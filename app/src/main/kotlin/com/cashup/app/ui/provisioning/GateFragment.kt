package com.cashup.app.ui.provisioning

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.cashup.app.CashupApp
import com.cashup.app.R

/**
 * Layar tanpa tampilan: menentukan ke mana device pergi saat dibuka.
 *
 * Belum terprovisioning → Scan QR. Sudah → Home. Home belum ada di plan ini,
 * jadi sementara tetap ke Scan QR; yang penting keputusannya sudah punya satu
 * tempat, bukan tersebar di beberapa layar nanti.
 */
class GateFragment : Fragment(R.layout.fragment_gate) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = (requireActivity().application as CashupApp).container
        if (container.isProvisioned()) {
            // TODO(App Shell): arahkan ke Home begitu layar itu ada.
            findNavController().navigate(R.id.to_scan)
        } else {
            findNavController().navigate(R.id.to_scan)
        }
    }
}
