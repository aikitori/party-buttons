package de.aikitori.partybuttons.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameTest {
    private val game = Game("ABCDE")
    private val tokenA = "a".repeat(22)
    private val tokenB = "b".repeat(22)
    private val c1 = ConnectionId(1)
    private val c2 = ConnectionId(2)
    private val c3 = ConnectionId(3)

    private fun welcome(effects: List<Effect>): ServerMessage.Welcome =
        effects.filterIsInstance<Effect.Send>().map { it.message }.filterIsInstance<ServerMessage.Welcome>().single()

    @Test
    fun `unbekanntes Token wird nach dem Namen gefragt`() {
        assertEquals(listOf(Effect.Send(c1, ServerMessage.NeedName)), game.hello(c1, tokenA))
    }

    @Test
    fun `neuer Spieler bekommt Nummer, Namen und Farbe`() {
        val w = welcome(game.join(c1, tokenA, "  Anna  "))
        assertEquals(1, w.id)
        assertEquals("Anna", w.name)
        assertEquals(PlayerColors.all[0], w.color)
        assertEquals(listOf(PlayerView(1, "Anna", PlayerColors.all[0], true)), game.players())
    }

    @Test
    fun `nach Abbruch ist derselbe Spieler wieder da`() {
        val first = welcome(game.join(c1, tokenA, "Anna"))
        game.join(c2, tokenB, "Ben")
        game.disconnected(c1)
        assertFalse(game.players().first { it.id == first.id }.connected)

        val again = welcome(game.hello(c3, tokenA))
        assertEquals(first, again)
        assertTrue(game.players().first { it.id == first.id }.connected)
        assertEquals(2, game.players().size)
    }

    @Test
    fun `zweite Verbindung mit demselben Token löst die alte ab`() {
        game.join(c1, tokenA, "Anna")
        val effects = game.hello(c2, tokenA)
        assertEquals(Effect.Close(c1, ServerMessage.Replaced), effects.first())
        welcome(effects)

        // die alte Verbindung geht danach zu, der Spieler bleibt verbunden
        game.disconnected(c1)
        assertTrue(game.players().single().connected)
    }

    @Test
    fun `join mit bekanntem Token behält den alten Namen`() {
        game.join(c1, tokenA, "Anna")
        game.disconnected(c1)
        assertEquals("Anna", welcome(game.join(c2, tokenA, "Anderer Name")).name)
    }

    @Test
    fun `Namen werden geprüft`() {
        game.join(c1, tokenA, "Anna")
        assertEquals(NameProblem.EMPTY, rejected(game.join(c2, tokenB, "   ")))
        assertEquals(NameProblem.TOO_LONG, rejected(game.join(c2, tokenB, "x".repeat(21))))
        assertEquals(NameProblem.TAKEN, rejected(game.join(c2, tokenB, "anna")))
        assertEquals("Ben Bo", welcome(game.join(c2, tokenB, "Ben \t  Bo")).name)
    }

    @Test
    fun `Emoji zählen als ein Zeichen`() {
        assertEquals("🎉".repeat(20), welcome(game.join(c1, tokenA, "🎉".repeat(20))).name)
    }

    @Test
    fun `mehr als 20 Spieler passen nicht`() {
        repeat(Rules.MAX_PLAYERS) { i ->
            welcome(game.join(ConnectionId(100L + i), "t".repeat(16) + i.toString().padStart(2, '0'), "P$i"))
        }
        assertEquals(listOf(Effect.Close(c1, ServerMessage.Full)), game.join(c1, tokenA, "Zu spät"))
        assertEquals(Rules.MAX_PLAYERS, game.players().map { it.color }.toSet().size)
    }

    @Test
    fun `entfernter Spieler fliegt raus und ist danach unbekannt`() {
        val id = welcome(game.join(c1, tokenA, "Anna")).id
        assertEquals(listOf(Effect.Close(c1, ServerMessage.Removed)), game.remove(id))
        assertTrue(game.players().isEmpty())
        assertEquals(listOf(Effect.Send(c2, ServerMessage.NeedName)), game.hello(c2, tokenA))
    }

    @Test
    fun `Farbe eines entfernten Spielers wird wieder frei`() {
        val id = welcome(game.join(c1, tokenA, "Anna")).id
        game.remove(id)
        assertEquals(PlayerColors.all[0], welcome(game.join(c2, tokenB, "Ben")).color)
    }

    @Test
    fun `kaputtes Token wird abgewiesen`() {
        assertEquals(listOf(Effect.Close(c1, ServerMessage.Invalid)), game.hello(c1, "kurz"))
        assertEquals(listOf(Effect.Close(c1, ServerMessage.Invalid)), game.join(c1, "mit leerzeichen drin!!", "Anna"))
    }

    private fun rejected(effects: List<Effect>): NameProblem =
        ((effects.single() as Effect.Send).message as ServerMessage.NameRejected).reason
}
