package com.cashup.provisioning.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class RsaKeyLocationTest {

    @Test
    fun `MGF1-SHA1 uses the Keystore on every supported API level`() {
        // AndroidKeyStore memakai MGF1-SHA1 secara default, jadi tidak ada yang
        // perlu dikonfigurasi -- jalur TEE terbuka sampai ke minSdk.
        listOf(23, 24, 28, 30, 33).forEach { api ->
            assertEquals(
                "API $api",
                RsaKeyLocation.ANDROID_KEYSTORE,
                RsaKeyLocationPolicy.choose(Mgf1Digest.SHA1, api),
            )
        }
    }

    @Test
    fun `MGF1-SHA256 falls back to software below API 35`() {
        // setMgf1Digests baru ada di API 35. Di bawah itu digest MGF1 di
        // AndroidKeyStore terkunci SHA-1 dan tidak bisa diubah, jadi key TEE
        // tidak akan pernah bisa membuka paket yang dibungkus MGF1-SHA256.
        listOf(23, 24, 28, 30, 33, 34).forEach { api ->
            assertEquals(
                "API $api",
                RsaKeyLocation.SOFTWARE,
                RsaKeyLocationPolicy.choose(Mgf1Digest.SHA256, api),
            )
        }
    }

    @Test
    fun `MGF1-SHA256 uses the Keystore from API 35 upward`() {
        listOf(35, 36).forEach { api ->
            assertEquals(
                "API $api",
                RsaKeyLocation.ANDROID_KEYSTORE,
                RsaKeyLocationPolicy.choose(Mgf1Digest.SHA256, api),
            )
        }
    }
}