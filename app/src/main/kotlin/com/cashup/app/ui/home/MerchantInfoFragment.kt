package com.cashup.app.ui.home

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.cashup.app.CashupApp
import com.cashup.app.R

internal fun formatDeviceInfo(
    deviceId: String?,
    serialNumber: String?,
    provisioned: Boolean,
    idLabel: String,
    serialLabel: String,
    statusLabel: String,
    provisionedText: String,
    notProvisionedText: String,
    unknownText: String,
): String {
    val status = if (provisioned) provisionedText else notProvisionedText
    return "$idLabel: ${deviceId ?: unknownText}\n$serialLabel: ${serialNumber ?: unknownText}\n$statusLabel: $status"
}

class MerchantInfoFragment : Fragment(R.layout.fragment_merchant_info_dummy) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = (requireActivity().application as CashupApp).container
        val text = formatDeviceInfo(
            deviceId = container.activeDeviceId(),
            serialNumber = container.storedSerialNumber(),
            provisioned = container.isProvisioned(),
            idLabel = getString(R.string.device_info_id_label),
            serialLabel = getString(R.string.device_info_serial_label),
            statusLabel = getString(R.string.device_info_status_label),
            provisionedText = getString(R.string.device_info_status_provisioned),
            notProvisionedText = getString(R.string.device_info_status_not_provisioned),
            unknownText = getString(R.string.device_info_unknown),
        )
        view.findViewById<TextView>(R.id.tvDeviceInfo).text = text
    }
}
