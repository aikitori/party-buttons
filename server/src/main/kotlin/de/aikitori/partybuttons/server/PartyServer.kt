package de.aikitori.partybuttons.server

import de.aikitori.partybuttons.core.ClientMessage
import de.aikitori.partybuttons.core.ConnectionId
import de.aikitori.partybuttons.core.Effect
import de.aikitori.partybuttons.core.Game
import de.aikitori.partybuttons.core.Mode
import de.aikitori.partybuttons.core.PlayerView
import de.aikitori.partybuttons.core.Protocol
import de.aikitori.partybuttons.core.RoomCode
import de.aikitori.partybuttons.core.RoundView
import de.aikitori.partybuttons.core.Rules
import de.aikitori.partybuttons.core.ServerMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.BindException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Alles, was die Host-Oberfläche zeigt. */
data class HostSnapshot(val players: List<PlayerView>, val round: RoundView)

/**
 * Ein laufendes Spiel: [Game] plus die offenen WebSocket-Verbindungen.
 * Alle Aufrufe ans Spiel laufen nacheinander unter [lock]; verschickt wird danach.
 */
class PartyRoom(val roomCode: String = RoomCode.generate()) {
    private val game = Game(roomCode)
    private val lock = Mutex()
    private val sessions = ConcurrentHashMap<ConnectionId, DefaultWebSocketServerSession>()
    private val nextConnection = AtomicLong(1)

    private val _snapshot = MutableStateFlow(HostSnapshot(game.players(), game.round()))

    /** Für die Host-Oberfläche: alle Spieler (auch getrennte) und die Runde. */
    val snapshot: StateFlow<HostSnapshot> = _snapshot.asStateFlow()

    suspend fun removePlayer(id: Int) = update { game.remove(id) }

    suspend fun openRound() = update { game.openRound(now()) }

    suspend fun setMode(mode: Mode) = update { game.setMode(mode) }

    suspend fun setOptions(count: Int) = update { game.setOptions(count) }

    /** Runde „Antworten“ auflösen; null = ohne richtige Antwort. */
    suspend fun reveal(correct: Int?) = update { game.reveal(correct) }

    suspend fun lockRound() = update { game.lockRound(now()) }

    suspend fun setLockOnFirst(value: Boolean) = update { game.setLockOnFirst(value) }

    // monotone Uhr: springt nicht, wenn das Handy die Uhrzeit stellt
    private fun now(): Long = System.nanoTime() / 1_000_000

    internal suspend fun handle(session: DefaultWebSocketServerSession) {
        val conn = ConnectionId(nextConnection.getAndIncrement())
        sessions[conn] = session
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                when (val message = Protocol.decodeClient(frame.readText())) {
                    is ClientMessage.Hello -> update { game.hello(conn, message.token) }
                    is ClientMessage.Join -> update { game.join(conn, message.token, message.name) }
                    is ClientMessage.Press -> {
                        // Zeit sofort beim Empfang nehmen, nicht erst, wenn das Lock frei ist
                        val at = now()
                        update { game.press(conn, message.round, at) }
                    }
                    is ClientMessage.Answer -> {
                        val at = now()
                        update { game.answer(conn, message.round, message.option, at) }
                    }
                    null -> Unit
                }
            }
        } finally {
            sessions.remove(conn)
            // auch wenn die Verbindung abgebrochen (gecancelt) wurde, muss das Spiel davon erfahren
            withContext(NonCancellable) { update { game.disconnected(conn) } }
        }
    }

    private suspend fun update(change: () -> List<Effect>) {
        val effects = lock.withLock {
            val effects = change()
            _snapshot.value = HostSnapshot(game.players(), game.round())
            effects
        }
        for (effect in effects) {
            when (effect) {
                is Effect.Send -> send(effect.to, effect.message)
                is Effect.Close -> {
                    send(effect.to, effect.message)
                    sessions.remove(effect.to)?.close(CloseReason(CloseReason.Codes.NORMAL, ""))
                }
            }
        }
    }

    private suspend fun send(to: ConnectionId, message: ServerMessage) {
        // Verbindung kann inzwischen weg sein, dann fällt die Nachricht weg
        runCatching { sessions[to]?.send(Frame.Text(Protocol.encode(message))) }
    }
}

/** Routen: Seite unter /j/<Raumcode>, Dateien unter /assets/, WebSocket unter /ws/<Raumcode>. */
fun Application.partyButtons(room: PartyRoom, files: StaticFiles) {
    install(WebSockets) {
        // tote Verbindungen (WLAN weg, Bildschirm aus) nach spätestens ~15 s erkennen
        pingPeriod = 5.seconds
        timeout = 10.seconds
    }
    routing {
        get("/") { call.respondRedirect("/j/${room.roomCode}") }
        // auch bei falschem Code die Seite ausliefern; sie erfährt über den WebSocket, dass das Spiel nicht passt
        get("/j/{code}") {
            val html = files.read("index.html") ?: return@get call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
            call.respondBytes(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
        }
        get("/assets/{path...}") {
            val path = "assets/" + call.parameters.getAll("path").orEmpty().joinToString("/")
            val bytes = files.read(path) ?: return@get call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
            call.respondBytes(bytes, contentTypeFor(path))
        }
        webSocket("/ws/{code}") {
            if (call.parameters["code"]?.uppercase() != room.roomCode) {
                send(Frame.Text(Protocol.encode(ServerMessage.WrongRoom)))
                close(CloseReason(CloseReason.Codes.NORMAL, ""))
                return@webSocket
            }
            room.handle(this)
        }
    }
}

private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.').lowercase()) {
    "js" -> ContentType.Application.JavaScript
    "css" -> ContentType.Text.CSS
    "html" -> ContentType.Text.Html
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    "json" -> ContentType.Application.Json
    "woff2" -> ContentType("font", "woff2")
    else -> ContentType.Application.OctetStream
}

/** Startet den Server auf [Rules.DEFAULT_PORT], ist der belegt, auf einem der nächsten Ports. */
class PartyServer(private val files: StaticFiles, val room: PartyRoom = PartyRoom()) {
    private var engine: EmbeddedServer<*, *>? = null

    var port: Int = 0
        private set

    fun start(): Int {
        check(engine == null) { "läuft schon" }
        var lastError: Exception? = null
        for (candidate in Rules.DEFAULT_PORT until Rules.DEFAULT_PORT + 10) {
            val server = embeddedServer(CIO, port = candidate, host = "0.0.0.0") { partyButtons(room, files) }
            try {
                server.start(wait = false)
                engine = server
                port = candidate
                return candidate
            } catch (e: Exception) {
                if (e !is BindException && e.cause !is BindException) throw e
                lastError = e
                runCatching { server.stop(0, 0) }
            }
        }
        throw IllegalStateException("kein freier Port ab ${Rules.DEFAULT_PORT}", lastError)
    }

    fun stop() {
        engine?.stop(500, 1500)
        engine = null
    }
}

/** Zum Ausprobieren auf dem PC: ./gradlew :server:run, dann http://localhost:8080 im Browser. */
fun main(args: Array<String>): Unit = runBlocking {
    val dir = java.io.File(args.firstOrNull() ?: "app/src/main/assets/web")
    require(dir.resolve("index.html").isFile) { "$dir/index.html fehlt, erst in web/ npm run build" }
    val server = PartyServer(DirectoryStaticFiles(dir))
    val port = server.start()
    println("Party Buttons: http://localhost:$port/j/${server.room.roomCode}")
    println("Befehle: o = Runde starten, l = sperren, f = Sperre nach erstem Druck an/aus,")
    println("         m = Buzzer/Antworten umschalten, n4 = vier Knöpfe, rB = auflösen mit B richtig, r- = ohne richtige")
    launch(kotlinx.coroutines.Dispatchers.IO) {
        while (true) {
            val line = readlnOrNull()?.trim() ?: break
            val round = server.room.snapshot.value.round
            when {
                line == "o" -> server.room.openRound()
                line == "l" -> server.room.lockRound()
                line == "f" -> server.room.setLockOnFirst(!round.lockOnFirst)
                line == "m" -> server.room.setMode(if (round.nextMode == Mode.BUZZER) Mode.CHOICE else Mode.BUZZER)
                line.startsWith("n") -> line.drop(1).toIntOrNull()?.let { server.room.setOptions(it) }
                line == "r-" -> server.room.reveal(null)
                line.startsWith("r") && line.length == 2 -> server.room.reveal(line[1].uppercaseChar() - 'A')
            }
        }
    }
    server.room.snapshot.collect { (players, round) ->
        println("Spieler: " + players.joinToString { "${it.id} ${it.name}" + if (it.connected) "" else " (getrennt)" })
        println("Runde ${round.number} ${round.mode} ${round.phase} (nächste: ${round.nextMode}, ${round.nextOptions} Knöpfe; " +
            "Sperre nach erstem: ${round.lockOnFirst}): " +
            round.presses.joinToString { "${it.rank}. ${it.name} +${it.offsetMs} ms" } +
            round.answers.joinToString { "${it.name}=${Rules.optionLabel(it.option)}" + (it.correct?.let { c -> if (c) " ✓" else " ✗" } ?: "") })
    }
}
