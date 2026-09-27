package com.chesstree.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.NoOpGameSaveStore
import com.chesstree.game.presentation.history.GameLogExporter
import com.chesstree.game.presentation.history.NoOpGameLogExporter
import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.data.ApiResult
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.NoOpOnlineSessionStore
import com.chesstree.multiplayer.data.NoOpPushTokenProvider
import com.chesstree.multiplayer.data.OnlineSessionStore
import com.chesstree.multiplayer.data.PushTokenProvider
import com.chesstree.multiplayer.presentation.GameLinkSharer
import com.chesstree.multiplayer.presentation.MultiplayerScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun App(
    gameSaveStore: GameSaveStore = NoOpGameSaveStore,
    onlineApi: ChessTreeApi? = null,
    onlineSessionStore: OnlineSessionStore = NoOpOnlineSessionStore,
    pushTokenProvider: PushTokenProvider = NoOpPushTokenProvider,
    initialGameCode: String? = null,
    gameLinkSharer: GameLinkSharer? = null,
    gameLogExporter: GameLogExporter = NoOpGameLogExporter,
) {
    ChessTreeTheme {
        val saveableStateHolder = rememberSaveableStateHolder()
        val settingsSaver = remember {
            Saver<GameSettings, String>(
                save = { encodeGameSettings(it) },
                restore = ::restoreGameSettings,
            )
        }
        var settings by rememberSaveable(stateSaver = settingsSaver) {
            mutableStateOf(GameSettings())
        }
        var selectedTab by rememberSaveable { mutableStateOf(AppTab.GAMES) }
        var authenticatedUser by remember { mutableStateOf<AuthResponse?>(null) }
        var accountLoading by remember { mutableStateOf(onlineApi != null) }
        var accountError by remember { mutableStateOf<String?>(null) }
        val accountScope = rememberCoroutineScope()
        var showChessGame by rememberSaveable { mutableStateOf(false) }
        var returnToSettingsAfterAuthentication by rememberSaveable { mutableStateOf(false) }
        var showProfileInMultiplayer by rememberSaveable { mutableStateOf(false) }
        var multiplayerResetKey by rememberSaveable { mutableStateOf(0) }
        var showMultiplayer by rememberSaveable { mutableStateOf(initialGameCode != null) }
        PlatformBackHandler(enabled = showChessGame && !showMultiplayer) {
            if (selectedTab == AppTab.SETTINGS) {
                selectedTab = AppTab.GAMES
            } else {
                selectedTab = AppTab.GAMES
                showChessGame = false
            }
        }
        LaunchedEffect(initialGameCode) {
            if (initialGameCode != null) showMultiplayer = true
        }
        LaunchedEffect(onlineApi, onlineSessionStore, initialGameCode) {
            if (onlineApi != null) {
                try {
                    authenticatedUser = onlineSessionStore.load()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    accountError = "i18n:login_restore_failed"
                } finally {
                    accountLoading = false
                }
            }
        }
        LaunchedEffect(onlineSessionStore) {
            onlineSessionStore.invalidations().collect {
                authenticatedUser = null
                accountError = null
                accountLoading = false
                multiplayerResetKey += 1
            }
        }
        LaunchedEffect(onlineApi, authenticatedUser?.accessToken, pushTokenProvider) {
            val api = onlineApi ?: return@LaunchedEffect
            val authToken = authenticatedUser?.accessToken ?: return@LaunchedEffect
            try {
                if (!pushTokenProvider.requestPermission()) return@LaunchedEffect
                pushTokenProvider.currentToken()?.let { deviceToken ->
                    api.registerPushDevice(authToken, deviceToken, pushTokenProvider.platform)
                }
                pushTokenProvider.tokenUpdates().collect { deviceToken ->
                    api.registerPushDevice(authToken, deviceToken, pushTokenProvider.platform)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Push registration is optional and retried on the next launch or token refresh.
            }
        }
        val profileContent: @Composable (Modifier) -> Unit = { modifier ->
            SettingsScreen(
                settings = settings,
                onSettingsChanged = { updated -> settings = updated },
                username = authenticatedUser?.user?.username,
                accountLoading = accountLoading,
                accountError = accountError,
                onLogout = if (authenticatedUser != null && onlineApi != null) {
                    {
                        val authentication = authenticatedUser
                        accountScope.launch {
                            accountLoading = true
                            try {
                                if (authentication != null) {
                                    try {
                                        pushTokenProvider.currentToken()?.let { deviceToken ->
                                            onlineApi.unregisterPushDevice(
                                                authentication.accessToken,
                                                deviceToken,
                                            )
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Throwable) {
                                    }
                                }
                                if (authentication != null) {
                                    when (onlineApi.logout(authentication.accessToken)) {
                                        is ApiResult.Success -> Unit
                                        is ApiResult.Failure -> {
                                            accountError = "i18n:logout_failed"
                                            return@launch
                                        }
                                    }
                                }
                                onlineSessionStore.clear()
                                authenticatedUser = null
                                accountError = null
                                multiplayerResetKey += 1
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                accountError = "i18n:logout_failed"
                            } finally {
                                accountLoading = false
                            }
                        }
                    }
                } else null,
                onOpenProfile = if (onlineApi != null) {
                    {
                        returnToSettingsAfterAuthentication = true
                        showProfileInMultiplayer = false
                        showMultiplayer = true
                    }
                } else null,
                modifier = modifier,
            )
        }
        if (showMultiplayer && onlineApi != null) {
            MultiplayerScreen(
                api = onlineApi,
                sessionStore = onlineSessionStore,
                resetKey = multiplayerResetKey,
                initialGameCode = initialGameCode.orEmpty(),
                gameLinkSharer = gameLinkSharer,
                selectedTab = selectedTab,
                showProfile = showProfileInMultiplayer,
                profileContent = profileContent,
                pieceSet = settings.pieceSet,
                showCurrentPossibleMoves = settings.showCurrentPossibleMoves,
                showMoveLines = settings.showMoveLines,
                zoomBeforeMove = settings.zoomBeforeMove,
                animatePieceMovement = settings.animatePieceMovement,
                onAuthenticationSuccess = { authentication ->
                    authenticatedUser = authentication
                    if (returnToSettingsAfterAuthentication) {
                        returnToSettingsAfterAuthentication = false
                        showMultiplayer = false
                        showChessGame = false
                        selectedTab = AppTab.SETTINGS
                        showProfileInMultiplayer = false
                    }
                },
                onSelectTab = { tab ->
                    selectedTab = tab
                    showProfileInMultiplayer = tab == AppTab.SETTINGS
                },
                onClose = {
                    showMultiplayer = false
                    showChessGame = false
                    showProfileInMultiplayer = false
                    returnToSettingsAfterAuthentication = false
                },
            )
            return@ChessTreeTheme
        }
        if (selectedTab == AppTab.SETTINGS || !showChessGame) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                when (selectedTab) {
                    AppTab.GAMES -> GamesHomeScreen(
                        onPlaySolo = {
                            selectedTab = AppTab.GAMES
                            showChessGame = true
                        },
                        onMultiplayer = onlineApi?.let {
                            {
                                returnToSettingsAfterAuthentication = false
                                showProfileInMultiplayer = false
                                showMultiplayer = true
                            }
                        },
                        username = authenticatedUser?.user?.username,
                        modifier = Modifier.weight(1f),
                    )

                    AppTab.SETTINGS -> profileContent(Modifier.weight(1f))
                }
                AppTabBar(
                    selected = selectedTab,
                    onSelected = { selectedTab = it },
                )
            }
            return@ChessTreeTheme
        }
        saveableStateHolder.SaveableStateProvider("chess") {
            ChessNavigationRoot(
                gameSaveStore = gameSaveStore,
                settings = settings,
                onSettingsChanged = { settings = it },
                gameLogExporter = gameLogExporter,
                selectedTab = selectedTab,
                onSelectTab = { tab -> selectedTab = tab },
                onBack = {
                    selectedTab = AppTab.GAMES
                    showChessGame = false
                },
            )
        }
        return@ChessTreeTheme
    }
}
