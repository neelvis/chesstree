package com.chesstree.game.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chesstree.app.localized
import com.chesstree.game.data.BotSeatConfig
import com.chesstree.game.data.LocalBotGameConfig
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.bot.BotDifficulty
import com.chesstree.game.domain.bot.BotPlayingProfile
import com.chesstree.game.domain.bot.BotPolicy
import kotlin.random.Random

@Composable
fun LocalBotSetupScreen(
    onDismiss: () -> Unit,
    onStart: (LocalBotGameConfig) -> Unit,
    currentConfig: LocalBotGameConfig? = null,
) {
    var humanSeat by remember(currentConfig) { mutableStateOf(currentConfig?.humanSeat ?: PlayerId.WHITE) }
    var seats by remember(currentConfig) {
        mutableStateOf(
            PlayerId.entries.associateWith { player ->
                val currentSeat = currentConfig?.seatFor(player)
                BotSeatDraft(
                    profile = currentSeat?.profile ?: BotPlayingProfile.UNIVERSAL,
                    difficulty = currentSeat?.difficulty ?: BotDifficulty.NORMAL,
                )
            },
        )
    }

    Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(localized("bot_setup_title"), style = MaterialTheme.typography.headlineSmall)
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BotSettingMenu(
                        label = localized("your_color"), selected = humanSeat, choices = PlayerId.entries,
                        choiceLabel = { localized(it.seatNameKey()) }, onSelect = { humanSeat = it },
                    )
                    PlayerId.entries.filter { it != humanSeat }.forEach { player ->
                        val seatName = localized("bot_setup_seat", localized(player.seatNameKey()))
                        val draft = seats.getValue(player)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(seatName, style = MaterialTheme.typography.titleSmall)
                            BotSettingMenu(
                                label = localized("bot_setup_profile"), selected = draft.profile,
                                choices = BotPlayingProfile.entries, choiceLabel = { localized(it.profileNameKey()) },
                                owner = seatName, onSelect = { seats = seats + (player to draft.copy(profile = it)) },
                            )
                            BotSettingMenu(
                                label = localized("bot_setup_difficulty"), selected = draft.difficulty,
                                choices = BotDifficulty.entries, choiceLabel = { localized(it.difficultyNameKey()) },
                                owner = seatName, onSelect = { seats = seats + (player to draft.copy(difficulty = it)) },
                            )
                        }
                    }
                    Text(localized("bot_setup_new_game_note"), style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(localized("cancel")) }
                    TextButton(onClick = {
                        onStart(LocalBotGameConfig(
                            humanSeat = humanSeat,
                            seats = PlayerId.entries.filter { it != humanSeat }.map { player ->
                                val draft = seats.getValue(player)
                                BotSeatConfig(player, draft.profile, draft.difficulty, Random.nextLong())
                            },
                            basePolicy = BotPolicy.DEFAULT,
                        ))
                    }) { Text(localized("bot_setup_start")) }
                }
            }
        }
    }

}

@Composable
private fun <T> BotSettingMenu(
    label: String,
    selected: T,
    choices: List<T>,
    choiceLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    owner: String? = null,
) {
    var expanded by remember(owner, label) { mutableStateOf(false) }
    val selectedLabel = choiceLabel(selected)
    val buttonLabel = localized("bot_setup_option", label, selectedLabel)
    val description = if (owner == null) buttonLabel else {
        localized("bot_setup_setting_description", owner, label, selectedLabel)
    }
    Column {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        ) {
            Text(buttonLabel)
        }
        if (expanded) {
            choices.forEach { choice ->
                TextButton(
                    modifier = Modifier.fillMaxWidth().semantics { this.selected = choice == selected },
                    onClick = {
                        expanded = false
                        onSelect(choice)
                    },
                ) { Text(choiceLabel(choice)) }
            }
        }
    }
}

private data class BotSeatDraft(
    val profile: BotPlayingProfile,
    val difficulty: BotDifficulty,
)

private fun PlayerId.seatNameKey(): String = when (this) {
    PlayerId.WHITE -> "turn_player_white"
    PlayerId.RED -> "turn_player_red"
    PlayerId.BLACK -> "turn_player_black"
}

private fun BotPlayingProfile.profileNameKey(): String = when (this) {
    BotPlayingProfile.UNIVERSAL -> "bot_setup_profile_universal"
    BotPlayingProfile.ATTACKING -> "bot_setup_profile_attacking"
    BotPlayingProfile.POSITIONAL -> "bot_setup_profile_positional"
    BotPlayingProfile.ENDGAME -> "bot_setup_profile_endgame"
}

private fun BotDifficulty.difficultyNameKey(): String = when (this) {
    BotDifficulty.BEGINNER -> "bot_setup_difficulty_beginner"
    BotDifficulty.NORMAL -> "bot_setup_difficulty_normal"
    BotDifficulty.STRONG -> "bot_setup_difficulty_strong"
}
