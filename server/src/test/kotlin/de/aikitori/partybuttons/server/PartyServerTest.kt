package de.aikitori.partybuttons.server

import de.aikitori.partybuttons.core.Mode
import de.aikitori.partybuttons.core.Protocol
import de.aikitori.partybuttons.core.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PartyServerTest {
    private val room = PartyRoom("ABCDE")
    private val files = StaticFiles { path ->
        when (path) {
            "index.html" -> "<html>Party</html>".toByteArray()
            "assets/app.js" -> "console.log(1)".toByteArray()
            else -> null
        }
    }
    private val token = "k".repeat(22)

    private fun test(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { partyButtons(room, files) }
        block(createClient { install(WebSockets) })
    }

    private suspend fun DefaultClientWebSocketSession.receiveType(): String =
        Json.parseToJsonElement(receiveText()).jsonObject["type"]!!.jsonPrimitive.content

    private suspend fun DefaultClientWebSocketSession.sendJson(json: String) = send(Frame.Text(json))

    @Test
    fun `Seite und Dateien`() = test { client ->
        assertEquals("<html>Party</html>", client.get("/j/ABCDE").bodyAsText())
        assertEquals("console.log(1)", client.get("/assets/app.js").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/assets/fehlt.js").status)
    }

    @Test
    fun `anmelden, abbrechen und als derselbe Spieler zurückkommen`() = test { client ->
        client.webSocket("/ws/ABCDE") {
            sendJson("""{"type":"hello","token":"$token"}""")
            assertEquals("needName", receiveType())
            sendJson("""{"type":"join","token":"$token","name":"Anna"}""")
            assertEquals("welcome", receiveType())
            assertEquals("state", receiveType())
        }
        // Verbindung zu: Spieler bleibt, aber getrennt
        val offline = withTimeout(5.seconds) { room.snapshot.first { it.players.isNotEmpty() && !it.players.single().connected }.players }
        assertEquals("Anna", offline.single().name)

        client.webSocket("/ws/abcde") { // Raumcode ohne Rücksicht auf Groß/klein
            sendJson("""{"type":"hello","token":"$token"}""")
            assertEquals(Protocol.encode(ServerMessage.Welcome(1, "Anna", offline.single().color)), receiveText())
            assertEquals("state", receiveType())
            assertTrue(withTimeout(5.seconds) { room.snapshot.first { it.players.single().connected } }.players.single().connected)
        }
    }

    @Test
    fun `zweiter Tab löst den ersten ab`() = test { client ->
        client.webSocket("/ws/ABCDE") {
            sendJson("""{"type":"join","token":"$token","name":"Anna"}""")
            assertEquals("welcome", receiveType())
            assertEquals("state", receiveType())
            client.webSocket("/ws/ABCDE") {
                sendJson("""{"type":"hello","token":"$token"}""")
                assertEquals("welcome", receiveType())
            assertEquals("state", receiveType())
            }
            assertEquals("replaced", receiveType())
        }
    }

    @Test
    fun `Buzzer über den WebSocket`() = test { client ->
        client.webSocket("/ws/ABCDE") {
            sendJson("""{"type":"join","token":"$token","name":"Anna"}""")
            assertEquals("welcome", receiveType())
            assertEquals("state", receiveType())
            room.openRound()
            assertEquals("""{"type":"state","round":1,"phase":"open","myRank":null,"mode":"buzzer","options":0,"myAnswer":null,"correct":null,"counts":null}""", receiveText())
            sendJson("""{"type":"press","round":1}""")
            assertEquals("""{"type":"state","round":1,"phase":"locked","myRank":1,"mode":"buzzer","options":0,"myAnswer":null,"correct":null,"counts":null}""", receiveText())
        }
        assertEquals(listOf("Anna"), room.snapshot.value.round.presses.map { it.name })
    }

    @Test
    fun `Antworten über den WebSocket`() = test { client ->
        client.webSocket("/ws/ABCDE") {
            sendJson("""{"type":"join","token":"$token","name":"Anna"}""")
            assertEquals("welcome", receiveType())
            assertEquals("state", receiveType())
            room.setMode(Mode.CHOICE)
            room.setOptions(3)
            room.openRound()
            assertEquals("""{"type":"state","round":1,"phase":"open","myRank":null,"mode":"choice","options":3,"myAnswer":null,"correct":null,"counts":null}""", receiveText())
            sendJson("""{"type":"answer","round":1,"option":2}""")
            assertEquals("""{"type":"state","round":1,"phase":"open","myRank":null,"mode":"choice","options":3,"myAnswer":2,"correct":null,"counts":null}""", receiveText())
            room.reveal(2)
            assertEquals("""{"type":"state","round":1,"phase":"locked","myRank":1,"mode":"choice","options":3,"myAnswer":2,"correct":2,"counts":[0,0,1]}""", receiveText())
        }
    }

    /** Nächste Nachricht außer Pings (die kommen alle 2 s dazwischen). */
    private suspend fun DefaultClientWebSocketSession.receiveText(): String {
        while (true) {
            val text = (withTimeout(5.seconds) { incoming.receive() } as Frame.Text).readText()
            if (!text.startsWith("""{"type":"ping"""")) return text
        }
    }

    @Test
    fun `Ping und Pong messen die Laufzeit`() = test { client ->
        client.webSocket("/ws/ABCDE") {
            sendJson("""{"type":"join","token":"$token","name":"Anna"}""")
            while (true) {
                val text = (withTimeout(5.seconds) { incoming.receive() } as Frame.Text).readText()
                if (!text.startsWith("""{"type":"ping"""")) continue
                val t = Json.parseToJsonElement(text).jsonObject["t"]!!.jsonPrimitive.content
                sendJson("""{"type":"pong","t":$t}""")
                break
            }
            val measured = withTimeout(5.seconds) { room.snapshot.first { it.players.singleOrNull()?.latencyMs != null } }
            assertTrue(measured.players.single().latencyMs!! in 0..1000)
        }
    }

    @Test
    fun `Übernehmen über den Server`() = test { client ->
        client.webSocket("/ws/ABCDE") {
            sendJson("""{"type":"join","token":"$token","name":"Anna"}""")
            assertEquals("welcome", receiveType())
        }
        withTimeout(5.seconds) { room.snapshot.first { it.players.size == 1 && !it.players.single().connected } }
        client.webSocket("/ws/ABCDE") {
            sendJson("""{"type":"join","token":"${"z".repeat(22)}","name":"Anna neu"}""")
            assertEquals("welcome", receiveType())
            assertEquals("state", receiveType())
            room.mergePlayers(1, 2)
            assertEquals(Protocol.encode(ServerMessage.Welcome(1, "Anna", room.snapshot.value.players.single().color)), receiveText())
        }
    }

    @Test
    fun `falscher Raumcode`() = test { client ->
        client.webSocket("/ws/XXXXX") {
            assertEquals("wrongRoom", receiveType())
        }
    }

    @Test
    fun `Host entfernt Spieler`() = test { client ->
        client.webSocket("/ws/ABCDE") {
            sendJson("""{"type":"join","token":"$token","name":"Anna"}""")
            assertEquals("welcome", receiveType())
            assertEquals("state", receiveType())
            room.removePlayer(1)
            assertEquals("removed", receiveType())
        }
        assertTrue(room.snapshot.value.players.isEmpty())
    }

    @Test
    fun `kein Ausbruch aus dem Ordner`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        dir.resolve("index.html").writeText("ok")
        dir.parentFile.resolve("geheim.txt").writeText("geheim")
        val static = DirectoryStaticFiles(dir)
        assertEquals("ok", static.read("index.html")?.decodeToString())
        assertEquals(null, static.read("../geheim.txt"))
        dir.parentFile.resolve("geheim.txt").delete()
    }
}
