package com.cashup.provisioning.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

class SelfSignedCertificateTest {

    private fun rsaKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    @Test
    fun `produces a certificate that verifies against its own public key`() {
        val keyPair = rsaKeyPair()

        val der = SelfSignedCertificate.build(keyPair)

        val cert = parse(der)
        // verify() tidak melempar kalau tanda tangan cocok dengan public key --
        // ini bukti langsung bahwa sertifikat memang ditandatangani oleh
        // private key yang berpasangan dengan public key-nya sendiri.
        cert.verify(keyPair.public)
        assertEquals(keyPair.public, cert.publicKey)
    }

    @Test
    fun `subject matches the requested common name`() {
        val der = SelfSignedCertificate.build(rsaKeyPair(), subjectCn = "test-device-123")

        val cert = parse(der)

        assertTrue(cert.subjectX500Principal.name, cert.subjectX500Principal.name.contains("test-device-123"))
    }

    @Test
    fun `validity window covers now and extends roughly ten years`() {
        val before = Date()

        val der = SelfSignedCertificate.build(rsaKeyPair())

        val cert = parse(der)
        cert.checkValidity(Date()) // tidak melempar = valid sekarang
        val approxTenYearsMillis = 3650L * 86_400_000
        assertTrue(
            "notAfter harus sekitar 10 tahun dari sekarang",
            cert.notAfter.time - before.time in (approxTenYearsMillis - 86_400_000)..(approxTenYearsMillis + 86_400_000),
        )
    }

    @Test
    fun `two calls for the same key pair produce different but both-valid certificates`() {
        // Tidak idempoten di level ini -- idempotensi dijamin RsaKeyStore
        // (sertifikat dibuat sekali, disimpan). Tes ini cuma memastikan
        // memanggil dua kali tidak melempar atau menghasilkan sesuatu yang aneh.
        val keyPair = rsaKeyPair()

        val first = parse(SelfSignedCertificate.build(keyPair))
        val second = parse(SelfSignedCertificate.build(keyPair))

        first.verify(keyPair.public)
        second.verify(keyPair.public)
    }
}
