package com.cashup.provisioning.crypto

import java.security.MessageDigest
import javax.crypto.BadPaddingException

/**
 * OAEP decoding after RSA/NoPadding is performed by AndroidKeyStore. Android 13
 * cannot authorize MGF1 SHA-256 for its built-in OAEP operation, but it can
 * keep the RSA private exponent non-exportable while returning the encoded
 * message for OAEP verification here. This decoder accepts SHA-256 for both
 * the message digest and MGF1, with the empty label required by TR-34.
 */
internal object OaepSha256 {
    private const val HASH_SIZE = 32
    private val EMPTY_LABEL_HASH = MessageDigest.getInstance("SHA-256").digest(ByteArray(0))

    fun decode(rawRsaResult: ByteArray, modulusBytes: Int): ByteArray {
        if (modulusBytes < 2 * HASH_SIZE + 2 || rawRsaResult.size > modulusBytes) {
            throw BadPaddingException("TR-34 OAEP block length is invalid")
        }
        val em = ByteArray(modulusBytes)
        rawRsaResult.copyInto(em, modulusBytes - rawRsaResult.size)
        val maskedSeed = em.copyOfRange(1, 1 + HASH_SIZE)
        val maskedDb = em.copyOfRange(1 + HASH_SIZE, em.size)
        val seedMask = mgf1(maskedDb, HASH_SIZE)
        val seed = ByteArray(HASH_SIZE) { (maskedSeed[it].toInt() xor seedMask[it].toInt()).toByte() }
        val dbMask = mgf1(seed, maskedDb.size)
        val db = ByteArray(maskedDb.size) { (maskedDb[it].toInt() xor dbMask[it].toInt()).toByte() }

        try {
            var bad = em[0].toInt() and 0xff
            for (i in 0 until HASH_SIZE) {
                bad = bad or (db[i].toInt() xor EMPTY_LABEL_HASH[i].toInt())
            }
            var foundDelimiter = 0
            var delimiter = 0
            for (i in HASH_SIZE until db.size) {
                val value = db[i].toInt() and 0xff
                val isZero = if (value == 0) 1 else 0
                val isOne = if (value == 1) 1 else 0
                bad = bad or ((1 - foundDelimiter) and (1 - isZero) and (1 - isOne))
                if (foundDelimiter == 0 && isOne == 1) delimiter = i
                foundDelimiter = foundDelimiter or isOne
            }
            if (bad != 0 || foundDelimiter == 0) {
                throw BadPaddingException("TR-34 OAEP validation failed")
            }
            return db.copyOfRange(delimiter + 1, db.size)
        } finally {
            em.fill(0)
            maskedSeed.fill(0)
            maskedDb.fill(0)
            seedMask.fill(0)
            seed.fill(0)
            dbMask.fill(0)
            db.fill(0)
        }
    }

    private fun mgf1(seed: ByteArray, length: Int): ByteArray {
        val result = ByteArray(length)
        val input = ByteArray(seed.size + 4)
        seed.copyInto(input)
        try {
            var offset = 0
            var counter = 0
            while (offset < length) {
                input[seed.size] = (counter ushr 24).toByte()
                input[seed.size + 1] = (counter ushr 16).toByte()
                input[seed.size + 2] = (counter ushr 8).toByte()
                input[seed.size + 3] = counter.toByte()
                val digest = MessageDigest.getInstance("SHA-256").digest(input)
                try {
                    val count = minOf(digest.size, length - offset)
                    digest.copyInto(result, offset, 0, count)
                    offset += count
                    counter++
                } finally {
                    digest.fill(0)
                }
            }
            return result
        } finally {
            input.fill(0)
        }
    }
}
