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

/**
 * Spieler eines Spiels. Ein Spieler gehört zu einem Token aus dem Browserspeicher; meldet sich dasselbe Token
 * nach einem Abbruch wieder, ist es derselbe Spieler. Getrennte Spieler bleiben, bis der Host sie entfernt.
 *
 * Nicht threadsicher: Der Server ruft alles nacheinander auf.
 */
class Game(val roomCode: String) {
    private class Player(val id: Int, val token: String, val name: String, val color: String) {
        var connection: ConnectionId? = null
    }

    private val players = LinkedHashMap<String, Player>()
    private val byConnection = HashMap<ConnectionId, Player>()
    private var nextId = 1

    fun players(): List<PlayerView> =
        players.values.map { PlayerView(it.id, it.name, it.color, it.connection != null) }

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
        val conn = player.connection ?: return emptyList()
        byConnection.remove(conn)
        return listOf(Effect.Close(conn, ServerMessage.Removed))
    }

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
