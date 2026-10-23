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

/** So sieht der Host einen Spieler. [latencyMs] ist die gemessene Hin- und Rücklaufzeit, solange verbunden. */
data class PlayerView(
    val id: Int,
    val name: String,
    val color: String,
    val connected: Boolean,
    val latencyMs: Long? = null,
    val score: Int = 0,
)

/**
 * Ein Druck in der Reihenfolge. [atMs] ist der Zeitpunkt auf der Uhr des Servers (monoton, mit Laufzeitausgleich),
 * [sinceOpenMs] die Zeit seit dem Freigeben, [offsetMs] der Abstand zum ersten Druck.
 */
data class PressView(
    val rank: Int,
    val playerId: Int,
    val name: String,
    val color: String,
    val offsetMs: Long,
    val atMs: Long = 0,
    val sinceOpenMs: Long = 0,
)

/**
 * Eine Antwort; [offsetMs] ist die Zeit seit dem Freigeben, [atMs] der Zeitpunkt auf der Uhr des Servers.
 * [correct] ist erst nach dem Auflösen gesetzt.
 */
data class AnswerView(
    val playerId: Int,
    val name: String,
    val color: String,
    val option: Int,
    val offsetMs: Long,
    val correct: Boolean?,
    val atMs: Long = 0,
)

/**
 * So sieht der Host die Runde. [mode] und [options] gelten für die laufende Runde; [nextMode] und [nextOptions]
 * sind die Einstellungen für die nächste. [answers] stehen in der Reihenfolge des Eingangs.
 */
data class RoundView(
    val number: Int,
    val phase: Phase,
    val mode: Mode,
    val options: Int,
    val lockOnFirst: Boolean,
    val nextMode: Mode,
    val nextOptions: Int,
    val presses: List<PressView>,
    val answers: List<AnswerView>,
    val correct: Int?,
) {
    /** Die zwei Schnellsten am Buzzer waren praktisch gleich schnell. */
    val tie: Boolean get() = presses.size >= 2 && presses[1].offsetMs < Rules.TIE_MS

    /** Antworten pro Knopf (Index = Knopf). */
    val counts: List<Int> get() = List(options) { option -> answers.count { it.option == option } }
}

/**
 * Spieler und Runden eines Spiels. Ein Spieler gehört zu einem Token aus dem Browserspeicher; meldet sich dasselbe
 * Token nach einem Abbruch wieder, ist es derselbe Spieler. Getrennte Spieler bleiben, bis der Host sie entfernt.
 *
 * Zeiten ([nowMs]) kommen vom Server aus einer monotonen Uhr, damit das Spiel ohne echte Zeit testbar ist.
 * Nicht threadsicher: Der Server ruft alles nacheinander auf.
 */
class Game(val roomCode: String) {
    private class Player(val id: Int, var token: String, val name: String, val color: String) {
        var connection: ConnectionId? = null

        var score = 0

        /** Gleitender Mittelwert der Laufzeit (hin und zurück), null bis zur ersten Messung. */
        var rttMs: Double? = null

        /** So viel früher hat der Spieler gedrückt, als der Druck ankam (halbe Laufzeit, begrenzt). */
        fun compensationMs(): Long = ((rttMs ?: 0.0) / 2).toLong().coerceIn(0, Rules.MAX_LATENCY_COMPENSATION_MS)
    }

    private class Press(val player: Player, val atMs: Long)

    private class Answer(val player: Player, val option: Int, val atMs: Long)

    private val players = LinkedHashMap<String, Player>()
    private val byConnection = HashMap<ConnectionId, Player>()
    private var nextId = 1

    private var round = 0
    private var phase = Phase.IDLE
    private var mode = Mode.BUZZER
    private var options = 0
    private var openedAtMs = 0L
    private var lockedAtMs = 0L
    private var correct: Int? = null
    private val presses = mutableListOf<Press>()
    private val answers = mutableListOf<Answer>()

    /** Nach dem ersten Druck sofort sperren (Quiz: wer zuerst drückt, antwortet). Sonst sperrt der Host. */
    var lockOnFirst = true
        private set

    // Einstellungen für die nächste Runde; die laufende behält ihre
    private var nextMode = Mode.BUZZER
    private var nextOptions = Rules.DEFAULT_ANSWER_OPTIONS

    fun players(): List<PlayerView> =
        players.values.map {
            val connected = it.connection != null
            PlayerView(it.id, it.name, it.color, connected, if (connected) it.rttMs?.toLong() else null, it.score)
        }

    fun round(): RoundView {
        val first = presses.firstOrNull()?.atMs ?: 0
        val revealed = phase == Phase.LOCKED && mode == Mode.CHOICE
        return RoundView(
            number = round,
            phase = phase,
            mode = mode,
            options = options,
            lockOnFirst = lockOnFirst,
            nextMode = nextMode,
            nextOptions = nextOptions,
            presses = presses.mapIndexed { i, p ->
                PressView(
                    i + 1, p.player.id, p.player.name, p.player.color, p.atMs - first,
                    atMs = p.atMs,
                    sinceOpenMs = (p.atMs - openedAtMs).coerceAtLeast(0),
                )
            },
            answers = answers.map {
                AnswerView(
                    it.player.id, it.player.name, it.player.color, it.option, (it.atMs - openedAtMs).coerceAtLeast(0),
                    correct = if (revealed && correct != null) it.option == correct else null,
                    atMs = it.atMs,
                )
            },
            correct = if (revealed) correct else null,
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

    /** Gemessene Laufzeit einer Verbindung (Ping bis Pong). */
    fun reportRtt(conn: ConnectionId, rttMs: Long): List<Effect> {
        val player = byConnection[conn] ?: return emptyList()
        if (rttMs < 0) return emptyList()
        // Ausreißer (kurzer WLAN-Hänger) sollen den Wert nicht auf einen Schlag verderben
        player.rttMs = player.rttMs?.let { it * 0.7 + rttMs * 0.3 } ?: rttMs.toDouble()
        return emptyList()
    }

    /**
     * Host legt zwei Spieler zusammen: [newId] ist jemand, der sein Token verloren hat (anderes Gerät, privater
     * Modus) und sich neu angemeldet hat; er wird wieder zu [oldId], mit Name, Farbe und Platz. [oldId] muss
     * getrennt sein, sonst gäbe es ihn zweimal.
     */
    fun merge(oldId: Int, newId: Int): List<Effect> {
        if (oldId == newId) return emptyList()
        val old = players.values.firstOrNull { it.id == oldId } ?: return emptyList()
        val new = players.values.firstOrNull { it.id == newId } ?: return emptyList()
        if (old.connection != null) return emptyList()

        players.remove(old.token)
        players.remove(new.token)
        old.token = new.token
        players[old.token] = old

        // was der Neue in dieser Runde getan hat, gilt für den Alten, falls der nichts getan hat
        presses.replaceAll { if (it.player === new && presses.none { p -> p.player === old }) Press(old, it.atMs) else it }
        presses.removeAll { it.player === new }
        answers.replaceAll { if (it.player === new && answers.none { a -> a.player === old }) Answer(old, it.option, it.atMs) else it }
        answers.removeAll { it.player === new }

        // Punkte, die der Neue schon gesammelt hat, gehen nicht verloren
        old.score += new.score
        val conn = new.connection ?: return broadcastState()
        old.connection = conn
        old.rttMs = new.rttMs
        byConnection[conn] = old
        return listOf(Effect.Send(conn, ServerMessage.Welcome(old.id, old.name, old.color))) + broadcastState()
    }

    /** Host ändert den Punktestand eines Spielers um [delta] (Buzzer: +1 für den, der richtig lag; Korrekturen). */
    fun addScore(playerId: Int, delta: Int): List<Effect> {
        val player = players.values.firstOrNull { it.id == playerId } ?: return emptyList()
        player.score += delta
        return player.connection?.let { listOf(Effect.Send(it, stateFor(player))) } ?: emptyList()
    }

    /** Alle Punkte auf 0, z. B. für ein neues Spiel mit denselben Leuten. */
    fun resetScores(): List<Effect> {
        players.values.forEach { it.score = 0 }
        return broadcastState()
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
        val hadPressed = presses.removeAll { it.player === player } or answers.removeAll { it.player === player }
        val effects = mutableListOf<Effect>()
        player.connection?.let { conn ->
            byConnection.remove(conn)
            effects += Effect.Close(conn, ServerMessage.Removed)
        }
        // die anderen rücken in der Reihenfolge auf
        if (hadPressed) effects += broadcastState()
        return effects
    }

    /** Rundenart für die nächste Runde. */
    fun setMode(value: Mode): List<Effect> {
        nextMode = value
        return emptyList()
    }

    /** Anzahl der Antwort-Knöpfe für die nächste Runde, begrenzt auf [Rules.ANSWER_OPTIONS]. */
    fun setOptions(value: Int): List<Effect> {
        nextOptions = value.coerceIn(Rules.ANSWER_OPTIONS)
        return emptyList()
    }

    /** Host startet eine neue Runde mit den aktuellen Einstellungen; alte Drücke und Antworten sind weg. */
    fun openRound(nowMs: Long): List<Effect> {
        round++
        phase = Phase.OPEN
        mode = nextMode
        options = if (mode == Mode.CHOICE) nextOptions else 0
        openedAtMs = nowMs
        correct = null
        presses.clear()
        answers.clear()
        return broadcastState()
    }

    /** Host sperrt die Buzzer; Drücke, die schon unterwegs waren, zählen noch kurz (siehe [Rules.LATE_PRESS_WINDOW_MS]). */
    fun lockRound(nowMs: Long): List<Effect> {
        if (phase != Phase.OPEN || mode != Mode.BUZZER) return emptyList()
        lock(nowMs)
        return broadcastState()
    }

    fun setLockOnFirst(value: Boolean): List<Effect> {
        lockOnFirst = value
        return emptyList()
    }

    /** Host löst eine Runde „Antworten“ auf; [correctOption] null heißt ohne richtige Antwort (Umfrage). */
    fun reveal(correctOption: Int?): List<Effect> {
        if (phase != Phase.OPEN || mode != Mode.CHOICE) return emptyList()
        if (correctOption != null && correctOption !in 0 until options) return emptyList()
        correct = correctOption
        phase = Phase.LOCKED
        // ein Punkt für jede richtige Antwort; der Host kann danach von Hand korrigieren
        if (correctOption != null) {
            answers.filter { it.option == correctOption }.forEach { it.player.score += Rules.POINTS_PER_CORRECT_ANSWER }
        }
        return broadcastState()
    }

    fun answer(conn: ConnectionId, answeredRound: Int, option: Int, nowMs: Long): List<Effect> {
        val player = byConnection[conn] ?: return emptyList()
        // nach dem Auflösen zählt nichts mehr, auch nichts Verspätetes: das Ergebnis steht schon
        if (answeredRound != round || mode != Mode.CHOICE || phase != Phase.OPEN) return emptyList()
        if (option !in 0 until options || answers.any { it.player === player }) return emptyList()
        answers += Answer(player, option, nowMs - player.compensationMs())
        // nur der Spieler selbst erfährt davon; die anderen sehen nichts, damit niemand abschaut
        return listOf(Effect.Send(conn, stateFor(player)))
    }

    fun press(conn: ConnectionId, pressedRound: Int, nowMs: Long): List<Effect> {
        val player = byConnection[conn] ?: return emptyList()
        if (mode != Mode.BUZZER) return emptyList()
        if (pressedRound != round || presses.any { it.player === player }) return emptyList()
        val accepted = when (phase) {
            Phase.OPEN -> true
            Phase.LOCKED -> nowMs - lockedAtMs <= Rules.LATE_PRESS_WINDOW_MS
            Phase.IDLE -> false
        }
        if (!accepted) return emptyList()
        // Zeitpunkt des Drückens: Empfang minus Laufzeit; ein später angekommener Druck kann davor landen
        val at = nowMs - player.compensationMs()
        val index = presses.indexOfFirst { it.atMs > at }.takeIf { it >= 0 } ?: presses.size
        presses.add(index, Press(player, at))
        if (phase == Phase.OPEN && lockOnFirst) lock(nowMs)
        return broadcastState()
    }

    private fun lock(nowMs: Long) {
        phase = Phase.LOCKED
        lockedAtMs = nowMs
    }

    private fun stateFor(player: Player): ServerMessage.State {
        if (mode == Mode.BUZZER) {
            val rank = presses.indexOfFirst { it.player === player }.takeIf { it >= 0 }?.plus(1)
            return ServerMessage.State(round, phase, rank, score = player.score)
        }
        val mine = answers.firstOrNull { it.player === player }
        val revealed = phase == Phase.LOCKED
        // Platz unter den Richtigen, nach Zeit
        val rank = if (revealed && mine != null && correct != null && mine.option == correct) {
            answers.filter { it.option == correct }.sortedBy { it.atMs }.indexOf(mine) + 1
        } else {
            null
        }
        return ServerMessage.State(
            round, phase, rank,
            mode = Mode.CHOICE,
            options = options,
            myAnswer = mine?.option,
            correct = if (revealed) correct else null,
            counts = if (revealed) round().counts else null,
            score = player.score,
        )
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
