package com.cashup.app.ui.home

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.cashup.app.CashupApp
import com.cashup.app.R

internal fun formatDeviceInfo(deviceId: String?, serialNumber: String?, provisioned: Boolean): String {
    val status = if (provisioned) "Terprovisioning" else "Belum terprovisioning"
    return "Device ID: ${deviceId ?: "-"}\nSerial: ${serialNumber ?: "-"}\nStatus: $status"
}

class MerchantInfoFragment : Fragment(R.layout.fragment_merchant_info_dummy) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = (requireActivity().application as CashupApp).container
        val text = formatDeviceInfo(
            deviceId = container.activeDeviceId(),
            serialNumber = container.storedSerialNumber(),
            provisioned = container.isProvisioned(),
        )
        view.findViewById<TextView>(R.id.tvDeviceInfo).text = text
    }
}
