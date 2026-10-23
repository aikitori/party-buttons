package de.aikitori.partybuttons.core

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamsTest {
    private val game = Game("ABCDE")
    private val conns = (1..5).map { ConnectionId(it.toLong()) }

    private fun join(i: Int) = game.join(conns[i], "t".repeat(20) + i, "P$i")

    private fun lastState(effects: List<Effect>, conn: ConnectionId) =
        effects.filterIsInstance<Effect.Send>().filter { it.to == conn }.map { it.message }
            .filterIsInstance<ServerMessage.State>().last()

    private fun teamsOf() = game.players().map { it.team }

    @Test
    fun `ohne Teams hat niemand ein Team`() {
        join(0)
        assertNull(game.players().single().team)
        assertTrue(game.teams().isEmpty())
        assertEquals(0, lastState(game.openRound(0), conns[0]).teams)
    }

    @Test
    fun `Einschalten verteilt alle gleichmäßig, Neue kommen ins kleinste Team`() {
        repeat(3) { join(it) }
        game.setTeams(2)
        assertEquals(listOf(2, 1), game.teams().map { it.members })
        join(3)
        assertEquals(listOf(2, 2), game.teams().map { it.members })
        join(4)
        assertEquals(5, game.teams().sumOf { it.members })
    }

    @Test
    fun `Anzahl ist begrenzt, weggefallene Teams werden neu verteilt`() {
        repeat(4) { join(it) }
        game.setTeams(1)
        assertEquals(2, game.teamCount)
        game.setTeams(99)
        assertEquals(Rules.MAX_TEAMS, game.teamCount)
        game.setTeams(4)
        game.setTeams(2)
        assertTrue(teamsOf().all { it in 0..1 })
        game.setTeams(0)
        assertTrue(teamsOf().all { it == null })
    }

    @Test
    fun `Mitspieler wählt sein Team, alle erfahren den neuen Stand`() {
        repeat(2) { join(it) }
        game.setTeams(3)
        val effects = game.chooseTeam(conns[0], 2)
        assertEquals(2, game.players().first { it.name == "P0" }.team)
        assertEquals(2, lastState(effects, conns[0]).myTeam)
        assertEquals(3, lastState(effects, conns[1]).teams)
        assertTrue(game.chooseTeam(conns[0], 3).isEmpty(), "Team 4 gibt es nicht")
    }

    @Test
    fun `Host verschiebt Spieler und lost aus`() {
        repeat(4) { join(it) }
        game.setTeams(2)
        game.setPlayerTeam(1, 1)
        assertEquals(1, game.players().first { it.id == 1 }.team)
        game.shuffleTeams(Random(42))
        assertEquals(listOf(2, 2), game.teams().map { it.members })
    }

    @Test
    fun `Teampunkte sind die Summe, alle sehen sie`() {
        repeat(3) { join(it) }
        game.setTeams(2)
        game.setPlayerTeam(1, 0)
        game.setPlayerTeam(2, 0)
        game.setPlayerTeam(3, 1)
        game.addScore(1, 2)
        val effects = game.addScore(2, 1)
        assertEquals(listOf(3, 0), game.teams().map { it.score })
        assertEquals(listOf(3, 0), lastState(effects, conns[2]).teamScores)
    }

    @Test
    fun `Mitspielende sehen, wer gebuzzert hat`() {
        repeat(3) { join(it) }
        game.setLockOnFirst(false)
        game.openRound(0)
        game.press(conns[1], 1, 1000)
        val effects = game.press(conns[0], 1, 1084)
        val buzzes = lastState(effects, conns[2]).buzzes
        assertEquals(listOf("P1" to 0L, "P0" to 84L), buzzes.map { it.name to it.offsetMs })
    }

    @Test
    fun `bei Antworten sieht niemand fremde Antworten`() {
        repeat(2) { join(it) }
        game.setMode(Mode.CHOICE)
        game.openRound(0)
        game.answer(conns[0], 1, 2, 100)
        val forOther = lastState(game.reveal(2), conns[1])
        assertTrue(forOther.buzzes.isEmpty())
        assertNull(forOther.myAnswer)
    }
}
