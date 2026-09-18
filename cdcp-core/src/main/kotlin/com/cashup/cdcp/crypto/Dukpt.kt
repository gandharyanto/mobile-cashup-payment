package com.cashup.cdcp.crypto

import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DUKPT (ANSI X9.24-1, TDES) -- sisi terminal: menurunkan kunci lalu MENGENKRIPSI.
 *
 * Port BYTE-EXACT dari `edc-simulator/src/main/kotlin/id/cashup/edcsimulator/crypto/Dukpt.kt`
 * (repo terpisah, backend Kotlin/Spring Boot yang sudah terbukti bicara dengan `corepayment`
 * sungguhan). JANGAN "diperbaiki"/ditulis ulang dari spesifikasi X9.24 buku teks -- dua
 * penyimpangan di bawah WAJIB dipertahankan persis, kalau tidak transaksi tidak akan pernah
 * terdekripsi oleh Core:
 *
 * 1. **Jalur data melewati langkah self-encrypt**: `encrypt3DesEcb(dataKey, dataKey)` sebelum
 *    dipakai. Jalur PIN **tidak**.
 * 2. **Konstanta varian di-XOR ke KEDUA paruh kunci**, bukan dibedakan kiri/kanan.
 *
 * Kesetiaannya dikunci ke vektor uji X9.24-1 yang dipublikasikan di `DukptTests`
 * (`app/src/test/.../crypto/DukptTests.kt`), disalin dari `DukptEncryptTests` sumber aslinya.
 */
internal object Dukpt {

    /** Menihilkan 21 bit penghitung transaksi pada KSN 10 byte. */
    private val KSN_MASK = "FFFFFFFFFFFFFFE00000".hexToBytes()

    /** Menyisakan hanya 21 bit penghitung transaksi. */
    private val TRANSACTION_COUNTER_MASK = "000000000000001FFFFF".hexToBytes()

    /** Varian yang dipakai saat menurunkan IPEK dan kunci berikutnya. */
    private val BDK_MASK = "C0C0C0C000000000C0C0C0C000000000".hexToBytes()

    private val PIN_VARIANT = "00000000000000FF".hexToBytes()

    private val DATA_VARIANT = "0000000000FF0000".hexToBytes()

    /** Bit ke-21 -- titik awal penelusuran penghitung transaksi. */
    private val SHIFT_START = "0000000000100000".hexToBytes()

    private val ZERO_IV = ByteArray(8)

    const val KSN_LENGTH = 10
    const val KSN_INDEX_LENGTH = 5

    private const val DOUBLE_KEY_LENGTH = 16
    private const val TRIPLE_KEY_LENGTH = 24

    /**
     * Menurunkan IPEK dari BDK dan KSN. Dipakai hanya untuk vektor uji -- jalur transaksi app ini
     * tidak pernah memegang BDK, cuma IPEK + base KSN hasil provisioning dari corepayment.
     */
    fun generateIpek(ksn: ByteArray, bdk: ByteArray): ByteArray {
        require(ksn.size == KSN_LENGTH) { "KSN harus $KSN_LENGTH byte, dapat ${ksn.size}" }
        require(bdk.size == DOUBLE_KEY_LENGTH) { "BDK harus $DOUBLE_KEY_LENGTH byte, dapat ${bdk.size}" }

        val eightMostSignificant = (ksn and KSN_MASK).copyOfRange(0, 8)

        val left = encrypt3DesEcb(eightMostSignificant, bdk)
        val right = encrypt3DesEcb(eightMostSignificant, bdk xor BDK_MASK)
        return left + right
    }

    /**
     * Menurunkan kunci sesi untuk penghitung transaksi yang terkandung di dalam [ksn].
     *
     * Inti "non-reversible key generation": kunci diturunkan maju bit demi bit dari IPEK, sehingga
     * kunci satu transaksi tidak dapat dipakai memundurkan kunci transaksi sebelumnya.
     */
    fun deriveKeyFromIpek(ksn: ByteArray, ipek: ByteArray): ByteArray {
        require(ksn.size == KSN_LENGTH) { "KSN harus $KSN_LENGTH byte, dapat ${ksn.size}" }
        require(ipek.size == DOUBLE_KEY_LENGTH) { "IPEK harus $DOUBLE_KEY_LENGTH byte, dapat ${ipek.size}" }

        val counter = (ksn and TRANSACTION_COUNTER_MASK).copyOfRange(2, 10)
        val baseRegister = (ksn and KSN_MASK).copyOfRange(2, 10)

        var currentKey = ipek
        var register = baseRegister
        var shift = SHIFT_START

        while (BigInteger(1, shift).signum() > 0) {
            if (BigInteger(1, shift and counter).signum() > 0) {
                register = register or shift

                // Crypto Register-2: XOR paruh kanan -> DES -> XOR paruh kanan.
                var cryptoRegister2 = register xor currentKey.rightHalf()
                cryptoRegister2 = desEncrypt(cryptoRegister2, currentKey.leftHalf())
                cryptoRegister2 = cryptoRegister2 xor currentKey.rightHalf()

                currentKey = currentKey xor BDK_MASK

                var cryptoRegister1 = register xor currentKey.rightHalf()
                cryptoRegister1 = desEncrypt(cryptoRegister1, currentKey.leftHalf())
                cryptoRegister1 = cryptoRegister1 xor currentKey.rightHalf()

                currentKey = cryptoRegister1 + cryptoRegister2
            }
            shift = shiftRight(shift)
        }
        return currentKey
    }

    /** Kunci enkripsi data = kunci sesi di-XOR varian data pada kedua paruhnya. */
    fun dataEncryptionKey(derivedKey: ByteArray): ByteArray = derivedKey.applyVariant(DATA_VARIANT)

    /** Kunci enkripsi PIN = kunci sesi di-XOR varian PIN pada kedua paruhnya. */
    fun pinEncryptionKey(derivedKey: ByteArray): ByteArray = derivedKey.applyVariant(PIN_VARIANT)

    /**
     * Mengenkripsi data (track 2, nominal) -- pasangan dari `decryptData` di Core.
     *
     * `plaintext` wajib kelipatan 8 byte: mode-nya `NoPadding`, jadi pemadatan adalah tanggung
     * jawab pemanggil. Lihat [padToBlock].
     */
    fun encryptData(plaintext: ByteArray, ksn: ByteArray, ipek: ByteArray): ByteArray {
        require(plaintext.size % BLOCK_SIZE == 0) {
            "Plaintext harus kelipatan $BLOCK_SIZE byte (NoPadding), dapat ${plaintext.size}"
        }
        val dataKey = dataEncryptionKey(deriveKeyFromIpek(ksn, ipek))
        return encrypt3DesCbc(plaintext, encrypt3DesEcb(dataKey, dataKey))
    }

    /** Kebalikan [encryptData]; dipakai test untuk membuktikan round-trip. */
    fun decryptData(ciphertext: ByteArray, ksn: ByteArray, ipek: ByteArray): ByteArray {
        val dataKey = dataEncryptionKey(deriveKeyFromIpek(ksn, ipek))
        return decrypt3DesCbc(ciphertext, encrypt3DesEcb(dataKey, dataKey))
    }

    /**
     * Mengenkripsi PIN block. **Tanpa** langkah self-encrypt -- berbeda dari [encryptData]. Ini
     * satu-satunya operasi yang tidak punya padanan langsung di Core untuk dibandingkan; hanya
     * bisa diverifikasi lewat round-trip [decryptPinBlock] dan transaksi online-PIN sungguhan.
     */
    fun encryptPinBlock(pinBlock: ByteArray, ksn: ByteArray, ipek: ByteArray): ByteArray {
        require(pinBlock.size == BLOCK_SIZE) { "PIN block harus $BLOCK_SIZE byte, dapat ${pinBlock.size}" }
        return encrypt3DesCbc(pinBlock, pinEncryptionKey(deriveKeyFromIpek(ksn, ipek)))
    }

    /** Kebalikan [encryptPinBlock]; ada untuk test, bukan dipakai alur terminal. */
    fun decryptPinBlock(pinBlock: ByteArray, ksn: ByteArray, ipek: ByteArray): ByteArray =
        decrypt3DesCbc(pinBlock, pinEncryptionKey(deriveKeyFromIpek(ksn, ipek)))

    /** Nilai penghitung transaksi (21 bit) di dalam KSN. */
    fun transactionCounter(ksn: ByteArray): Long = BigInteger(1, ksn and TRANSACTION_COUNTER_MASK).toLong()

    /**
     * Menyusun KSN penuh dari KSN dasar + lima digit hex penghitung.
     *
     * Cerminan persis `DukptCardCryptoGateway.resolveKey` di Core: lima karakter terakhir KSN
     * dasar **diganti** (bukan ditambahkan) oleh indeks. Kalau kedua sisi tidak sepakat di titik
     * ini, kunci sesinya berbeda dan seluruh dekripsi menghasilkan sampah.
     */
    fun composeKsn(baseKsn: ByteArray, ksnIndex: String): ByteArray {
        require(ksnIndex.length == KSN_INDEX_LENGTH) {
            "KSN index harus $KSN_INDEX_LENGTH karakter hex, dapat '${ksnIndex.length}'"
        }
        return (baseKsn.toHex().dropLast(KSN_INDEX_LENGTH) + ksnIndex.uppercase()).hexToBytes()
    }

    /** Lima digit hex penghitung, format yang dikirim terminal ke Core. */
    fun ksnIndex(counter: Int): String {
        require(counter in 1..MAX_COUNTER) { "Penghitung DUKPT di luar 21 bit: $counter" }
        return "%0${KSN_INDEX_LENGTH}X".format(counter)
    }

    // --- primitif ---------------------------------------------------------------------------

    private fun ByteArray.applyVariant(variant: ByteArray) = (leftHalf() xor variant) + (rightHalf() xor variant)

    private fun ByteArray.leftHalf() = copyOfRange(0, 8)

    private fun ByteArray.rightHalf() = copyOfRange(8, 16)

    private infix fun ByteArray.and(other: ByteArray) = ByteArray(size) { (this[it].toInt() and other[it].toInt()).toByte() }

    private infix fun ByteArray.or(other: ByteArray) = ByteArray(size) { (this[it].toInt() or other[it].toInt()).toByte() }

    private infix fun ByteArray.xor(other: ByteArray) = ByteArray(size) { (this[it].toInt() xor other[it].toInt()).toByte() }

    private fun shiftRight(value: ByteArray): ByteArray {
        val shifted = BigInteger(1, value).shiftRight(1).toByteArray()
        val result = ByteArray(value.size)
        // `toByteArray()` dapat menyisipkan byte tanda di depan; yang diambil adalah byte paling
        // tidak signifikan sebanyak ukuran aslinya.
        val source = if (shifted.size > value.size) shifted.copyOfRange(shifted.size - value.size, shifted.size) else shifted
        source.copyInto(result, result.size - source.size)
        return result
    }

    private fun desEncrypt(input: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("DES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            doFinal(input)
        }

    private fun encrypt3DesEcb(input: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("DESede/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toTripleKey(), "DESede"))
            doFinal(input)
        }

    private fun encrypt3DesCbc(input: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("DESede/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toTripleKey(), "DESede"), IvParameterSpec(ZERO_IV))
            doFinal(input)
        }

    private fun decrypt3DesCbc(input: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("DESede/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toTripleKey(), "DESede"), IvParameterSpec(ZERO_IV))
            doFinal(input)
        }

    /**
     * Memuai kunci ganda (K1K2) menjadi rangkap tiga (K1K2K1).
     *
     * Dilakukan eksplisit, tidak diserahkan ke provider JCE: sebagian provider memuainya sendiri
     * dan sebagian menolak kunci 16 byte, sehingga hasilnya bergantung provider mana yang
     * kebetulan terpasang. Core melakukan hal yang sama -- kalau salah satu sisi berubah, kedua
     * sisi berhenti cocok.
     */
    private fun ByteArray.toTripleKey(): ByteArray = if (size == TRIPLE_KEY_LENGTH) this else this + copyOfRange(0, 8)

    private const val BLOCK_SIZE = 8
    private const val MAX_COUNTER = 0x1FFFFF
}

/**
 * Memadatkan teks ke kelipatan 8 byte dengan spasi.
 *
 * Spasi, bukan `0x00`: Core memotong hasil dekripsi dengan `takeWhile { it.code in 0x20..0x7E }`
 * kalau panjang aslinya tidak dikirim.
 */
internal fun String.padToBlock(): String = padEnd(((length + 7) / 8) * 8, ' ')

