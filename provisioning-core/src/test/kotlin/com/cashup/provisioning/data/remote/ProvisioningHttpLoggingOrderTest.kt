package com.cashup.provisioning.data.remote

import com.cashup.provisioning.data.ProvisioningRepository
import com.cashup.signing.DeviceSigner
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Regresi untuk temuan review: [HttpLoggingInterceptor] harus terpasang
 * PALING TERAKHIR pada client yang ditandatangani (spec §7.5 item 7).
 *
 * Kalau logging terpasang sebelum [SigningInterceptor], ia tidak pernah
 * melihat request yang sudah dibubuhi `X-Signature`/`X-Device-Id`/`X-Nonce`,
 * sehingga `redactHeader("X-Signature")` tidak punya apa pun untuk diredaksi
 * -- dan bug penolakan tanda tangan yang sesungguhnya akan tampak di log
 * sebagai header yang hilang, bukan nilai yang diredaksi.
 *
 * Logger bawaan [okhttp3.logging.HttpLoggingInterceptor] di JVM biasa (bukan
 * Android) memakai `java.util.logging`. Test ini memasang [Handler] sementara
 * pada logger ROOT (lihat [capturePlatformLog]) untuk menangkap baris yang
 * benar-benar dicatat, lalu memastikan `X-Signature` muncul di situ untuk
 * request yang ditandatangani -- redaksinya sendiri ikut kelihatan (`██`),
 * yang justru membuktikan interceptor itu berjalan setelah tanda tangan
 * ditambahkan.
 */
class ProvisioningHttpLoggingOrderTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ProvisioningRepository

    private val signer = object : DeviceSigner {
        override fun deviceId(): String = "PAX-A920-0012938"
        override fun sign(canonicalBytes: ByteArray): ByteArray = ByteArray(64) { 7 }
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        repository = ProvisioningHttp.create(
            baseUrl = server.url("/").toString(),
            deviceSigner = signer,
            debugLogging = true,
        )
    }

    @After
    fun stop() {
        server.shutdown()
    }

    /**
     * Menempel [Handler] pada logger ROOT, bukan pada nama logger tertentu
     * milik OkHttp -- nama itu berbeda-beda tergantung [okhttp3.internal.platform.Platform]
     * konkret yang terpilih saat runtime (mis. `Jdk9Platform` di JDK modern),
     * sedangkan `sourceClassName` yang tampil di output default JUL selalu
     * `Platform` (tempat method `log()` didefinisikan). Menempel di root
     * menangkap rekaman itu tanpa perlu menebak nama logger yang tepat.
     */
    private fun capturePlatformLog(block: suspend () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val root = Logger.getLogger("")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { lines += record.message }
            override fun flush() {}
            override fun close() {}
        }
        handler.level = Level.ALL
        root.addHandler(handler)
        try {
            runBlocking { block() }
        } finally {
            root.removeHandler(handler)
        }
        return lines
    }

    @Test
    fun `logging on the signed client sees the signature header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"orderId":"o-1","wrappedPackageKey":"d3JhcHBlZA"}}"""))

        val logged = capturePlatformLog {
            repository.downloadKeyPackage(KeyPackageRequest("o-1", "tok-1"))
        }.joinToString("\n")

        // Header ini hanya dicatat oleh HttpLoggingInterceptor kalau ia
        // berjalan SETELAH SigningInterceptor menambahkannya. Nilainya
        // sendiri diredaksi lewat redactHeader("X-Signature"), tapi nama
        // headernya tetap tercetak -- yang membuktikan urutannya benar.
        assertTrue("expected X-Signature to be logged (proves logging ran after signing):\n$logged", logged.contains("X-Signature:"))
        assertTrue("expected X-Device-Id to be logged", logged.contains("X-Device-Id:"))
        assertTrue("expected X-Nonce to be logged", logged.contains("X-Nonce:"))
    }

    @Test
    fun `the unsigned client still logs its request headers`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"orderId":"o-1","activationToken":"tok-1"}}"""))

        val logged = capturePlatformLog {
            repository.redeem(QrRedeemRequest("ABCD-1234", "PAX-A920-0012938", "rsa-spki", "eddsa-raw"))
        }.joinToString("\n")

        assertTrue("expected X-Correlation-Id to be logged:\n$logged", logged.contains("X-Correlation-Id:"))
    }
}
