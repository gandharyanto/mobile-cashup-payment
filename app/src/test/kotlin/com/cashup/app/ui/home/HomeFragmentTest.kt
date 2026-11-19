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

    @Test
    fun `mode selector shows only on the transaction destination`() {
        assertEquals(true, showsModeSelector(com.cashup.app.R.id.nav_home))
        assertEquals(false, showsModeSelector(com.cashup.app.R.id.nav_history))
        assertEquals(false, showsModeSelector(com.cashup.app.R.id.nav_features))
        assertEquals(false, showsModeSelector(com.cashup.app.R.id.nav_merchant_info))
        assertEquals(false, showsModeSelector(com.cashup.app.R.id.nav_qris))
    }
}
