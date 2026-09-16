package com.cashup.app.scan

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.cashup.devicesdk.Scanner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Cadangan untuk terminal tanpa scanner hardware.
 *
 * ZXing, bukan ML Kit: ML Kit membawa model on-device beberapa megabyte beserta
 * pemakaian memorinya, dan target kita terminal 1 GB. QR provisioning adalah
 * kode cetak beresolusi tinggi dari dashboard — ZXing lebih dari cukup.
 *
 * Analyzer dibatasi ke satu frame terakhir (`STRATEGY_KEEP_ONLY_LATEST`) supaya
 * frame tidak menumpuk saat CPU lambat, dan setiap `ImageProxy` ditutup di
 * `finally` — ImageProxy yang bocor akan menghentikan aliran frame sepenuhnya.
 */
class CameraQrScanner(
    private val previewView: PreviewView,
    private val lifecycleOwner: LifecycleOwner,
) : Scanner {

    override suspend fun scanQr(timeoutMillis: Long): String? {
        val context = previewView.context
        val executor = Executors.newSingleThreadExecutor()
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }

        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val providerFuture = ProcessCameraProvider.getInstance(context)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .apply {
                                setAnalyzer(executor) { proxy ->
                                    val decoded = decode(proxy, reader)
                                    if (decoded != null && continuation.isActive) {
                                        continuation.resume(decoded)
                                    }
                                }
                            }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        continuation.invokeOnCancellation { provider.unbindAll() }
                    }, ContextCompat.getMainExecutor(context))
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            executor.shutdown()
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
    }

    private fun decode(proxy: ImageProxy, reader: MultiFormatReader): String? = try {
        val buffer = proxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val source = PlanarYUVLuminanceSource(
            bytes, proxy.width, proxy.height, 0, 0, proxy.width, proxy.height, false,
        )
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
    } catch (e: Exception) {
        // Frame tanpa QR melempar NotFoundException -- itu keadaan normal, bukan
        // kesalahan, dan terjadi puluhan kali per detik. Jangan dicatat.
        null
    } finally {
        proxy.close()
    }
}
