package de.aikitori.partybuttons.core

/** Feste Grenzen des Spiels, siehe PLAN.md. */
object Rules {
    /** So viele Mitspielende muss ein Spiel mindestens tragen. */
    const val MAX_PLAYERS = 20

    /** Längster erlaubter Spielername (in Zeichen). */
    const val MAX_NAME_LENGTH = 20

    /** Anzahl der Knöpfe in der Rundenart „Antworten“. */
    val ANSWER_OPTIONS = 2..8
    const val DEFAULT_ANSWER_OPTIONS = 4

    /**
     * So lange nach dem Sperren zählen Drücke noch für die Reihenfolge: Sie waren schon unterwegs, als gesperrt
     * wurde, oder der Browser wusste noch nichts davon.
     */
    const val LATE_PRESS_WINDOW_MS = 500L

    /** Fester Port des Webservers, damit der Browserspeicher der Mitspielenden gleich bleibt. */
    const val DEFAULT_PORT = 8080

    /** Beschriftung des Antwort-Knopfs [index] (0 = A). */
    fun optionLabel(index: Int): String {
        require(index in 0 until ANSWER_OPTIONS.last) { "Knopf $index gibt es nicht" }
        return ('A' + index).toString()
    }
}
