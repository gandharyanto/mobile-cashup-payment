package com.cashup.provisioning.crypto

import java.math.BigInteger
import java.security.KeyPair
import java.time.Instant
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Sertifikat self-signed X.509 atas sebuah [KeyPair] RSA — port dari
 * `DeviceIdentityStore.certificateChain()` di `edc-mobile`.
 *
 * Fungsi murni yang menerima `KeyPair` apa pun, bukan metode `RsaKeyStore`,
 * supaya bisa diuji di JVM biasa tanpa Android Keystore. `RsaKeyStore.sign()`
 * di bawah bekerja lewat `java.security.Signature` standar — kompatibel
 * dengan handle `PrivateKey` AndroidKeyStore non-extractable, jadi
 * `JcaContentSignerBuilder` di sini juga bekerja benar untuk key TEE
 * sungguhan, bukan cuma key software yang dites di sini.
 *
 * Dikirim sebagai `deviceCertificateChain` di `qr-redeem` — proof-of-
 * possession: backend tahu device benar-benar memegang private key yang
 * berpasangan dengan `rsaPublicKey`, karena sertifikat ini hanya bisa
 * dibuat kalau punya akses tanda tangan atas key itu.
 */
object SelfSignedCertificate {

    private const val VALIDITY_DAYS = 3650L

    fun build(keyPair: KeyPair, subjectCn: String = "cashup-edc-device"): ByteArray {
        BcProvider.ensureInstalled()
        val subject = X500Name("CN=$subjectCn")
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(VALIDITY_DAYS * 86_400)),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BcProvider.NAME)
            .build(keyPair.private)
        return JcaX509CertificateConverter()
            .setProvider(BcProvider.NAME)
            .getCertificate(builder.build(signer))
            .encoded
    }
}
