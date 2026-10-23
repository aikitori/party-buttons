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
        assertEquals("""{"type":"ping","t":42}""", Protocol.encode(ServerMessage.Ping(42)))
        assertEquals("""{"type":"nameRejected","reason":"tooLong"}""",
            Protocol.encode(ServerMessage.NameRejected(NameProblem.TOO_LONG)))
        assertEquals(
            """{"type":"state","round":2,"phase":"locked","myRank":null,"mode":"buzzer","options":0,"myAnswer":null,"correct":null,"counts":null,"score":0,"teams":0,"myTeam":null,"teamScores":null,"buzzes":[]}""",
            Protocol.encode(ServerMessage.State(2, Phase.LOCKED, null)),
        )
        assertEquals(
            """{"type":"state","round":3,"phase":"locked","myRank":1,"mode":"choice","options":3,"myAnswer":2,"correct":2,"counts":[0,1,4],"score":0,"teams":0,"myTeam":null,"teamScores":null,"buzzes":[]}""",
            Protocol.encode(ServerMessage.State(3, Phase.LOCKED, 1, Mode.CHOICE, 3, 2, 2, listOf(0, 1, 4))),
        )
    }

    @Test
    fun `Nachrichten vom Browser`() {
        assertEquals(ClientMessage.Hello("x".repeat(22)), Protocol.decodeClient("""{"type":"hello","token":"${"x".repeat(22)}"}"""))
        assertEquals(ClientMessage.Answer(4, 2), Protocol.decodeClient("""{"type":"answer","round":4,"option":2}"""))
        assertEquals(ClientMessage.Pong(123456789L), Protocol.decodeClient("""{"type":"pong","t":123456789}"""))
        assertEquals(ClientMessage.Press(4), Protocol.decodeClient("""{"type":"press","round":4}"""))
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
