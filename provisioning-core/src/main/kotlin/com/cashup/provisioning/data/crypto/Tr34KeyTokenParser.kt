package com.cashup.provisioning.crypto

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.cms.SignedData
import org.bouncycastle.asn1.cms.SignerInfo

class Tr34ParsingException(message: String) : Exception(message)

/** Hasil parse satu key token TR-34: `Kn` (IPEK mentah) + header key block untuk sanity-check. */
internal data class Tr34KeyTokenResult(val ipek: ByteArray, val keyBlockHeader: String)

/**
 * Kebalikan dari `Tr34MessageBuilder` (corepayment) -- parse CMS `SignedData` hasil AT1000
 * (command `123`/`136`/`126`/`139`) untuk dapatkan `Kn` (IPEK mentah) yang device sendiri yang
 * dekripsi, TANPA AT1000/corepayment terlibat sama sekali di sini. Struktur & urutan verifikasi
 * ini SAMA PERSIS dengan yang dipakai `At1000Tr34IndependentVerification` (corepayment,
 * 2026-09-17) untuk membuktikan pipa TR-34 mengirim key yang benar -- di sini device beneran
 * memakainya untuk membuka paket key produksi, bukan cuma verifikasi.
 *
 * **Kenapa parsing MANUAL byte-per-byte (bukan `CMSSignedData`/`CMSEnvelopedData` BC)**: AT1000
 * membangun struktur ini TIDAK 100% mengikuti konvensi CMS standar (lihat KDoc
 * `Tr34MessageBuilder` di corepayment) -- `messageDigest` di `SignedAttributes` di-hash dari
 * `EnvelopedData` TANPA outer SEQUENCE wrapper, padahal API tingkat tinggi BC mengasumsikan
 * `messageDigest` = hash `encapContent` APA ADANYA (termasuk wrapper). Memakai API tinggi BC akan
 * SELALU gagal verifikasi walau signature-nya sendiri valid.
 */
internal object Tr34KeyTokenParser {
    /**
     * @param keyToken CMS `SignedData` (field `keyBlock` dari respons `TR34_2019`).
     * @param kdhCertificateChain leaf dulu -- signature diverifikasi terhadap certificate PERTAMA.
     * @param unwrapEphemeralKey RSA decrypt lewat pemilik key device. Parser tidak memilih
     *   provider agar handle AndroidKeyStore tetap diproses oleh AndroidKeyStore.
     */
    fun parseKeyToken(keyToken: ByteArray, kdhCertificateChain: List<ByteArray>, unwrapEphemeralKey: (ByteArray) -> ByteArray): Tr34KeyTokenResult {
        BcProvider.ensureInstalled()
        val kdhLeaf = CertificateFactory.getInstance("X.509")
            .generateCertificate(kdhCertificateChain.first().inputStream()) as X509Certificate

        val signedData = try {
            SignedData.getInstance(ContentInfo.getInstance(ASN1InputStream(keyToken).readObject()).content)
        } catch (failure: Exception) {
            throw Tr34ParsingException("Key token bukan CMS SignedData valid: ${failure.message}")
        }

        // 1. Verifikasi signature MANUAL -- lihat KDoc kelas ini soal kenapa bukan CMSSignedData.
        val signerInfo = SignerInfo.getInstance(signedData.signerInfos.getObjectAt(0))
        val signedAttributesBytes = signerInfo.authenticatedAttributes.getEncoded("BER") ?: signerInfo.authenticatedAttributes.encoded
        val signatureValid = Signature.getInstance("SHA256withRSA").run {
            initVerify(kdhLeaf.publicKey)
            update(signedAttributesBytes)
            verify(signerInfo.encryptedDigest.octets)
        }
        if (!signatureValid) throw Tr34ParsingException("Signature key token TIDAK valid terhadap sertifikat KDH")

        // 2. Verifikasi messageDigest SignedAttributes cocok dengan hash EnvelopedData.
        val encapContent = (signedData.encapContentInfo.content as ASN1OctetString).octets
        val envelopedDataDigest = MessageDigest.getInstance("SHA-256").digest(reconstructRawEnvelopedData(encapContent))
        val signedAttributesSet = ASN1Set.getInstance(signedAttributesBytes)
        val reportedDigest = signedAttributesSet.toArray()
            .map { Attribute.getInstance(it) }
            .firstOrNull { it.attrType == CMSAttributes.messageDigest }
            ?.attrValues?.getObjectAt(0)?.let { ASN1OctetString.getInstance(it) }?.octets
            ?: throw Tr34ParsingException("SignedAttributes tanpa messageDigest")
        if (!envelopedDataDigest.contentEquals(reportedDigest)) {
            throw Tr34ParsingException("messageDigest SignedAttributes tidak cocok dengan EnvelopedData -- key token rusak/dipalsukan")
        }

        // 3. Ekstrak encryptedKey (RSA-OAEP) + iv/encryptedContent (3DES-CBC) dari EnvelopedData.
        val sequence = ASN1Sequence.getInstance(encapContent)
        val recipientInfos = ASN1Set.getInstance(sequence.getObjectAt(1))
        val keyTransRecipientInfo = ASN1Sequence.getInstance(ASN1Sequence.getInstance(recipientInfos.getObjectAt(0)))
        val encryptedEphemeralKey = ASN1OctetString.getInstance(keyTransRecipientInfo.getObjectAt(3)).octets

        val encryptedContentInfo = ASN1Sequence.getInstance(sequence.getObjectAt(2))
        val innerAlgSeq = ASN1Sequence.getInstance(encryptedContentInfo.getObjectAt(1))
        val iv = ASN1OctetString.getInstance(innerAlgSeq.getObjectAt(1)).octets
        val encryptedContentTagged = ASN1TaggedObject.getInstance(innerAlgSeq.getObjectAt(2))
        val encryptedContent = ASN1OctetString.getInstance(encryptedContentTagged, false).octets

        // 4. RSA-OAEP-SHA256 unwrap lewat provider yang memiliki private key device.
        val ephemeralKey = try {
            unwrapEphemeralKey(encryptedEphemeralKey)
        } catch (failure: Exception) {
            throw Tr34ParsingException("Gagal unwrap ephemeral key TR-34: ${failure.message}")
        }

        // 5. 3DES-CBC decrypt BE pakai ephemeral key.
        val plaintextBe = try {
            val tripleDesKey = if (ephemeralKey.size == 16) ephemeralKey + ephemeralKey.copyOfRange(0, 8) else ephemeralKey
            Cipher.getInstance("DESede/CBC/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(tripleDesKey, "DESede"), IvParameterSpec(iv))
                doFinal(encryptedContent)
            }
        } finally {
            ephemeralKey.fill(0)
        }

        // 6. BE = SEQUENCE { Version, IssuerAndSerialNumber(KDH), Kn (OCTET STRING), KeyBlockHeaderAttribute }
        //    -- ditemukan lewat inspeksi struktur langsung 2026-09-17, BUKAN konkatenasi rata
        //    seperti kalimat manual "Version || ID_KDH_CRED || Kn || KBH" tampak menyiratkan.
        val beSequence = try {
            ASN1Sequence.getInstance(ASN1InputStream(ByteArrayInputStream(plaintextBe)).readObject())
        } catch (failure: Exception) {
            throw Tr34ParsingException("BE plaintext bukan SEQUENCE valid: ${failure.message}")
        }
        // AT1000 may encode a two-key TDES IPEK as K1|K2|K1 (24 bytes).  The
        // DUKPT implementation stores the canonical two-key representation K1|K2
        // (16 bytes), so reduce only that exact, cryptographically equivalent form.
        // A different 24-byte value would be a three-key key and must never be
        // silently truncated.
        val ipek = try {
            canonicalizeIpek(ASN1OctetString.getInstance(beSequence.getObjectAt(2)).octets)
        } catch (failure: IllegalArgumentException) {
            throw Tr34ParsingException("IPEK TR-34 tidak valid: ${failure.message}")
        }
        val keyBlockHeaderAttribute = Attribute.getInstance(beSequence.getObjectAt(3))
        val keyBlockHeader = ASN1OctetString.getInstance(keyBlockHeaderAttribute.attrValues.getObjectAt(0)).octets
            .toString(Charsets.US_ASCII)
        return Tr34KeyTokenResult(ipek, keyBlockHeader)
    }

    /** `wrapAsSequence` di `Tr34MessageBuilder` (corepayment) membungkus concatenated version||recipientInfos||encryptedContentInfo -- di sini tinggal parse sebagai SEQUENCE langsung. */
    private fun canonicalizeIpek(value: ByteArray): ByteArray = when {
        value.size == 16 -> value
        value.size == 24 && value.copyOfRange(0, 8).contentEquals(value.copyOfRange(16, 24)) -> value.copyOfRange(0, 16)
        else -> throw IllegalArgumentException("IPEK harus 16 byte atau format 24 byte K1|K2|K1")
    }

    private fun reconstructRawEnvelopedData(sequenceBytes: ByteArray): ByteArray {
        val sequence = ASN1Sequence.getInstance(sequenceBytes)
        return sequence.objects.asSequence()
            .map { (it as org.bouncycastle.asn1.ASN1Encodable).toASN1Primitive().encoded }
            .reduce { a, b -> a + b }
    }
}

