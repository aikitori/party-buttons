package de.aikitori.partybuttons.server

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

    private suspend fun DefaultClientWebSocketSession.receiveType(): String {
        val frame = withTimeout(5.seconds) { incoming.receive() } as Frame.Text
        return Json.parseToJsonElement(frame.readText()).jsonObject["type"]!!.jsonPrimitive.content
    }

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
        }
        // Verbindung zu: Spieler bleibt, aber getrennt
        val offline = withTimeout(5.seconds) { room.players.first { it.isNotEmpty() && !it.single().connected } }
        assertEquals("Anna", offline.single().name)

        client.webSocket("/ws/abcde") { // Raumcode ohne Rücksicht auf Groß/klein
            sendJson("""{"type":"hello","token":"$token"}""")
            val frame = withTimeout(5.seconds) { incoming.receive() } as Frame.Text
            assertEquals(Protocol.encode(ServerMessage.Welcome(1, "Anna", offline.single().color)), frame.readText())
            assertTrue(withTimeout(5.seconds) { room.players.first { it.single().connected } }.single().connected)
        }
    }

    @Test
    fun `zweiter Tab löst den ersten ab`() = test { client ->
        client.webSocket("/ws/ABCDE") {
            sendJson("""{"type":"join","token":"$token","name":"Anna"}""")
            assertEquals("welcome", receiveType())
            client.webSocket("/ws/ABCDE") {
                sendJson("""{"type":"hello","token":"$token"}""")
                assertEquals("welcome", receiveType())
            }
            assertEquals("replaced", receiveType())
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
            room.removePlayer(1)
            assertEquals("removed", receiveType())
        }
        assertTrue(room.players.value.isEmpty())
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
