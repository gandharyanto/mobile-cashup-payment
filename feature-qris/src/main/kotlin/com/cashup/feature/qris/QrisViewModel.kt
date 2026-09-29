package com.cashup.feature.qris

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cashup.common.network.ApiError
import com.cashup.common.network.ApiResult
import com.cashup.qris.GeneratedQr
import com.cashup.qris.PayApp
import com.cashup.qris.QrisPaymentResult
import com.cashup.qris.QrisStatus
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class QrisStage { LOADING_APPS, SELECT_APP, GENERATING, SHOWING_QR, PAID, EXPIRED, FAILED }

data class QrisUiState(
    val stage: QrisStage = QrisStage.LOADING_APPS,
    val amount: BigDecimal,
    val apps: List<PayApp> = emptyList(),
    val qr: GeneratedQr? = null,
    /** Jam monotonic lokal ([monotonicMillis]) saat QR kedaluwarsa; bukan dari `expiresAt` server. */
    val qrDeadline: Long = 0L,
    val result: QrisPaymentResult? = null,
    @StringRes val messageRes: Int? = null,
    val errorCode: String? = null,
)

/** Jam monotonic yang sama dipakai ViewModel dan fragment; tanpa API Android supaya JVM-testable. */
internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

class QrisViewModel(
    private val dependencies: QrisDependencies,
    amountRupiah: Long,
    private val clock: () -> Long = ::monotonicMillis,
    private val retryDelayMs: Long = 1_000L,
) : ViewModel() {
    private val amount = BigDecimal.valueOf(amountRupiah)
    private val mutableState = MutableStateFlow(QrisUiState(amount = amount))
    val state: StateFlow<QrisUiState> = mutableState

    private var selectedApp: PayApp? = null
    /** Dipertahankan hanya setelah kegagalan NETWORK: request mungkin sudah sampai ke server. */
    private var attemptId: String? = null

    init {
        // Koneksi MQTT paralel; tidak menunda pemuatan pay-apps. Kegagalan sudah ditelan implementasinya.
        viewModelScope.launch { dependencies.ensureNotificationsConnected() }
        viewModelScope.launch { begin() }
    }

    fun selectApp(qrisHostId: Int) {
        val app = state.value.apps.firstOrNull { it.qrisHostId == qrisHostId } ?: return
        if (state.value.stage != QrisStage.SELECT_APP) return
        viewModelScope.launch { generateAndTrack(app) }
    }

    /** "Coba lagi" (FAILED) atau "Buat QR baru" (EXPIRED). */
    fun retry() {
        val stage = state.value.stage
        if (stage != QrisStage.FAILED && stage != QrisStage.EXPIRED) return
        viewModelScope.launch { selectedApp?.let { generateAndTrack(it) } ?: begin() }
    }

    private suspend fun begin() {
        if (amount.signum() <= 0) return fail(R.string.qris_invalid_amount)
        if (!dependencies.isTransactionAllowed()) return fail(R.string.qris_device_suspended)
        update { copy(stage = QrisStage.LOADING_APPS, messageRes = null, errorCode = null) }
        when (val loaded = dependencies.payApps()) {
            is ApiResult.Failure -> failWith(loaded.error)
            is ApiResult.Success -> {
                val dynamic = loaded.data.filter { it.supportsDynamic }
                when {
                    dynamic.isEmpty() -> fail(R.string.qris_no_dynamic)
                    dynamic.size == 1 -> generateAndTrack(dynamic.first())
                    else -> update { copy(stage = QrisStage.SELECT_APP, apps = dynamic) }
                }
            }
        }
    }

    private suspend fun generateAndTrack(app: PayApp) {
        selectedApp = app
        if (!dependencies.isTransactionAllowed()) return fail(R.string.qris_device_suspended)
        update { copy(stage = QrisStage.GENERATING, qr = null, result = null, messageRes = null, errorCode = null) }
        val attempt = attemptId ?: UUID.randomUUID().toString().also { attemptId = it }
        var generated = dependencies.generate(app.qrisHostId, amount, attempt)
        if (generated is ApiResult.Failure && generated.error.code == IN_PROGRESS) {
            delay(retryDelayMs)
            generated = dependencies.generate(app.qrisHostId, amount, attempt)
        }
        when (generated) {
            is ApiResult.Failure -> {
                if (generated.error.code != NETWORK) attemptId = null
                if (generated.error.code in HOST_ERRORS) selectedApp = null // retry() harus memilih ulang
                failWith(generated.error)
            }
            is ApiResult.Success -> {
                attemptId = null
                showAndTrack(generated.data, app)
            }
        }
    }

    private suspend fun showAndTrack(qr: GeneratedQr, app: PayApp) {
        val deadline = clock() + app.qrExpirySeconds * 1_000L
        update { copy(stage = QrisStage.SHOWING_QR, qr = qr, qrDeadline = deadline) }
        val outcome = dependencies.track(qr, app.qrExpirySeconds)
        update {
            when {
                outcome.status.isPaid -> copy(stage = QrisStage.PAID, result = outcome)
                outcome.status == QrisStatus.EXPIRED -> copy(stage = QrisStage.EXPIRED, result = outcome)
                else -> copy(stage = QrisStage.FAILED, result = outcome, messageRes = R.string.qris_failed)
            }
        }
    }

    private fun fail(@StringRes messageRes: Int) =
        update { copy(stage = QrisStage.FAILED, messageRes = messageRes, errorCode = null) }

    private fun failWith(error: ApiError) =
        update { copy(stage = QrisStage.FAILED, messageRes = QrisErrorMessages.forCode(error.code), errorCode = error.code) }

    private inline fun update(block: QrisUiState.() -> QrisUiState) {
        mutableState.value = mutableState.value.block()
    }

    class Factory(
        private val dependencies: QrisDependencies,
        private val amountRupiah: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            QrisViewModel(dependencies, amountRupiah) as T
    }

    private companion object {
        const val NETWORK = "NETWORK"
        const val IN_PROGRESS = "IDEMPOTENCY_KEY_IN_PROGRESS"
        val HOST_ERRORS = setOf("QRIS_HOST_NOT_ACTIVE", "QRIS_ACCOUNT_NOT_FOUND", "QRIS_HOST_AMBIGUOUS")
    }
}
