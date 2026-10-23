package de.aikitori.partybuttons.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Nachrichten zwischen Browser und Host über den WebSocket, als JSON mit Feld "type".
 * Keine fertigen Texte: Die Seite übersetzt Zustände selbst (Deutsch/Englisch).
 */
@Serializable
sealed interface ClientMessage {
    /** Erste Nachricht nach dem Verbinden. Kennt der Host das Token, kommt [ServerMessage.Welcome], sonst [ServerMessage.NeedName]. */
    @Serializable
    @SerialName("hello")
    data class Hello(val token: String) : ClientMessage

    /** Neuer Spieler mit Namen, nach [ServerMessage.NeedName]. */
    @Serializable
    @SerialName("join")
    data class Join(val token: String, val name: String) : ClientMessage

    /** Buzzer gedrückt in Runde [round]. Drücke für eine alte Runde (verspätet angekommen) zählen nicht. */
    @Serializable
    @SerialName("press")
    data class Press(val round: Int) : ClientMessage

    /** Antwort [option] (0 = A) in einer Runde „Antworten“. Nur eine pro Runde, danach steht sie fest. */
    @Serializable
    @SerialName("answer")
    data class Answer(val round: Int, val option: Int) : ClientMessage

    /** Antwort auf [ServerMessage.Ping], sofort und mit demselben [t]. */
    @Serializable
    @SerialName("pong")
    data class Pong(val t: Long) : ClientMessage
}

@Serializable
sealed interface ServerMessage {
    /** Angemeldet, neu oder wiedererkannt. */
    @Serializable
    @SerialName("welcome")
    data class Welcome(val id: Int, val name: String, val color: String) : ServerMessage

    /**
     * Stand der Runde für diesen Spieler; kommt nach [Welcome] und bei jeder Änderung.
     * Bei [Mode.CHOICE]: [myAnswer] ist die eigene Antwort; nach dem Auflösen ([Phase.LOCKED]) kommen [correct]
     * (null = ohne richtige Antwort, z. B. Umfrage), [counts] (Antworten pro Knopf) und [myRank]
     * (Platz unter den Richtigen, nach Zeit).
     */
    @Serializable
    @SerialName("state")
    data class State(
        val round: Int,
        val phase: Phase,
        val myRank: Int?,
        val mode: Mode = Mode.BUZZER,
        val options: Int = 0,
        val myAnswer: Int? = null,
        val correct: Int? = null,
        val counts: List<Int>? = null,
        /** Punktestand dieses Spielers. */
        val score: Int = 0,
    ) : ServerMessage

    /** Laufzeitmessung: Der Browser schickt [t] als [ClientMessage.Pong] zurück. */
    @Serializable
    @SerialName("ping")
    data class Ping(val t: Long) : ServerMessage

    /** Token unbekannt, die Seite fragt nach dem Namen. */
    @Serializable
    @SerialName("needName")
    data object NeedName : ServerMessage

    /** Name leer, zu lang oder schon vergeben. */
    @Serializable
    @SerialName("nameRejected")
    data class NameRejected(val reason: NameProblem) : ServerMessage

    /** Schon [Rules.MAX_PLAYERS] Spieler im Spiel. */
    @Serializable
    @SerialName("full")
    data object Full : ServerMessage

    /** Raumcode passt nicht, z. B. alter QR-Code von einem früheren Spiel. */
    @Serializable
    @SerialName("wrongRoom")
    data object WrongRoom : ServerMessage

    /** Dasselbe Token hat sich anderswo neu verbunden (zweiter Tab). */
    @Serializable
    @SerialName("replaced")
    data object Replaced : ServerMessage

    /** Nachricht ergibt keinen Sinn, z. B. kaputtes Token. Die Seite legt ein neues Token an. */
    @Serializable
    @SerialName("invalid")
    data object Invalid : ServerMessage

    /** Der Host hat den Spieler entfernt. */
    @Serializable
    @SerialName("removed")
    data object Removed : ServerMessage
}

/** Rundenart, siehe PLAN.md. */
@Serializable
enum class Mode {
    @SerialName("buzzer") BUZZER,
    @SerialName("choice") CHOICE,
}

/** Zustand der Runde. */
@Serializable
enum class Phase {
    /** Noch keine Runde, Buzzer aus. */
    @SerialName("idle") IDLE,

    /** Buzzer frei. */
    @SerialName("open") OPEN,

    /** Runde vorbei, Ergebnis steht (bei Antworten: aufgelöst). */
    @SerialName("locked") LOCKED,
}

@Serializable
enum class NameProblem {
    @SerialName("empty") EMPTY,
    @SerialName("tooLong") TOO_LONG,
    @SerialName("taken") TAKEN,
}

object Protocol {
    val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: ServerMessage): String = json.encodeToString(ServerMessage.serializer(), message)

    /** null bei kaputtem JSON oder unbekanntem Typ; der Host ignoriert solche Nachrichten. */
    fun decodeClient(text: String): ClientMessage? =
        runCatching { json.decodeFromString(ClientMessage.serializer(), text) }.getOrNull()
}
