package de.aikitori.partybuttons.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FairnessTest {
    private val game = Game("ABCDE")
    private val anna = ConnectionId(1)
    private val ben = ConnectionId(2)

    init {
        game.join(anna, "a".repeat(22), "Anna")
        game.join(ben, "b".repeat(22), "Ben")
    }

    private fun ranking() = game.round().presses.map { it.name to it.offsetMs }

    @Test
    fun `langsame Verbindung wird ausgeglichen`() {
        game.reportRtt(anna, 10) // schnelles WLAN, 5 ms eine Richtung
        game.reportRtt(ben, 120) // weit weg vom Router, 60 ms
        game.setLockOnFirst(false)
        game.openRound(0)
        // Ben drückt 20 ms vor Anna, seine Nachricht kommt aber 35 ms später an
        game.press(anna, 1, 1005) // gedrückt bei 1000
        game.press(ben, 1, 1040) // gedrückt bei 980
        assertEquals(listOf("Ben" to 0L, "Anna" to 20L), ranking())
    }

    @Test
    fun `auch nach dem automatischen Sperren zählt der wirklich Erste`() {
        game.reportRtt(anna, 10)
        game.reportRtt(ben, 120)
        game.openRound(0)
        game.press(anna, 1, 1005) // kommt zuerst an und sperrt
        game.press(ben, 1, 1040) // war aber früher
        assertEquals("Ben", game.round().presses.first().name)
        assertEquals(Phase.LOCKED, game.round().phase)
    }

    @Test
    fun `Ausgleich ist begrenzt`() {
        game.reportRtt(ben, 2000) // absichtlich langsame Pongs
        game.setLockOnFirst(false)
        game.openRound(0)
        game.press(anna, 1, 1000)
        game.press(ben, 1, 1050) // höchstens 100 ms gut geschrieben: 950
        assertEquals(listOf("Ben" to 0L, "Anna" to 50L), ranking())
    }

    @Test
    fun `Laufzeit ist geglättet und beim Host sichtbar`() {
        game.reportRtt(anna, 20)
        game.reportRtt(anna, 120) // Ausreißer zählt nur zu 30 %
        assertEquals(50L, game.players().first { it.name == "Anna" }.latencyMs)
        game.disconnected(anna)
        assertEquals(null, game.players().first { it.name == "Anna" }.latencyMs)
    }

    @Test
    fun `fast gleichzeitig heißt gleichzeitig`() {
        game.setLockOnFirst(false)
        game.openRound(0)
        game.press(anna, 1, 1000)
        game.press(ben, 1, 1000 + Rules.TIE_MS - 1)
        assertTrue(game.round().tie)
        game.openRound(0)
        game.press(anna, 2, 1000)
        game.press(ben, 2, 1000 + Rules.TIE_MS)
        assertFalse(game.round().tie)
    }
}
