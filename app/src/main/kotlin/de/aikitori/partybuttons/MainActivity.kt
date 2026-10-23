package de.aikitori.partybuttons

import android.os.Bundle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import de.aikitori.partybuttons.server.QrCode
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import de.aikitori.partybuttons.core.Mode
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
private fun HostScreen() {
    val state by HostSession.state.collectAsState()
    val context = LocalContext.current
    // ohne Erlaubnis läuft das Spiel trotzdem, nur die Benachrichtigung fehlt
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        HostSession.start(context)
    }
    val start = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            HostSession.start(context)
        }
    }
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
                Button(onClick = start, Modifier.fillMaxWidth()) { Text(stringResource(R.string.start_game)) }
                LanguagePicker()
            }
            HostState.Starting -> Text(stringResource(R.string.starting))
            is HostState.Running -> RunningGame(s)
        }
    }
}

@Composable
private fun ColumnScope.RunningGame(state: HostState.Running) {
    val context = LocalContext.current
    // Host-Bildschirm bleibt an, solange das Spiel läuft
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var largeQr by remember { mutableStateOf(false) }

    // oben: wie man reinkommt
    val url = state.urls.firstOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (url != null) {
            QrImage(
                url,
                Modifier.size(132.dp).clickable(onClickLabel = stringResource(R.string.qr_enlarge)) { largeQr = true },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.room_code), style = MaterialTheme.typography.labelLarge)
            Text(state.roomCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 28.sp)
            if (url != null) Text(stringResource(R.string.qr_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (url == null) {
        Text(stringResource(R.string.no_address), color = MaterialTheme.colorScheme.error)
    } else {
        Text(stringResource(R.string.join_hint), style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Column { state.urls.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp) } }
        }
    }
    if (largeQr && url != null) {
        Dialog(onDismissRequest = { largeQr = false }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    QrImage(url, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(state.roomCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 40.sp)
                    Text(url, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { largeQr = false }) { Text(stringResource(R.string.qr_close)) }
                }
            }
        }
    }
    HorizontalDivider()

    // Mitte: Runde und Ergebnis, darunter die Spieler; scrollt bei vielen Spielern
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { RoundResult(state.round, connectedPlayers = state.players.count { it.connected }, state.wallClockOffsetMs) }
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
        // Rangliste: meiste Punkte oben, bei Gleichstand in der Reihenfolge des Beitretens
        items(state.players.sortedByDescending { it.score }, key = { it.id }) { player -> PlayerRow(player, state.players) }
    }

    // unten: Steuerung
    Controls(state)
    var help by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    Row {
        TextButton(onClick = { help = true }, Modifier.weight(1f)) { Text(stringResource(R.string.help_button)) }
        TextButton(onClick = { resetting = true }, Modifier.weight(1f)) { Text(stringResource(R.string.reset_scores)) }
        TextButton(onClick = { HostSession.stop(context) }, Modifier.weight(1f)) { Text(stringResource(R.string.stop_game)) }
    }
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            text = { Text(stringResource(R.string.reset_scores_question)) },
            confirmButton = {
                TextButton(onClick = { HostSession.resetScores(); resetting = false }) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.help_title)) },
            text = { Text(stringResource(R.string.help_text)) },
            confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun Controls(state: HostState.Running) {
    val round = state.round
    // Einstellungen für die nächste Runde
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = round.nextMode == Mode.BUZZER,
            onClick = { HostSession.setMode(Mode.BUZZER) },
            label = { Text(stringResource(R.string.mode_buzzer)) },
        )
        FilterChip(
            selected = round.nextMode == Mode.CHOICE,
            onClick = { HostSession.setMode(Mode.CHOICE) },
            label = { Text(stringResource(R.string.mode_choice)) },
        )
        if (round.nextMode == Mode.CHOICE) {
            Spacer(Modifier.weight(1f))
            OptionsStepper(round.nextOptions, HostSession::setOptions)
        }
    }
    if (round.nextMode == Mode.BUZZER) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = round.lockOnFirst, onCheckedChange = HostSession::setLockOnFirst)
            Text(stringResource(R.string.lock_on_first), Modifier.padding(start = 12.dp))
        }
    }

    // Auflösen einer laufenden Runde „Antworten“
    if (round.phase == Phase.OPEN && round.mode == Mode.CHOICE) {
        Text(stringResource(R.string.reveal_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in 0 until round.options) {
                FilledTonalButton(
                    onClick = { HostSession.reveal(option) },
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp),
                ) { Text(Rules.optionLabel(option), fontWeight = FontWeight.Bold) }
            }
        }
        TextButton(onClick = { HostSession.reveal(null) }) { Text(stringResource(R.string.reveal_none)) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = HostSession::openRound, Modifier.weight(1f)) {
            Text(stringResource(if (round.number == 0) R.string.open_round else R.string.next_round))
        }
        if (round.phase == Phase.OPEN && round.mode == Mode.BUZZER) {
            FilledTonalButton(onClick = HostSession::lockRound, Modifier.weight(1f)) { Text(stringResource(R.string.lock_round)) }
        }
    }
}

@Composable
private fun OptionsStepper(count: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTextButton("−", stringResource(R.string.fewer_options), enabled = count > Rules.ANSWER_OPTIONS.first) {
            onChange(count - 1)
        }
        Text(stringResource(R.string.options_count, count))
        IconTextButton("+", stringResource(R.string.more_options), enabled = count < Rules.ANSWER_OPTIONS.last) {
            onChange(count + 1)
        }
    }
}

@Composable
private fun IconTextButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoundResult(round: RoundView, connectedPlayers: Int, wallClockOffsetMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                when (round.phase) {
                    Phase.IDLE -> R.string.phase_idle
                    Phase.OPEN -> if (round.mode == Mode.CHOICE) R.string.mode_choice else R.string.phase_open
                    Phase.LOCKED -> if (round.mode == Mode.CHOICE) R.string.mode_choice else R.string.phase_locked
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        when (round.mode) {
            Mode.BUZZER -> BuzzerResult(round, wallClockOffsetMs)
            Mode.CHOICE -> ChoiceResult(round, connectedPlayers, wallClockOffsetMs)
        }
    }
}

@Composable
private fun BuzzerResult(round: RoundView, wallClockOffsetMs: Long) {
    val first = round.presses.firstOrNull()
    if (first != null) {
        // Platz 1 groß, in der Farbe des Spielers
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(40.dp).background(parseColor(first.color), CircleShape))
            Column(Modifier.weight(1f)) {
                Text(first.name, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                PressTimes(first.atMs + wallClockOffsetMs, first.sinceOpenMs)
            }
            // der Host entscheidet, ob die Antwort stimmte
            FilledTonalButton(onClick = { HostSession.addScore(first.playerId, 1) }) { Text(stringResource(R.string.add_point)) }
        }
        if (round.tie) {
            Text(stringResource(R.string.tie), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        for (press in round.presses.drop(1)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rank, press.rank), Modifier.width(32.dp))
                Box(Modifier.size(14.dp).background(parseColor(press.color), CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(press.name)
                    PressTimes(press.atMs + wallClockOffsetMs, press.sinceOpenMs)
                }
                Text(
                    stringResource(R.string.offset_ms, press.offsetMs),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { HostSession.addScore(press.playerId, 1) }) { Text(stringResource(R.string.add_point)) }
            }
        }
    } else if (round.phase == Phase.LOCKED) {
        Text(stringResource(R.string.nobody_pressed), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Uhrzeit des Drucks (geschätzter Moment des Drückens, mit Laufzeitausgleich) und Zeit seit dem Freigeben. */
@Composable
private fun PressTimes(wallClockMs: Long, sinceOpenMs: Long) {
    Text(
        stringResource(R.string.press_times, formatClock(wallClockMs), sinceOpenMs / 1000.0),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun formatClock(wallClockMs: Long): String =
    Instant.ofEpochMilli(wallClockMs).atZone(ZoneId.systemDefault()).toLocalTime().format(clockFormat)

@Composable
private fun ChoiceResult(round: RoundView, connectedPlayers: Int, wallClockOffsetMs: Long) {
    if (round.phase == Phase.OPEN) {
        // nur wer, nicht was: sonst könnte man auf dem Host-Bildschirm abschauen
        Text(
            stringResource(R.string.answered_count, round.answers.size, maxOf(connectedPlayers, round.answers.size)),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(round.answers.joinToString(", ") { it.name }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // aufgelöst: Verteilung, richtige Antwort grün
    val counts = round.counts
    val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    for (option in 0 until round.options) {
        val isCorrect = option == round.correct
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Rules.optionLabel(option), Modifier.width(24.dp), fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f).height(24.dp)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(counts[option].toFloat() / max)
                        .background(if (isCorrect) CorrectGreen else MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Text(counts[option].toString(), Modifier.width(32.dp), fontFamily = FontFamily.Monospace)
        }
    }
    if (round.correct != null) {
        val correct = round.answers.filter { it.correct == true }.sortedBy { it.offsetMs }
        Text(stringResource(R.string.correct_title), style = MaterialTheme.typography.titleSmall)
        if (correct.isEmpty()) {
            Text(stringResource(R.string.nobody_correct), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        correct.forEachIndexed { i, answer ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rank, i + 1), Modifier.width(32.dp))
                Box(Modifier.size(14.dp).background(parseColor(answer.color), CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(answer.name, fontWeight = if (i == 0) FontWeight.Bold else null)
                    PressTimes(answer.atMs + wallClockOffsetMs, answer.offsetMs)
                }
                // Abstand zum schnellsten Richtigen
                if (i > 0) {
                    Text(
                        stringResource(R.string.offset_ms, answer.offsetMs - correct.first().offsetMs),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private val CorrectGreen = Color(0xFF2E8B3A)

/** QR-Code, immer schwarz auf weiß (Kameras kommen mit hell auf dunkel schlecht zurecht). */
@Composable
private fun QrImage(text: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrCode.encode(text) }
    Canvas(modifier.background(Color.White)) {
        val cell = size.minDimension / qr.size
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr[x, y]) {
                    // eine Spur größer, damit zwischen Nachbarn keine Haarlinien entstehen
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

@Composable
private fun PlayerRow(player: PlayerView, everyone: List<PlayerView>) {
    val alpha = if (player.connected) 1f else 0.45f
    var merging by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(16.dp).background(parseColor(player.color).copy(alpha = alpha), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(player.name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            val status = stringResource(if (player.connected) R.string.player_connected else R.string.player_disconnected)
            val latency = player.latencyMs?.let { " · " + stringResource(R.string.latency, it) } ?: ""
            Text(status + latency, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.score_points, player.score), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        IconTextButton("−", stringResource(R.string.remove_point), enabled = true) { HostSession.addScore(player.id, -1) }
        IconTextButton("+", stringResource(R.string.add_point), enabled = true) { HostSession.addScore(player.id, 1) }
        var menu by remember { mutableStateOf(false) }
        Box {
            IconTextButton("⋮", stringResource(R.string.player_menu, player.name), enabled = true) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!player.connected) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.merge_player)) },
                        onClick = { menu = false; merging = true },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remove_player)) },
                    onClick = { menu = false; HostSession.removePlayer(player.id) },
                )
            }
        }
    }
    if (merging) {
        // nur Verbundene kommen in Frage: das sind die, die sich gerade neu angemeldet haben
        val candidates = everyone.filter { it.connected && it.id != player.id }
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text(stringResource(R.string.merge_title, player.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.merge_hint), style = MaterialTheme.typography.bodySmall)
                    if (candidates.isEmpty()) Text(stringResource(R.string.merge_none))
                    for (candidate in candidates) {
                        TextButton(onClick = {
                            HostSession.mergePlayers(player.id, candidate.id)
                            merging = false
                        }) {
                            Box(Modifier.size(12.dp).background(parseColor(candidate.color), CircleShape))
                            Text(candidate.name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = false }) { Text(stringResource(R.string.cancel)) } },
        )
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
