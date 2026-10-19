package com.cashup.app.ui.provisioning

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.cashup.app.CashupApp
import com.cashup.app.R
import com.cashup.reader.BluetoothPermissions
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Splash: menentukan ke mana device pergi saat dibuka.
 *
 * Belum terprovisioning → Scan QR. Sudah → Home. Kalau ada reader mPOS yang diingat, splash menahan sampai
 * reader itu tersambung dan siap (connect Bluetooth, key, AID/CAPK -- sekitar satu menit), supaya layar
 * pembayaran tidak perlu menunggu. Reader yang mati atau di luar jangkauan tidak menahan app selamanya:
 * setelah [WARM_UP_TIMEOUT_MILLIS] splash lanjut dan layar pembayaran mencoba lagi.
 */
class GateFragment : Fragment(R.layout.fragment_gate) {

    private val container get() = (requireActivity().application as CashupApp).container

    // Hasil izin tidak memblokir: tanpa izin, reader disambungkan nanti saat pembayaran.
    private val requestBluetooth = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.all { it }) prepareReader() else openSale()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!container.isProvisioned()) {
            navigate(R.id.to_scan)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            if (container.readerSession.rememberedReader() == null) {
                openSale()
                return@launch
            }
            val missing = missingBluetoothPermissions()
            if (missing.isEmpty()) prepareReader() else requestBluetooth.launch(missing)
        }
    }

    private fun prepareReader() {
        val view = view ?: return
        view.findViewById<View>(R.id.readerPreparing).visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            // await dibatalkan oleh timeout, pekerjaan warm-up-nya sendiri tetap jalan di scope aplikasi.
            withTimeoutOrNull(WARM_UP_TIMEOUT_MILLIS) { container.readerSession.prepareInBackground().await() }
            openSale()
        }
    }

    private fun openSale() = navigate(R.id.to_sale)

    private fun navigate(action: Int) {
        if (!isAdded || view == null) return
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.gateFragment) navController.navigate(action)
    }

    private fun missingBluetoothPermissions(): Array<String> = BluetoothPermissions.missing(requireContext())

    private companion object {
        const val WARM_UP_TIMEOUT_MILLIS = 90_000L
    }
}
