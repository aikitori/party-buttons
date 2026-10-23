package de.aikitori.partybuttons.core

/** Verbindung eines Browsers, vergeben vom Server. */
@JvmInline
value class ConnectionId(val value: Long)

/** Was der Server nach einem Ereignis tun soll. Das Spiel selbst schickt nichts, damit es ohne Netz testbar ist. */
sealed interface Effect {
    data class Send(val to: ConnectionId, val message: ServerMessage) : Effect

    /** Nachricht schicken, dann Verbindung schließen. */
    data class Close(val to: ConnectionId, val message: ServerMessage) : Effect
}

/** So sieht der Host einen Spieler. */
data class PlayerView(val id: Int, val name: String, val color: String, val connected: Boolean)

/** Ein Druck in der Reihenfolge; [offsetMs] ist der Abstand zum ersten Druck. */
data class PressView(val rank: Int, val playerId: Int, val name: String, val color: String, val offsetMs: Long)

/** So sieht der Host die Runde. */
data class RoundView(val number: Int, val phase: Phase, val lockOnFirst: Boolean, val presses: List<PressView>)

/**
 * Spieler und Runden eines Spiels. Ein Spieler gehört zu einem Token aus dem Browserspeicher; meldet sich dasselbe
 * Token nach einem Abbruch wieder, ist es derselbe Spieler. Getrennte Spieler bleiben, bis der Host sie entfernt.
 *
 * Zeiten ([nowMs]) kommen vom Server aus einer monotonen Uhr, damit das Spiel ohne echte Zeit testbar ist.
 * Nicht threadsicher: Der Server ruft alles nacheinander auf.
 */
class Game(val roomCode: String) {
    private class Player(val id: Int, val token: String, val name: String, val color: String) {
        var connection: ConnectionId? = null
    }

    private class Press(val player: Player, val atMs: Long)

    private val players = LinkedHashMap<String, Player>()
    private val byConnection = HashMap<ConnectionId, Player>()
    private var nextId = 1

    private var round = 0
    private var phase = Phase.IDLE
    private var lockedAtMs = 0L
    private val presses = mutableListOf<Press>()

    /** Nach dem ersten Druck sofort sperren (Quiz: wer zuerst drückt, antwortet). Sonst sperrt der Host. */
    var lockOnFirst = true
        private set

    fun players(): List<PlayerView> =
        players.values.map { PlayerView(it.id, it.name, it.color, it.connection != null) }

    fun round(): RoundView {
        val first = presses.firstOrNull()?.atMs ?: 0
        return RoundView(
            round, phase, lockOnFirst,
            presses.mapIndexed { i, p -> PressView(i + 1, p.player.id, p.player.name, p.player.color, p.atMs - first) },
        )
    }

    fun hello(conn: ConnectionId, token: String): List<Effect> {
        if (!isValidToken(token)) return listOf(Effect.Close(conn, ServerMessage.Invalid))
        val player = players[token] ?: return listOf(Effect.Send(conn, ServerMessage.NeedName))
        return attach(conn, player)
    }

    fun join(conn: ConnectionId, token: String, rawName: String): List<Effect> {
        if (!isValidToken(token)) return listOf(Effect.Close(conn, ServerMessage.Invalid))
        // Token schon bekannt (z. B. join doppelt geschickt): alter Spieler, Name bleibt
        players[token]?.let { return attach(conn, it) }
        if (players.size >= Rules.MAX_PLAYERS) return listOf(Effect.Close(conn, ServerMessage.Full))

        val name = normalizeName(rawName)
        val problem = when {
            name.isEmpty() -> NameProblem.EMPTY
            name.codePointCount(0, name.length) > Rules.MAX_NAME_LENGTH -> NameProblem.TOO_LONG
            players.values.any { it.name.equals(name, ignoreCase = true) } -> NameProblem.TAKEN
            else -> null
        }
        if (problem != null) return listOf(Effect.Send(conn, ServerMessage.NameRejected(problem)))

        val player = Player(nextId++, token, name, freeColor())
        players[token] = player
        return attach(conn, player)
    }

    /** Verbindung ist weg. Der Spieler bleibt, nur als getrennt. */
    fun disconnected(conn: ConnectionId): List<Effect> {
        val player = byConnection.remove(conn) ?: return emptyList()
        // nur wenn das noch seine aktuelle Verbindung war, nicht nach replaced
        if (player.connection == conn) player.connection = null
        return emptyList()
    }

    /** Host entfernt einen Spieler; er ist danach ein Unbekannter und müsste sich neu anmelden. */
    fun remove(playerId: Int): List<Effect> {
        val player = players.values.firstOrNull { it.id == playerId } ?: return emptyList()
        players.remove(player.token)
        val hadPressed = presses.removeAll { it.player === player }
        val effects = mutableListOf<Effect>()
        player.connection?.let { conn ->
            byConnection.remove(conn)
            effects += Effect.Close(conn, ServerMessage.Removed)
        }
        // die anderen rücken in der Reihenfolge auf
        if (hadPressed) effects += broadcastState()
        return effects
    }

    /** Host gibt die Buzzer frei: neue Runde, alte Drücke weg. */
    fun openRound(): List<Effect> {
        round++
        phase = Phase.OPEN
        presses.clear()
        return broadcastState()
    }

    /** Host sperrt die Buzzer; Drücke, die schon unterwegs waren, zählen noch kurz (siehe [Rules.LATE_PRESS_WINDOW_MS]). */
    fun lockRound(nowMs: Long): List<Effect> {
        if (phase != Phase.OPEN) return emptyList()
        lock(nowMs)
        return broadcastState()
    }

    fun setLockOnFirst(value: Boolean): List<Effect> {
        lockOnFirst = value
        return emptyList()
    }

    fun press(conn: ConnectionId, pressedRound: Int, nowMs: Long): List<Effect> {
        val player = byConnection[conn] ?: return emptyList()
        if (pressedRound != round || presses.any { it.player === player }) return emptyList()
        val accepted = when (phase) {
            Phase.OPEN -> true
            Phase.LOCKED -> nowMs - lockedAtMs <= Rules.LATE_PRESS_WINDOW_MS
            Phase.IDLE -> false
        }
        if (!accepted) return emptyList()
        presses += Press(player, nowMs)
        if (phase == Phase.OPEN && lockOnFirst) lock(nowMs)
        return broadcastState()
    }

    private fun lock(nowMs: Long) {
        phase = Phase.LOCKED
        lockedAtMs = nowMs
    }

    private fun stateFor(player: Player): ServerMessage.State {
        val rank = presses.indexOfFirst { it.player === player }.takeIf { it >= 0 }?.plus(1)
        return ServerMessage.State(round, phase, rank)
    }

    private fun broadcastState(): List<Effect> =
        players.values.mapNotNull { player -> player.connection?.let { Effect.Send(it, stateFor(player)) } }

    private fun attach(conn: ConnectionId, player: Player): List<Effect> {
        val effects = mutableListOf<Effect>()
        val old = player.connection
        if (old != null && old != conn) {
            byConnection.remove(old)
            effects += Effect.Close(old, ServerMessage.Replaced)
        }
        player.connection = conn
        byConnection[conn] = player
        effects += Effect.Send(conn, ServerMessage.Welcome(player.id, player.name, player.color))
        effects += Effect.Send(conn, stateFor(player))
        return effects
    }

    private fun freeColor(): String {
        val used = players.values.map { it.color }.toSet()
        return PlayerColors.all.firstOrNull { it !in used } ?: PlayerColors.all[players.size % PlayerColors.all.size]
    }

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9_-]{16,64}")

        fun isValidToken(token: String) = TOKEN.matches(token)

        /** Leerzeichen am Rand weg, mehrere Leerzeichen und Steuerzeichen zu einem. */
        fun normalizeName(raw: String): String =
            raw.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
    }
}

/** 20 gut unterscheidbare Farben, eine pro Spieler. */
object PlayerColors {
    val all = listOf(
        "#e6194b", "#3cb44b", "#ffe119", "#4363d8", "#f58231",
        "#911eb4", "#42d4f4", "#f032e6", "#bfef45", "#fabed4",
        "#469990", "#dcbeff", "#9a6324", "#fffac8", "#800000",
        "#aaffc3", "#808000", "#ffd8b1", "#000075", "#a9a9a9",
    )
}
