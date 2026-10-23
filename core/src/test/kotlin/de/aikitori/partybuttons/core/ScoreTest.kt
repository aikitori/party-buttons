package de.aikitori.partybuttons.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScoreTest {
    private val game = Game("ABCDE")
    private val anna = ConnectionId(1)
    private val ben = ConnectionId(2)

    init {
        game.join(anna, "a".repeat(22), "Anna")
        game.join(ben, "b".repeat(22), "Ben")
    }

    private fun scores() = game.players().associate { it.name to it.score }

    private fun scoreSent(effects: List<Effect>, conn: ConnectionId) =
        effects.filterIsInstance<Effect.Send>().filter { it.to == conn }.map { it.message }
            .filterIsInstance<ServerMessage.State>().last().score

    @Test
    fun `richtige Antworten geben beim Auflösen einen Punkt`() {
        game.setMode(Mode.CHOICE)
        game.openRound(0)
        game.answer(anna, 1, 1, 100)
        game.answer(ben, 1, 2, 200)
        val effects = game.reveal(1)
        assertEquals(mapOf("Anna" to 1, "Ben" to 0), scores())
        assertEquals(1, scoreSent(effects, anna))
    }

    @Test
    fun `Umfrage gibt keine Punkte`() {
        game.setMode(Mode.CHOICE)
        game.openRound(0)
        game.answer(anna, 1, 1, 100)
        game.reveal(null)
        assertEquals(mapOf("Anna" to 0, "Ben" to 0), scores())
    }

    @Test
    fun `Host vergibt und korrigiert Punkte, der Spieler erfährt es`() {
        val effects = game.addScore(2, 1)
        assertEquals(1, scoreSent(effects, ben))
        game.addScore(2, 2)
        game.addScore(2, -1)
        assertEquals(mapOf("Anna" to 0, "Ben" to 2), scores())
    }

    @Test
    fun `Punkte bleiben nach Abbruch und gehen beim Übernehmen mit`() {
        game.addScore(1, 3)
        game.disconnected(anna)
        assertEquals(3, scoreSent(game.hello(ConnectionId(5), "a".repeat(22)), ConnectionId(5)))
        game.disconnected(ConnectionId(5))
        val annaNew = ConnectionId(6)
        game.join(annaNew, "n".repeat(22), "Anna neu")
        game.addScore(3, 1)
        game.merge(1, 3)
        assertEquals(mapOf("Anna" to 4, "Ben" to 0), scores())
    }

    @Test
    fun `Zurücksetzen stellt alle auf 0`() {
        game.addScore(1, 2)
        game.addScore(2, 5)
        val effects = game.resetScores()
        assertEquals(mapOf("Anna" to 0, "Ben" to 0), scores())
        assertEquals(0, scoreSent(effects, ben))
    }
}
