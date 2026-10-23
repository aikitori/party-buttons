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
}

@Serializable
sealed interface ServerMessage {
    /** Angemeldet, neu oder wiedererkannt. */
    @Serializable
    @SerialName("welcome")
    data class Welcome(val id: Int, val name: String, val color: String) : ServerMessage

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
