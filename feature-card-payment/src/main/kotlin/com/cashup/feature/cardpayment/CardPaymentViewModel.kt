package com.cashup.feature.cardpayment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cashup.cdcp.SaleRepository
import com.cashup.cdcp.SaleResponse
import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.CardAuthorization
import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardTransactionEvent
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest
import com.cashup.devicesdk.PairedDeviceInfo
import com.cashup.devicesdk.PairableCardReader
import java.math.BigDecimal
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class PaymentStage { ENTRY, CONNECTING, SELECT_READER, WAITING_CARD, PIN, AUTHORIZING, COMPLETING, SUCCESS, FAILURE }

data class CardPaymentUiState(
    val stage: PaymentStage = PaymentStage.ENTRY,
    val amount: BigDecimal? = null,
    val tip: BigDecimal = BigDecimal.ZERO,
    val message: String = "",
    val pinLength: Int = 0,
    val readers: List<PairedDeviceInfo> = emptyList(),
    val result: SaleResponse? = null,
    val externalReader: Boolean = false,
)

class CardPaymentViewModel(private val dependencies: CardPaymentDependencies) : ViewModel() {
    private val mutableState = MutableStateFlow(CardPaymentUiState())
    val state: StateFlow<CardPaymentUiState> = mutableState

    private var transactionJob: Job? = null
    private var reader: CardReader? = null
    private var pendingAmount: BigDecimal? = null
    private var pendingTip = BigDecimal.ZERO
    private var pendingFingerprint: String? = null
    private var idempotencyKey: String? = null
    private var hostResult: ApiResult<SaleResponse>? = null

    fun start(amountText: String, tipText: String) {
        if (transactionJob?.isActive == true) return
        val amount = amountText.toBigDecimalOrNull()
        val tip = tipText.ifBlank { "0" }.toBigDecimalOrNull()
        if (amount == null || tip == null || amount.signum() <= 0 || tip.signum() < 0 ||
            amount.scale() > 0 || tip.scale() > 0 || amount.toPlainString().length > 12) {
            mutableState.value = CardPaymentUiState(stage = PaymentStage.FAILURE, message = "Nominal atau tip tidak valid")
            return
        }
        pendingAmount = amount
        pendingTip = tip
        val fingerprint = "$amount|$tip"
        if (pendingFingerprint != fingerprint) {
            pendingFingerprint = fingerprint
            idempotencyKey = SaleRepository.newIdempotencyKey()
        }
        transactionJob = viewModelScope.launch { connectAndStart(amount, tip) }
    }

    fun selectReader(id: String) {
        val pairable = reader as? PairableCardReader ?: return
        val amount = pendingAmount ?: return
        if (transactionJob?.isActive == true) return
        transactionJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(stage = PaymentStage.CONNECTING, message = "Menghubungkan reader…")
            if (!pairable.selectDevice(id)) {
                fail("Reader tidak dapat dihubungkan")
                return@launch
            }
            transact(amount, pendingTip)
        }
    }

    fun retry() {
        val amount = pendingAmount
        if (amount == null) reset() else start(amount.toPlainString(), pendingTip.toPlainString())
    }

    fun reset() {
        reader?.cancel()
        transactionJob?.cancel()
        transactionJob = null
        hostResult = null
        mutableState.value = CardPaymentUiState()
    }

    fun cancel() {
        reader?.cancel()
        transactionJob?.cancel()
        transactionJob = null
        mutableState.value = CardPaymentUiState(stage = PaymentStage.ENTRY)
    }

    private suspend fun connectAndStart(amount: BigDecimal, tip: BigDecimal) {
        mutableState.value = CardPaymentUiState(
            stage = PaymentStage.CONNECTING,
            amount = amount,
            tip = tip,
            message = "Menyiapkan perangkat pembayaran…",
        )
        try {
            val selected = dependencies.cardReader().also { reader = it }
            if (selected is PairableCardReader) {
                mutableState.value = mutableState.value.copy(externalReader = true)
                val devices = selected.pairedDevices()
                when (devices.size) {
                    0 -> return fail("Reader mPOS tidak ditemukan")
                    1 -> if (!selected.selectDevice(devices.single().id)) return fail("Reader tidak dapat dihubungkan")
                    else -> {
                        mutableState.value = mutableState.value.copy(
                            stage = PaymentStage.SELECT_READER,
                            message = "Pilih reader kartu",
                            readers = devices,
                        )
                        return
                    }
                }
            }
            transact(amount, tip)
        } catch (failure: Exception) {
            fail(failure.message ?: "Perangkat pembayaran tidak tersedia")
        }
    }

    private suspend fun transact(amount: BigDecimal, tip: BigDecimal) {
        hostResult = null
        val result = requireNotNull(reader).transact(
            CardTransactionRequest(amount.longValueExact()),
            object : CardTransactionListener {
                override fun onEvent(event: CardTransactionEvent) {
                    val next = when (event) {
                        CardTransactionEvent.Connecting -> PaymentStage.CONNECTING to "Menghubungkan SDK vendor…"
                        CardTransactionEvent.WaitingForCard -> PaymentStage.WAITING_CARD to "Masukkan atau tempel kartu Anda"
                        is CardTransactionEvent.CardDetected -> PaymentStage.WAITING_CARD to "Kartu terbaca. Jangan lepaskan kartu."
                        CardTransactionEvent.PinRequested -> PaymentStage.PIN to "Masukkan PIN pada pinpad"
                        is CardTransactionEvent.PinProgress -> PaymentStage.PIN to "•".repeat(event.length)
                        CardTransactionEvent.Authorizing -> PaymentStage.AUTHORIZING to "Memproses pembayaran…"
                        CardTransactionEvent.Completing -> PaymentStage.COMPLETING to "Menyelesaikan transaksi EMV…"
                    }
                    mutableState.value = mutableState.value.copy(
                        stage = next.first,
                        message = next.second,
                        pinLength = (event as? CardTransactionEvent.PinProgress)?.length ?: mutableState.value.pinLength,
                    )
                }

                override suspend fun authorize(card: CardTransactionData): CardAuthorization {
                    return try {
                        val response = dependencies.authorize(
                            card, amount, tip, requireNotNull(idempotencyKey),
                        ).also { hostResult = it }
                        when (response) {
                            is ApiResult.Success -> CardAuthorization(
                                approved = response.data.status.equals("APPROVED", true) || response.data.responseCode == "00",
                                responseCode = response.data.responseCode?.takeIf { it.length == 2 }
                                    ?: if (response.data.status.equals("APPROVED", true)) "00" else "05",
                            )
                            is ApiResult.Failure -> CardAuthorization(false, response.error.code.takeIf { it.length == 2 } ?: "96")
                        }
                    } finally {
                        card.pinBlock?.fill(0)
                    }
                }
            },
        )
        when (result) {
            is CardReadResult.Success -> renderHostResult(result.authorization)
            is CardReadResult.Failure -> fail(result.reason)
            CardReadResult.Cancelled -> mutableState.value = CardPaymentUiState(stage = PaymentStage.ENTRY)
        }
    }

    private fun renderHostResult(authorization: CardAuthorization) {
        when (val response = hostResult) {
            is ApiResult.Success -> {
                if (authorization.approved) {
                    pendingFingerprint = null
                    idempotencyKey = null
                }
                mutableState.value = mutableState.value.copy(
                    stage = if (authorization.approved) PaymentStage.SUCCESS else PaymentStage.FAILURE,
                    message = if (authorization.approved) "Pembayaran berhasil" else "Pembayaran ditolak",
                    result = response.data,
                )
            }
            is ApiResult.Failure -> fail("${response.error.code}: ${response.error.message}")
            null -> fail("Host tidak mengembalikan hasil transaksi")
        }
    }

    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(stage = PaymentStage.FAILURE, message = message)
    }

    override fun onCleared() {
        reader?.cancel()
        super.onCleared()
    }

    class Factory(private val dependencies: CardPaymentDependencies) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CardPaymentViewModel(dependencies) as T
    }
}
