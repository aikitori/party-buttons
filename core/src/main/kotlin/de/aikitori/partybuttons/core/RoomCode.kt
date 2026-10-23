package de.aikitori.partybuttons.core

import java.security.SecureRandom

/** Kurzer Code im Link, damit nur mitspielt, wer den QR-Code gesehen hat. Ohne verwechselbare Zeichen (0/O, 1/I/L). */
object RoomCode {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 5
    private val random = SecureRandom()

    fun generate(): String = buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}
