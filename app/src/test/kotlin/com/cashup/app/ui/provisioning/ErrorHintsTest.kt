package com.cashup.app.ui.provisioning

import com.cashup.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorHintsTest {

    @Test
    fun `known backend codes map to their own message`() {
        assertEquals(R.string.err_token_invalid, hintFor("PROVISIONING_TOKEN_INVALID"))
        assertEquals(R.string.err_already_provisioned, hintFor("TERMINAL_KEY_ALREADY_PROVISIONED"))
        assertEquals(R.string.err_not_registered, hintFor("TERMINAL_NOT_REGISTERED"))
    }

    @Test
    fun `local failure codes map too`() {
        assertEquals(R.string.err_device_unknown, hintFor("DEVICE_UNKNOWN"))
        assertEquals(R.string.err_package_invalid, hintFor("PACKAGE_INVALID"))
        assertEquals(R.string.err_key_install_failed, hintFor("KEY_INSTALL_FAILED"))
    }

    @Test
    fun `an unknown code falls back so the backend message still reaches the technician`() {
        // Daftar kode belum final (spec §8 item 8). Memetakan kode tak dikenal
        // ke pesan generik akan menyembunyikan justru keterangan yang berguna.
        assertEquals(0, hintFor("SOMETHING_NEW"))
    }
}
