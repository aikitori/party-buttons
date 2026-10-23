package de.aikitori.partybuttons.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChoiceTest {
    private val game = Game("ABCDE")
    private val anna = ConnectionId(1)
    private val ben = ConnectionId(2)
    private val carla = ConnectionId(3)

    init {
        game.join(anna, "a".repeat(22), "Anna")
        game.join(ben, "b".repeat(22), "Ben")
        game.join(carla, "c".repeat(22), "Carla")
        game.setMode(Mode.CHOICE)
    }

    private fun stateOf(effects: List<Effect>, conn: ConnectionId): ServerMessage.State? =
        effects.filterIsInstance<Effect.Send>().filter { it.to == conn }.map { it.message }
            .filterIsInstance<ServerMessage.State>().lastOrNull()

    @Test
    fun `Runde mit vier Knöpfen ist Standard`() {
        val effects = game.openRound(0)
        assertEquals(ServerMessage.State(1, Phase.OPEN, null, Mode.CHOICE, 4), stateOf(effects, anna))
    }

    @Test
    fun `Anzahl der Knöpfe ist einstellbar und begrenzt`() {
        game.setOptions(6)
        assertEquals(6, stateOf(game.openRound(0), anna)!!.options)
        game.setOptions(1)
        assertEquals(2, game.round().nextOptions)
        game.setOptions(99)
        assertEquals(8, game.round().nextOptions)
    }

    @Test
    fun `Einstellung gilt erst für die nächste Runde`() {
        game.openRound(0)
        game.setOptions(3)
        game.setMode(Mode.BUZZER)
        assertEquals(Mode.CHOICE, game.round().mode)
        assertEquals(4, game.round().options)
        game.openRound(10)
        assertEquals(Mode.BUZZER, game.round().mode)
    }

    @Test
    fun `Antwort steht fest und nur der Spieler selbst erfährt davon`() {
        game.openRound(0)
        val effects = game.answer(anna, 1, 2, 1500)
        assertEquals(listOf(anna), effects.map { (it as Effect.Send).to })
        assertEquals(2, stateOf(effects, anna)!!.myAnswer)
        assertNull(stateOf(effects, anna)!!.counts)
        assertTrue(game.answer(anna, 1, 0, 1600).isEmpty(), "kein Umentscheiden")
        assertEquals(listOf(2), game.round().answers.map { it.option })
        assertEquals(1500L, game.round().answers.single().offsetMs)
    }

    @Test
    fun `ungültige Antworten zählen nicht`() {
        game.openRound(0)
        assertTrue(game.answer(anna, 1, 4, 100).isEmpty(), "Knopf E bei vier Knöpfen")
        assertTrue(game.answer(anna, 1, -1, 100).isEmpty())
        assertTrue(game.answer(anna, 0, 1, 100).isEmpty(), "alte Runde")
        assertTrue(game.press(anna, 1, 100).isEmpty(), "Buzzer gibt es in dieser Runde nicht")
        assertTrue(game.round().answers.isEmpty())
    }

    @Test
    fun `Host sieht während der Runde nicht, wer richtig liegt`() {
        game.openRound(0)
        game.answer(anna, 1, 1, 100)
        assertNull(game.round().correct)
        assertNull(game.round().answers.single().correct)
    }

    @Test
    fun `Auflösen zeigt richtig und falsch, die schnellsten Richtigen zuerst`() {
        game.openRound(0)
        game.answer(ben, 1, 1, 900)
        game.answer(anna, 1, 2, 1200)
        game.answer(carla, 1, 1, 2500)
        val effects = game.reveal(1)

        val forBen = stateOf(effects, ben)!!
        assertEquals(Phase.LOCKED, forBen.phase)
        assertEquals(1, forBen.correct)
        assertEquals(1, forBen.myRank)
        assertEquals(listOf(0, 2, 1, 0), forBen.counts)
        assertEquals(2, stateOf(effects, carla)!!.myRank)
        assertNull(stateOf(effects, anna)!!.myRank, "falsch geantwortet")
        assertEquals(2, stateOf(effects, anna)!!.myAnswer)

        assertEquals(listOf(true, false, true), game.round().answers.map { it.correct })
        assertTrue(game.answer(anna, 1, 1, 3000).isEmpty(), "nach dem Auflösen zählt nichts mehr")
    }

    @Test
    fun `Umfrage ohne richtige Antwort zeigt nur die Verteilung`() {
        game.openRound(0)
        game.answer(anna, 1, 0, 100)
        game.answer(ben, 1, 0, 200)
        val forAnna = stateOf(game.reveal(null), anna)!!
        assertNull(forAnna.correct)
        assertNull(forAnna.myRank)
        assertEquals(listOf(2, 0, 0, 0), forAnna.counts)
    }

    @Test
    fun `wer nicht geantwortet hat, sieht trotzdem das Ergebnis`() {
        game.openRound(0)
        game.answer(anna, 1, 3, 100)
        val forCarla = stateOf(game.reveal(3), carla)!!
        assertNull(forCarla.myAnswer)
        assertEquals(3, forCarla.correct)
    }

    @Test
    fun `Auflösen nur in offener Runde mit gültigem Knopf`() {
        assertTrue(game.reveal(0).isEmpty())
        game.openRound(0)
        assertTrue(game.reveal(4).isEmpty())
        assertTrue(game.lockRound(100).isEmpty(), "Sperren gibt es nur beim Buzzer")
        assertEquals(Phase.OPEN, game.round().phase)
    }

    @Test
    fun `wer wiederkommt, sieht seine Antwort`() {
        game.openRound(0)
        game.answer(anna, 1, 2, 100)
        game.disconnected(anna)
        assertEquals(2, stateOf(game.hello(ConnectionId(9), "a".repeat(22)), ConnectionId(9))!!.myAnswer)
    }
}
