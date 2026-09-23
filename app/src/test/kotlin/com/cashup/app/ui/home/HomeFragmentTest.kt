package com.cashup.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFragmentTest {

    @Test
    fun `selecting pos deselects simple`() {
        val (simpleSelected, posSelected) = modeSelectionState(pos = true)
        assertEquals(false, simpleSelected)
        assertEquals(true, posSelected)
    }

    @Test
    fun `selecting simple deselects pos`() {
        val (simpleSelected, posSelected) = modeSelectionState(pos = false)
        assertEquals(true, simpleSelected)
        assertEquals(false, posSelected)
    }
}
