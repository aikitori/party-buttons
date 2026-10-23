package de.aikitori.partybuttons

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import de.aikitori.partybuttons.core.Phase
import de.aikitori.partybuttons.core.PlayerView
import de.aikitori.partybuttons.core.RoundView
import de.aikitori.partybuttons.core.Rules

// AppCompatActivity statt ComponentActivity: nur damit funktioniert AppCompatDelegate.setApplicationLocales
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PartyButtonsTheme {
                Surface(Modifier.fillMaxSize()) {
                    HostScreen()
                }
            }
        }
    }
}

@Composable
private fun PartyButtonsTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun HostScreen(model: HostViewModel = viewModel()) {
    val state by model.state.collectAsState()
    Column(
        Modifier.safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        when (val s = state) {
            HostState.Idle, is HostState.Failed -> {
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.titleMedium)
                if (s is HostState.Failed) {
                    Text(stringResource(R.string.start_failed, s.message), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = model::start, Modifier.fillMaxWidth()) { Text(stringResource(R.string.start_game)) }
                LanguagePicker()
            }
            HostState.Starting -> Text(stringResource(R.string.starting))
            is HostState.Running -> RunningGame(s, model)
        }
    }
}

@Composable
private fun ColumnScope.RunningGame(state: HostState.Running, model: HostViewModel) {
    // oben: wie man reinkommt
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.room_code), style = MaterialTheme.typography.labelLarge)
        Text(state.roomCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 28.sp)
    }
    if (state.urls.isEmpty()) {
        Text(stringResource(R.string.no_address), color = MaterialTheme.colorScheme.error)
    } else {
        Text(stringResource(R.string.join_hint), style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Column { state.urls.forEach { Text(it, fontFamily = FontFamily.Monospace) } }
        }
    }
    HorizontalDivider()

    // Mitte: Runde und Ergebnis, darunter die Spieler; scrollt bei vielen Spielern
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { RoundResult(state.round) }
        item {
            Text(
                stringResource(R.string.players_title, state.players.size, Rules.MAX_PLAYERS),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (state.players.isEmpty()) {
            item { Text(stringResource(R.string.no_players), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.players, key = { it.id }) { player -> PlayerRow(player, model::removePlayer) }
    }

    // unten: Steuerung
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = state.round.lockOnFirst, onCheckedChange = model::setLockOnFirst)
        Text(stringResource(R.string.lock_on_first), Modifier.padding(start = 12.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = model::openRound, Modifier.weight(1f)) {
            Text(stringResource(if (state.round.number == 0) R.string.open_round else R.string.next_round))
        }
        if (state.round.phase == Phase.OPEN) {
            FilledTonalButton(onClick = model::lockRound, Modifier.weight(1f)) { Text(stringResource(R.string.lock_round)) }
        }
    }
    TextButton(onClick = model::stop, Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop_game)) }
}

@Composable
private fun RoundResult(round: RoundView) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                when (round.phase) {
                    Phase.IDLE -> R.string.phase_idle
                    Phase.OPEN -> R.string.phase_open
                    Phase.LOCKED -> R.string.phase_locked
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        val first = round.presses.firstOrNull()
        if (first != null) {
            // Platz 1 groß, in der Farbe des Spielers
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(40.dp).background(parseColor(first.color), CircleShape))
                Text(first.name, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            for (press in round.presses.drop(1)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.rank, press.rank), Modifier.width(32.dp))
                    Box(Modifier.size(14.dp).background(parseColor(press.color), CircleShape))
                    Text(press.name, Modifier.weight(1f))
                    Text(
                        stringResource(R.string.offset_ms, press.offsetMs),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (round.phase == Phase.LOCKED) {
            Text(stringResource(R.string.nobody_pressed), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayerRow(player: PlayerView, onRemove: (Int) -> Unit) {
    val alpha = if (player.connected) 1f else 0.45f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(16.dp).background(parseColor(player.color).copy(alpha = alpha), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(player.name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(
                stringResource(if (player.connected) R.string.player_connected else R.string.player_disconnected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onRemove(player.id) }) { Text(stringResource(R.string.remove_player)) }
    }
}

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

/** Sprache der App: System, Englisch oder Deutsch. Android merkt sich die Wahl selbst. */
@Composable
private fun LanguagePicker() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val choices = listOf(
        "" to R.string.language_system,
        "en" to R.string.language_en,
        "de" to R.string.language_de,
    )
    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((tag, label) in choices) {
            FilterChip(
                selected = current == tag,
                onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}
