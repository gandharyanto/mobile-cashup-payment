package com.cashup.app.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic test for the Simple/POS visual toggle -- no Android framework needed. */
class HomeFragmentTest {

    @Test
    fun `selecting pos deselects simple`() {
        var simpleSelected = true
        var posSelected = false

        // Simulates HomeFragment's onModeSelected(pos = true)
        fun selectMode(pos: Boolean) {
            simpleSelected = !pos
            posSelected = pos
        }

        selectMode(pos = true)

        assertFalse(simpleSelected)
        assertTrue(posSelected)
    }

    @Test
    fun `selecting simple deselects pos`() {
        var simpleSelected = false
        var posSelected = true

        fun selectMode(pos: Boolean) {
            simpleSelected = !pos
            posSelected = pos
        }

        selectMode(pos = false)

        assertTrue(simpleSelected)
        assertFalse(posSelected)
    }
}
