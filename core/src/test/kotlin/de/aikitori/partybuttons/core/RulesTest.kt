package de.aikitori.partybuttons.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RulesTest {
    @Test
    fun `Knöpfe heißen A bis H`() {
        assertEquals("A", Rules.optionLabel(0))
        assertEquals("H", Rules.optionLabel(7))
        assertThrows<IllegalArgumentException> { Rules.optionLabel(8) }
    }

    @Test
    fun `Standard liegt im erlaubten Bereich`() {
        assert(Rules.DEFAULT_ANSWER_OPTIONS in Rules.ANSWER_OPTIONS)
    }
}
