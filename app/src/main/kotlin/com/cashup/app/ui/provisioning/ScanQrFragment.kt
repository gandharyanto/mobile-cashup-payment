package com.cashup.app.ui.provisioning

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cashup.app.CashupApp
import com.cashup.app.R
import com.cashup.app.databinding.FragmentScanQrBinding
import com.cashup.app.scan.CameraQrScanner
import com.cashup.app.scan.QrScanSource
import kotlinx.coroutines.launch

class ScanQrFragment : Fragment(R.layout.fragment_scan_qr) {

    // activityViewModels, bukan viewModels: Scan, Processing, dan Result adalah
    // tujuan bersebelahan di nav graph, bukan parent-child. ViewModel ber-scope
    // fragment akan memberi masing-masing instance sendiri, dan Processing akan
    // menunggu state yang tidak pernah berubah.
    private val viewModel: ProvisioningViewModel by activityViewModels {
        ProvisioningViewModel.Factory((requireActivity().application as CashupApp).container)
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startScanning() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentScanQrBinding.bind(view)
        val container = (requireActivity().application as CashupApp).container
        viewLifecycleOwner.lifecycleScope.launch {
            val serial = runCatching { container.serialNumbers.serialNumber() }.getOrNull()
            binding.serialNumber.text = if (serial.isNullOrBlank()) getString(R.string.serial_unavailable)
                else getString(R.string.serial_value, serial)
        }

        binding.manualSubmit.setOnClickListener {
            viewModel.provision(binding.manualCode.text.toString())
        }

        binding.decryptRsa.setOnClickListener {
            val ciphertext = binding.rsaCiphertext.text.toString()
            if (ciphertext.isBlank()) {
                binding.decryptResult.text = "Ciphertext wajib diisi"
            } else {
                runCatching { container.decryptRsaBase64(ciphertext).toString(Charsets.UTF_8) }
                    .onSuccess { binding.decryptResult.text = "Plaintext: $it" }
                    .onFailure { binding.decryptResult.text = "Decrypt gagal: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
        binding.signEd25519.setOnClickListener {
            val message = binding.ed25519Message.text.toString()
            if (message.isBlank()) {
                binding.signatureResult.text = "Pesan wajib diisi"
            } else {
                runCatching { container.signEd25519Base64(message) }
                    .onSuccess { binding.signatureResult.text = "Signature Base64: $it" }
                    .onFailure { binding.signatureResult.text = "Signing gagal: ${it.message ?: it.javaClass.simpleName}" }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state is ProvisioningUiState.Processing) {
                        findNavController().navigate(R.id.to_processing)
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startScanning()
        } else {
            // Izin kamera diminta tanpa syarat karena scanner vendor belum tentu
            // ada; kalau ternyata ada, kamera tidak akan pernah dinyalakan --
            // QrScanSource membuatnya lewat lambda.
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScanning() {
        val container = (requireActivity().application as CashupApp).container
        val binding = FragmentScanQrBinding.bind(requireView())
        viewLifecycleOwner.lifecycleScope.launch {
            val source = QrScanSource(container.vendorScanner) {
                CameraQrScanner(binding.preview, viewLifecycleOwner)
            }
            val code = source.scan(SCAN_TIMEOUT_MILLIS)
            if (code != null) viewModel.provision(code)
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MILLIS = 120_000L
    }
}
