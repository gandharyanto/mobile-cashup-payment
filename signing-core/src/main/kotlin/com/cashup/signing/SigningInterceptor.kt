package com.cashup.signing

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.UUID

/**
 * Dilempar kalau [SigningInterceptor] dipanggil sebelum device terprovisioning.
 *
 * Sengaja [IOException], bukan [IllegalStateException]: di jalur async OkHttp
 * (`enqueue`, yang dipakai fungsi `suspend` Retrofit) exception non-IOException
 * dari sebuah interceptor dilaporkan lewat `onFailure` LALU dilempar ulang,
 * sehingga proses mati lewat uncaught-exception handler milik thread
 * dispatcher. IOException muncul rapi sebagai kegagalan pemanggilan biasa.
 */
class DeviceNotProvisionedException :
    IOException("SigningInterceptor dipanggil sebelum device terprovisioning")

/**
 * Dilempar kalau `X-Timestamp` belum ada saat request sampai ke sini — artinya
 * `RequestHeadersInterceptor` tidak terpasang, atau terpasang setelah
 * interceptor ini. IOException dengan alasan yang sama seperti di atas.
 */
class MissingTimestampException :
    IOException("X-Timestamp tidak ada; RequestHeadersInterceptor harus terpasang lebih dulu")

/**
 * Menandatangani setiap request yang melewatinya dengan `X-Device-Id`,
 * `X-Nonce`, dan `X-Signature`.
 *
 * Pasang ini HANYA pada client yang bicara ke endpoint bertanda tangan.
 * `qr-redeem` tidak boleh melewatinya: saat itu backend belum mengenal public
 * key device, karena kunci itu justru baru dikirim di request tersebut.
 *
 * Gagal keras (melempar) alih-alih mengirim request tanpa tanda tangan —
 * request tak bertanda tangan yang sampai ke sini adalah bug wiring, bukan
 * keadaan yang bisa dipulihkan.
 */
class SigningInterceptor(
    private val signer: DeviceSigner,
    private val requestSigner: Ed25519RequestSigner = Ed25519RequestSigner(),
    private val nonceFactory: () -> String = { UUID.randomUUID().toString() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val deviceId = signer.deviceId() ?: throw DeviceNotProvisionedException()
        val timestamp = request.header("X-Timestamp") ?: throw MissingTimestampException()

        // Body one-shot/duplex tidak bisa dibaca dua kali (sekali di sini untuk
        // ditandatangani, sekali saat OkHttp menuliskannya ke wire), jadi
        // ditandatangani sebagai body kosong sementara body aslinya tetap
        // terkirim utuh. Body biasa -- yang berlaku untuk seluruh alur
        // provisioning -- ditandatangani dengan benar.
        val bodyBytes = request.body
            ?.takeIf { !it.isOneShot() && !it.isDuplex() }
            ?.let { body -> Buffer().also { body.writeTo(it) }.readByteArray() }
            ?: ByteArray(0)

        val nonce = nonceFactory()
        val canonical = requestSigner.canonicalize(
            method = request.method,
            path = request.url.encodedPath,
            deviceId = deviceId,
            timestamp = timestamp,
            nonce = nonce,
            body = bodyBytes,
        )
        val signature = Ed25519RequestSigner.encodeSignature(signer.sign(canonical))

        val signed = request.newBuilder()
            .header("X-Device-Id", deviceId)
            .header("X-Nonce", nonce)
            .header("X-Signature", signature)
            .build()
        return chain.proceed(signed)
    }
}