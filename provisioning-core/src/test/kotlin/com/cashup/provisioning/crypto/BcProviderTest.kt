package com.cashup.provisioning.crypto

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.KeyStore

class BcProviderTest {

    @Test
    fun `BKS keystore stays available after the BC provider is replaced`() {
        BcProvider.ensureInstalled()

        assertNotNull(KeyStore.getInstance("BKS"))
    }
}
