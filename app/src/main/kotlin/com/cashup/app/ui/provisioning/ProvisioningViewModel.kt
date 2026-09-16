package com.cashup.app.ui.provisioning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cashup.app.di.AppContainer
import com.cashup.provisioning.domain.ProvisioningOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Menerima lambda, bukan [AppContainer], supaya seluruh mesin state bisa diuji
 * di JVM tanpa Android sama sekali. [Factory] yang menyambungkannya ke container
 * sungguhan.
 */
class ProvisioningViewModel(
    private val provisionDevice: suspend (String) -> ProvisioningOutcome,
) : ViewModel() {

    private val _state = MutableStateFlow<ProvisioningUiState>(ProvisioningUiState.Idle)
    val state: StateFlow<ProvisioningUiState> = _state.asStateFlow()

    /**
     * Guard re-entrancy, dan ini bukan kehati-hatian berlebih: scanner QR
     * mendeteksi frame yang sama berkali-kali dalam sepersekian detik, sebelum
     * hasil percobaan pertama sempat masuk state. Tanpa guard ini kode
     * sekali-pakai ditembak dua kali — percobaan pertama **berhasil**, yang
     * kedua ditolak karena kodenya sudah terpakai, dan penolakan itulah yang
     * dilihat teknisi.
     *
     * Flag di sini, bukan `isEnabled = false` di UI: yang terakhir baru berlaku
     * setelah render berikutnya, dan frame kedua sudah datang sebelum itu.
     */
    private var inFlight = false

    fun provision(challengeCode: String) {
        if (inFlight) return
        inFlight = true
        _state.value = ProvisioningUiState.Processing

        viewModelScope.launch {
            try {
                _state.value = when (val outcome = provisionDevice(challengeCode)) {
                    is ProvisioningOutcome.Success -> ProvisioningUiState.Success(
                        serialNumber = outcome.serialNumber,
                        orderId = outcome.orderId,
                        installed = outcome.installed,
                        journalText = outcome.journalText,
                    )
                    is ProvisioningOutcome.Failure -> ProvisioningUiState.Failure(
                        code = outcome.code,
                        message = outcome.message,
                        journalText = outcome.journalText,
                    )
                }
            } finally {
                inFlight = false
            }
        }
    }

    fun scanning() {
        if (!inFlight) _state.value = ProvisioningUiState.Scanning
    }

    fun reset() {
        if (!inFlight) _state.value = ProvisioningUiState.Idle
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProvisioningViewModel { code -> container.provisionDeviceUseCase().invoke(code) } as T
    }
}
