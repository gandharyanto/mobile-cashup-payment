package com.cashup.app.ui.provisioning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cashup.app.di.AppContainer
import com.cashup.provisioning.domain.ProvisioningStep
import com.cashup.provisioning.domain.ProvisioningOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Menerima lambda, bukan [AppContainer], supaya seluruh mesin state bisa diuji
 * di JVM tanpa Android sama sekali. [Factory] yang menyambungkannya ke container
 * sungguhan.
 *
 * Parameter kedua lambda adalah callback langkah: use case memanggilnya setiap
 * kali satu langkah ceremony dimulai, dan ViewModel meneruskannya ke
 * [ProvisioningUiState.Processing] supaya layar Processing menunjukkan kemajuan
 * yang sebenarnya, bukan spinner tanpa keterangan.
 */
class ProvisioningViewModel(
    private val provisionDevice: suspend (String, (ProvisioningStep) -> Unit) -> ProvisioningOutcome,
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
        _state.value = ProvisioningUiState.Processing(ProvisioningStep.DETECT_DEVICE)

        viewModelScope.launch {
            try {
                val report: (ProvisioningStep) -> Unit = { step ->
                    _state.value = ProvisioningUiState.Processing(step)
                }
                _state.value = when (val outcome = provisionDevice(challengeCode, report)) {
                    is ProvisioningOutcome.Success -> ProvisioningUiState.Success(
                        serialNumber = outcome.serialNumber,
                        orderId = outcome.orderId,
                        installed = outcome.installed,
                        journalText = outcome.journalText,
                        identityOnly = outcome.identityOnly,
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
            ProvisioningViewModel { code, onStep ->
                container.provisionDeviceUseCase().invoke(code, onStep)
            } as T
    }
}
