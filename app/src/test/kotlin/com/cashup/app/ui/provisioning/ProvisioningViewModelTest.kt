package com.cashup.app.ui.provisioning

import com.cashup.devicesdk.KeyBacking
import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.provisioning.audit.ProvisioningStep
import com.cashup.provisioning.domain.ProvisioningOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProvisioningViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun successOutcome() = ProvisioningOutcome.Success(
        serialNumber = "PAX-A920-0012938",
        orderId = "o-1",
        installed = listOf(KeyInstallOutcome("PIN", KeyBacking.VENDOR_SECURE_MODULE)),
        journalText = "OK REDEEM",
    )

    @Test
    fun `starts idle`() = runTest {
        val viewModel = ProvisioningViewModel { _, _ -> successOutcome() }

        assertEquals(ProvisioningUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `moves through Processing to Success`() = runTest {
        var provisioned = 0
        val viewModel = ProvisioningViewModel { _, _ -> provisioned++; successOutcome() }

        viewModel.provision("ABCD-1234")
        assertEquals(
            ProvisioningUiState.Processing(ProvisioningStep.DETECT_DEVICE),
            viewModel.state.value,
        )

        advanceUntilIdle()
        val state = viewModel.state.value
        assertTrue(state.toString(), state is ProvisioningUiState.Success)
        assertEquals("PAX-A920-0012938", (state as ProvisioningUiState.Success).serialNumber)
        assertEquals(1, provisioned)
    }

    @Test
    fun `a second call while one is running is ignored`() = runTest {
        var provisioned = 0
        val viewModel = ProvisioningViewModel { _, _ -> provisioned++; successOutcome() }

        // Scanner QR mendeteksi frame yang sama berkali-kali dalam sepersekian
        // detik. Tanpa guard ini, kode sekali-pakai ditembak dua kali: yang
        // pertama berhasil, yang kedua ditolak -- dan penolakan itulah yang
        // dilihat teknisi.
        viewModel.provision("ABCD-1234")
        viewModel.provision("ABCD-1234")
        advanceUntilIdle()

        assertEquals(1, provisioned)
    }

    /**
     * Layar Processing tidak boleh cuma spinner: langkah yang dilaporkan use
     * case harus benar-benar sampai ke state. Sebelum ini `onStep` sudah
     * dirangkai di sepanjang ceremony tapi tidak tersambung ke apa pun.
     */
    @Test
    fun `every step reported by the use case reaches the state`() = runTest {
        val seen = mutableListOf<ProvisioningStep>()
        lateinit var viewModel: ProvisioningViewModel
        viewModel = ProvisioningViewModel { _, onStep ->
            listOf(
                ProvisioningStep.REDEEM,
                ProvisioningStep.DOWNLOAD_PACKAGE,
                ProvisioningStep.ACTIVATE,
            ).forEach { step ->
                onStep(step)
                seen += (viewModel.state.value as ProvisioningUiState.Processing).step
            }
            successOutcome()
        }

        viewModel.provision("ABCD-1234")
        advanceUntilIdle()

        assertEquals(
            listOf(
                ProvisioningStep.REDEEM,
                ProvisioningStep.DOWNLOAD_PACKAGE,
                ProvisioningStep.ACTIVATE,
            ),
            seen,
        )
        assertTrue(
            viewModel.state.value.toString(),
            viewModel.state.value is ProvisioningUiState.Success,
        )
    }

    @Test
    fun `a failure surfaces the backend code`() = runTest {
        val viewModel = ProvisioningViewModel { _, _ ->
            ProvisioningOutcome.Failure("PROVISIONING_TOKEN_INVALID", "Kedaluwarsa", "FAILED REDEEM")
        }

        viewModel.provision("ABCD-1234")
        advanceUntilIdle()

        val state = viewModel.state.value as ProvisioningUiState.Failure
        assertEquals("PROVISIONING_TOKEN_INVALID", state.code)
    }

    @Test
    fun `reset returns to idle so the technician can retry`() = runTest {
        val viewModel = ProvisioningViewModel { _, _ ->
            ProvisioningOutcome.Failure("X", "y", "")
        }

        viewModel.provision("ABCD-1234")
        advanceUntilIdle()
        viewModel.reset()

        assertEquals(ProvisioningUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `a retry after a failure is allowed`() = runTest {
        var attempts = 0
        val viewModel = ProvisioningViewModel { _, _ ->
            attempts++
            ProvisioningOutcome.Failure("X", "y", "")
        }

        viewModel.provision("ABCD-1234")
        advanceUntilIdle()
        viewModel.reset()
        viewModel.provision("ABCD-1234")
        advanceUntilIdle()

        assertEquals(2, attempts)
    }
}
