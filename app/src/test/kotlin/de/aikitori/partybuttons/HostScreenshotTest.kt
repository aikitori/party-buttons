package de.aikitori.partybuttons

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import com.github.takahirom.roborazzi.captureRoboImage
import de.aikitori.partybuttons.core.ConnectionId
import de.aikitori.partybuttons.core.Game
import de.aikitori.partybuttons.core.Mode
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bilder des Host-Bildschirms ohne Handy: `./gradlew :app:recordRoborazziDebug` schreibt sie nach `app/screenshots/`.
 * Ein normaler Testlauf zeichnet die Bildschirme nur (fängt Abstürze), ohne Bilder zu speichern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HostScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun fixedTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @Test
    fun start() = capture("start", HostState.Idle)

    @Test
    fun lobby() = capture("lobby", Sample().running())

    @Test
    fun buzzer() = capture("buzzer", Sample().buzzerRound().running())

    /** Wie nach dem Wegscrollen des QR-Codes: Ergebnis, Teams und Spieler. */
    @Test
    fun buzzerScrolled() = capture("buzzer-scrolled", Sample().buzzerRound().running(), scrolled = true)

    @Test
    fun answersOpen() = capture("answers-open", Sample().answerRound(reveal = false).running())

    @Test
    fun answersRevealed() = capture("answers-revealed", Sample().answerRound(reveal = true).running())

    @Test
    fun teams() = capture("teams", Sample(teams = 2).buzzerRound().running(), scrolled = true)

    @Test
    @Config(qualifiers = "+de")
    fun teamsGerman() = capture("teams-de", Sample(teams = 2).buzzerRound().running(), scrolled = true)

    private fun capture(name: String, state: HostState, scrolled: Boolean = false) {
        compose.setContent {
            PartyButtonsTheme {
                Surface(Modifier.fillMaxSize()) { HostContent(state, start = {}) }
            }
        }
        if (scrolled) compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    /** Ein Spiel mit fünf Mitspielenden, gespielt über die echte Spiel-Logik. */
    private class Sample(teams: Int = 0) {
        private val game = Game("K7QXM")
        private val names = listOf("Sam", "Jamie", "Alex", "Robin", "Kim")
        private val conns = names.indices.map { ConnectionId(it + 1L) }

        /** Monotone Uhr des Spiels, so verschoben, dass Drücke um 20:15 Uhr liegen. */
        private val wallClockOffsetMs =
            ZonedDateTime.of(2026, 10, 23, 20, 15, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()

        init {
            names.forEachIndexed { i, name -> game.join(conns[i], "token-$i".padEnd(20, 'x'), name) }
            game.reportRtt(conns[1], 12)
            game.reportRtt(conns[3], 40)
            if (teams > 0) game.setTeams(teams)
            game.addScore(1, 3)
            game.addScore(2, 2)
            game.addScore(4, 1)
        }

        fun buzzerRound() = apply {
            game.setMode(Mode.BUZZER)
            game.setLockOnFirst(true)
            game.openRound(1_000)
            game.press(conns[1], 1, 3_412)
            game.press(conns[0], 1, 3_449)
            game.press(conns[3], 1, 3_731)
        }

        fun answerRound(reveal: Boolean) = apply {
            game.setMode(Mode.CHOICE)
            game.setOptions(4)
            game.openRound(1_000)
            game.answer(conns[2], 1, 2, 4_210)
            game.answer(conns[0], 1, 1, 5_020)
            game.answer(conns[1], 1, 2, 6_380)
            if (reveal) {
                game.answer(conns[4], 1, 0, 7_900)
                game.reveal(2)
            }
        }

        fun running() = HostState.Running(
            roomCode = game.roomCode,
            urls = listOf("http://192.168.178.23:8080/j/${game.roomCode}"),
            players = game.players(),
            round = game.round(),
            wallClockOffsetMs = wallClockOffsetMs,
            teams = game.teams(),
        )
    }
}
