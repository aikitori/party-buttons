package de.aikitori.partybuttons.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** „Übernehmen“: Wer sein Token verloren hat, wird wieder zum alten Spieler. */
class MergeTest {
    private val game = Game("ABCDE")
    private val oldToken = "a".repeat(22)
    private val newToken = "n".repeat(22)
    private val annaOld = ConnectionId(1)
    private val annaNew = ConnectionId(2)

    init {
        game.join(annaOld, oldToken, "Anna")
        game.disconnected(annaOld)
        game.join(annaNew, newToken, "Anna 2")
    }

    @Test
    fun `neuer Spieler wird zum alten, mit dessen Namen`() {
        val effects = game.merge(oldId = 1, newId = 2)
        assertEquals(
            Effect.Send(annaNew, ServerMessage.Welcome(1, "Anna", PlayerColors.all[0])),
            effects.first(),
        )
        assertEquals(listOf(PlayerView(1, "Anna", PlayerColors.all[0], true)), game.players())
    }

    @Test
    fun `danach gilt das neue Token`() {
        game.merge(1, 2)
        game.disconnected(annaNew)
        val effects = game.hello(ConnectionId(3), newToken)
        assertEquals(1, effects.filterIsInstance<Effect.Send>().map { it.message }.filterIsInstance<ServerMessage.Welcome>().single().id)
        // das alte Token ist vergessen
        assertEquals(listOf(Effect.Send(ConnectionId(4), ServerMessage.NeedName)), game.hello(ConnectionId(4), oldToken))
    }

    @Test
    fun `Druck des Neuen in dieser Runde geht mit`() {
        game.openRound(0)
        game.press(annaNew, 1, 500)
        game.merge(1, 2)
        assertEquals(listOf("Anna"), game.round().presses.map { it.name })
    }

    @Test
    fun `nur mit getrenntem altem Spieler`() {
        game.hello(ConnectionId(5), oldToken) // alte Anna wieder da
        assertTrue(game.merge(1, 2).isEmpty())
        assertEquals(2, game.players().size)
        assertTrue(game.merge(1, 1).isEmpty())
        assertTrue(game.merge(1, 99).isEmpty())
    }
}
