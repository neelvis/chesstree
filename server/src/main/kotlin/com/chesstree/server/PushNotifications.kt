package com.chesstree.server

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.scenario.StandardGame
import com.chesstree.game.domain.session.GameSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.util.UUID

interface PushNotifications {
    fun gameStarted(game: GameRecord)
    fun gameStateChanged(state: GameStateRecord)
}

object NoOpPushNotifications : PushNotifications {
    override fun gameStarted(game: GameRecord) = Unit
    override fun gameStateChanged(state: GameStateRecord) = Unit
}

fun interface PushSender {
    suspend fun send(device: PushDevice, notification: PushNotification): PushSendResult
}

data class PushNotification(
    val type: String,
    val title: String,
    val body: String,
    val gameCode: String,
    val deepLink: String,
)

enum class PushSendResult { SENT, TOKEN_UNREGISTERED }

class FcmPushNotifications private constructor(
    private val store: ChessTreeStore,
    private val sender: PushSender,
    private val publicBaseUrl: String,
    private val logWarning: (String) -> Unit,
) : PushNotifications, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun gameStarted(game: GameRecord) {
        if (game.status != GameStatus.ACTIVE) return
        dispatch(
            game.players.mapTo(linkedSetOf()) { it.user.id },
            PushNotification(
                type = "game_started",
                title = "Партия началась",
                body = "Все игроки в комнате. Нажмите, чтобы открыть партию.",
                gameCode = game.code,
                deepLink = game.deepLink(),
            ),
        )
    }

    override fun gameStateChanged(state: GameStateRecord) {
        if (state.game.status == GameStatus.FINISHED) {
            dispatch(
                state.game.players.mapTo(linkedSetOf()) { it.user.id },
                PushNotification(
                    type = "game_finished",
                    title = "Партия завершена",
                    body = "Откройте партию, чтобы посмотреть результат.",
                    gameCode = state.game.code,
                    deepLink = state.game.deepLink(),
                ),
            )
            return
        }

        if (state.game.status != GameStatus.ACTIVE) return
        val gameState = state.domainState ?: GameSession.replay(
            StandardGame.scenario,
            state.moves.map(GameMoveRecord::intent),
        )?.state ?: run {
            logWarning("Push skipped because the stored game history could not be replayed")
            return
        }
        if (gameState.phase is GamePhase.Finished) return
        val turn = gameState.turn?.player ?: return
        val nextPlayer = state.game.players.firstOrNull { it.color?.name == turn.name } ?: return
        dispatch(
            setOf(nextPlayer.user.id),
            PushNotification(
                type = "your_turn",
                title = "Ваш ход",
                body = "Ваш ход в партии ${state.game.code}.",
                gameCode = state.game.code,
                deepLink = state.game.deepLink(),
            ),
        )
    }

    private fun dispatch(userIds: Set<UUID>, notification: PushNotification) {
        if (userIds.isEmpty()) return
        scope.launch {
            try {
                store.findPushDevices(userIds).forEach { device ->
                    try {
                        if (sender.send(device, notification) == PushSendResult.TOKEN_UNREGISTERED) {
                            store.removePushDevice(device.userId, device.token)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        logWarning("Push delivery failed; game state remains available through the API")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                logWarning("Push delivery could not load device registrations")
            }
        }
    }

    private fun GameRecord.deepLink(): String = "${publicBaseUrl.trimEnd('/')}/g/$code"

    override fun close() {
        scope.cancel()
        (sender as? AutoCloseable)?.close()
    }

    companion object {
        fun fromServiceAccountFile(
            store: ChessTreeStore,
            serviceAccountFile: String?,
            publicBaseUrl: String,
            logWarning: (String) -> Unit,
        ): FcmPushNotifications? {
            val file = serviceAccountFile?.takeIf(String::isNotBlank) ?: return null
            if (!File(file).isFile) {
                logWarning("Firebase service-account file is unavailable; push notifications are disabled")
                return null
            }
            val projectId = FileInputStream(file).use { input ->
                Json.parseToJsonElement(input.reader().readText())
                    .let { (it as? kotlinx.serialization.json.JsonObject)?.get("project_id") }
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?.takeIf(String::isNotBlank)
                    ?: error("Firebase service account is missing project_id")
            }
            val credentials = FileInputStream(file).use(GoogleCredentials::fromStream)
                .createScoped("https://www.googleapis.com/auth/firebase.messaging")
            val firebaseApp = FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build(),
                "chesstree-fcm",
            )
            val messaging = FirebaseMessaging.getInstance(firebaseApp)
            val sender = FirebaseAdminPushSender(messaging)
            return FcmPushNotifications(store, sender, publicBaseUrl, logWarning)
        }
    }
}

private class FirebaseAdminPushSender(
    private val messaging: FirebaseMessaging,
) : PushSender {
    override suspend fun send(device: PushDevice, notification: PushNotification): PushSendResult =
        withContext(Dispatchers.IO) {
            val message = Message.builder()
                .setToken(device.token)
                .setNotification(
                    Notification.builder()
                        .setTitle(notification.title)
                        .setBody(notification.body)
                        .build(),
                )
                .putData("type", notification.type)
                .putData("gameCode", notification.gameCode)
                .putData("deepLink", notification.deepLink)
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(
                            AndroidNotification.builder()
                                .setClickAction("OPEN_GAME")
                                .setTag("chesstree-${notification.gameCode}")
                                .build(),
                        )
                        .build(),
                )
                .setApnsConfig(
                    ApnsConfig.builder()
                        .putHeader("apns-push-type", "alert")
                        .putHeader("apns-priority", "10")
                        .setAps(Aps.builder().setSound("default").build())
                        .build(),
                )
                .build()
            try {
                messaging.send(message)
                PushSendResult.SENT
            } catch (error: FirebaseMessagingException) {
                if (error.messagingErrorCode == MessagingErrorCode.UNREGISTERED) {
                    PushSendResult.TOKEN_UNREGISTERED
                } else {
                    throw error
                }
            }
        }
}
