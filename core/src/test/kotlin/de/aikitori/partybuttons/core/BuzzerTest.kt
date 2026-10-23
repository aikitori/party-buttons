package de.aikitori.partybuttons.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuzzerTest {
    private val game = Game("ABCDE")
    private val anna = ConnectionId(1)
    private val ben = ConnectionId(2)
    private val carla = ConnectionId(3)

    init {
        game.join(anna, "a".repeat(22), "Anna")
        game.join(ben, "b".repeat(22), "Ben")
        game.join(carla, "c".repeat(22), "Carla")
    }

    private fun ranking() = game.round().presses.map { it.name to it.offsetMs }

    private fun stateOf(effects: List<Effect>, conn: ConnectionId): ServerMessage.State =
        effects.filterIsInstance<Effect.Send>().filter { it.to == conn }.map { it.message }
            .filterIsInstance<ServerMessage.State>().last()

    @Test
    fun `vor der ersten Runde ist der Buzzer aus`() {
        assertEquals(Phase.IDLE, game.round().phase)
        assertTrue(game.press(anna, 0, 100).isEmpty())
        assertTrue(game.round().presses.isEmpty())
    }

    @Test
    fun `Freigeben schickt allen die offene Runde`() {
        val effects = game.openRound()
        for (conn in listOf(anna, ben, carla)) {
            assertEquals(ServerMessage.State(1, Phase.OPEN, null), stateOf(effects, conn))
        }
    }

    @Test
    fun `wer zuerst drückt, gewinnt und sperrt alle`() {
        game.openRound()
        val effects = game.press(ben, 1, 1000)
        assertEquals(ServerMessage.State(1, Phase.LOCKED, 1), stateOf(effects, ben))
        assertEquals(ServerMessage.State(1, Phase.LOCKED, null), stateOf(effects, anna))
        assertEquals(listOf("Ben" to 0L), ranking())
    }

    @Test
    fun `Drücke kurz nach dem Sperren zählen noch für die Reihenfolge`() {
        game.openRound()
        game.press(ben, 1, 1000)
        game.press(anna, 1, 1084)
        game.press(carla, 1, 1000 + Rules.LATE_PRESS_WINDOW_MS + 1) // zu spät
        assertEquals(listOf("Ben" to 0L, "Anna" to 84L), ranking())
        assertEquals(Phase.LOCKED, game.round().phase)
    }

    @Test
    fun `jeder drückt pro Runde nur einmal`() {
        game.setLockOnFirst(false)
        game.openRound()
        game.press(anna, 1, 1000)
        assertTrue(game.press(anna, 1, 1001).isEmpty())
        assertEquals(1, game.round().presses.size)
    }

    @Test
    fun `ohne Sperre nach dem ersten drücken alle, bis der Host sperrt`() {
        game.setLockOnFirst(false)
        game.openRound()
        game.press(carla, 1, 1000)
        assertEquals(Phase.OPEN, game.round().phase)
        game.press(anna, 1, 1500)
        game.press(ben, 1, 3000)
        assertEquals(listOf("Carla" to 0L, "Anna" to 500L, "Ben" to 2000L), ranking())

        val effects = game.lockRound(4000)
        assertEquals(ServerMessage.State(1, Phase.LOCKED, 3), stateOf(effects, ben))
    }

    @Test
    fun `Druck für eine alte Runde zählt nicht`() {
        game.openRound()
        game.press(anna, 1, 1000)
        game.openRound()
        assertTrue(game.press(ben, 1, 1100).isEmpty())
        assertEquals(2, game.round().number)
        assertTrue(game.round().presses.isEmpty())
    }

    @Test
    fun `neue Runde löscht das Ergebnis`() {
        game.openRound()
        game.press(anna, 1, 1000)
        val effects = game.openRound()
        assertEquals(ServerMessage.State(2, Phase.OPEN, null), stateOf(effects, anna))
        assertTrue(game.round().presses.isEmpty())
    }

    @Test
    fun `wer wiederkommt, erfährt seinen Platz`() {
        game.openRound()
        game.press(anna, 1, 1000)
        game.disconnected(anna)
        val effects = game.hello(ConnectionId(9), "a".repeat(22))
        assertEquals(ServerMessage.State(1, Phase.LOCKED, 1), stateOf(effects, ConnectionId(9)))
    }

    @Test
    fun `entfernter Spieler verschwindet aus der Reihenfolge`() {
        game.setLockOnFirst(false)
        game.openRound()
        game.press(anna, 1, 1000)
        game.press(ben, 1, 1200)
        val effects = game.remove(1)
        assertEquals(listOf("Ben" to 0L), ranking())
        assertEquals(1, stateOf(effects, ben).myRank)
    }

    @Test
    fun `Sperren ohne offene Runde ändert nichts`() {
        assertTrue(game.lockRound(1000).isEmpty())
        assertEquals(Phase.IDLE, game.round().phase)
    }
}
