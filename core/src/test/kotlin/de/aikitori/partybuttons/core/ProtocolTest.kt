package de.aikitori.partybuttons.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Das JSON muss genau so aussehen, wie es web/src/protocol.ts erwartet. */
class ProtocolTest {
    @Test
    fun `Nachrichten an den Browser`() {
        assertEquals("""{"type":"welcome","id":3,"name":"Anna","color":"#e6194b"}""",
            Protocol.encode(ServerMessage.Welcome(3, "Anna", "#e6194b")))
        assertEquals("""{"type":"needName"}""", Protocol.encode(ServerMessage.NeedName))
        assertEquals("""{"type":"nameRejected","reason":"tooLong"}""",
            Protocol.encode(ServerMessage.NameRejected(NameProblem.TOO_LONG)))
    }

    @Test
    fun `Nachrichten vom Browser`() {
        assertEquals(ClientMessage.Hello("x".repeat(22)), Protocol.decodeClient("""{"type":"hello","token":"${"x".repeat(22)}"}"""))
        assertEquals(ClientMessage.Join("t", "Anna"), Protocol.decodeClient("""{"type":"join","token":"t","name":"Anna","extra":1}"""))
    }

    @Test
    fun `Unsinn wird ignoriert`() {
        assertNull(Protocol.decodeClient("kein json"))
        assertNull(Protocol.decodeClient("""{"type":"gibtsnicht"}"""))
        assertNull(Protocol.decodeClient("""{"type":"hello"}"""))
    }

    @Test
    fun `Raumcode ohne verwechselbare Zeichen`() {
        repeat(200) {
            val code = RoomCode.generate()
            assertEquals(5, code.length)
            assert(code.none { it in "01OIL" }) { code }
        }
    }
}
