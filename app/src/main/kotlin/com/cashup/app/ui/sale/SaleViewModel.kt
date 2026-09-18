package com.cashup.app.ui.sale

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cashup.app.di.AppContainer
import com.cashup.app.BuildConfig
import com.cashup.cdcp.CardDefinition
import com.cashup.cdcp.SaleRepository
import com.cashup.common.network.ApiResult
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SaleUiState(val loading: Boolean = false, val result: String = "")

class SaleViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow(SaleUiState())
    val state: StateFlow<SaleUiState> = mutableState
    private var pendingFingerprint: String? = null
    private var pendingKey: String? = null

    fun pay(card: CardDefinition, amountText: String, tipText: String, pinText: String) {
        if (mutableState.value.loading) return
        val amount = amountText.toBigDecimalOrNull()
        val tip = tipText.ifBlank { "0" }.toBigDecimalOrNull()
        if (amount == null || tip == null || amount.signum() <= 0 || tip.signum() < 0 ||
            amount.scale() > 0 || tip.scale() > 0) {
            mutableState.value = SaleUiState(result = "Nominal atau tip tidak valid")
            return
        }
        val pin = pinText.takeIf { it.isNotBlank() }
        if (pin != null && (pin.length !in 4..12 || !pin.all(Char::isDigit))) {
            mutableState.value = SaleUiState(result = "PIN harus 4–12 digit")
            return
        }
        val fingerprint = "${card.pan}|$amount|$tip|$pin"
        if (pendingFingerprint != fingerprint) {
            pendingFingerprint = fingerprint
            pendingKey = SaleRepository.newIdempotencyKey()
        }
        mutableState.value = SaleUiState(loading = true, result = "Memproses pembayaran…")
        viewModelScope.launch {
            try {
                val deviceId = requireNotNull(container.activeDeviceId()) { "Identitas device belum tersedia" }
                val version = requireNotNull(container.activeKeySetVersion()) { "Key DUKPT belum aktif" }
                if (BuildConfig.DEBUG) Log.d("CashupSale", "Preparing sale request")
                val response = container.saleRepository.sale(deviceId, version, card, amount, tip, pin, pendingKey!!)
                if (BuildConfig.DEBUG) Log.d("CashupSale", "Sale result: ${response.javaClass.simpleName}")
                mutableState.value = when (response) {
                    is ApiResult.Success -> {
                        pendingFingerprint = null; pendingKey = null
                        SaleUiState(result = "${response.data.status}\nTransaksi: ${response.data.transactionId}" +
                            (response.data.approvalCode?.let { "\nApproval: $it" } ?: "") +
                            (response.data.maskedPan?.let { "\nKartu: $it" } ?: ""))
                    }
                    is ApiResult.Failure -> SaleUiState(result = "${response.error.code}: ${response.error.message}")
                }
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.e("CashupSale", "Sale failed before or during HTTP", failure)
                mutableState.value = SaleUiState(result = failure.message ?: "Pembayaran gagal")
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SaleViewModel(container) as T
    }
}
