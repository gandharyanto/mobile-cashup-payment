package com.cashup.app.ui.sale

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cashup.app.CashupApp
import com.cashup.app.R
import com.cashup.app.databinding.FragmentSaleBinding
import com.cashup.cdcp.CardCatalog
import kotlinx.coroutines.launch

class SaleFragment : Fragment(R.layout.fragment_sale) {
    private val viewModel: SaleViewModel by viewModels {
        SaleViewModel.Factory((requireActivity().application as CashupApp).container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentSaleBinding.bind(view)
        val container = (requireActivity().application as CashupApp).container
        container.storedSerialNumber()?.let {
            binding.serialNumber.text = getString(R.string.serial_value, it)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val serial = runCatching { container.serialNumbers.serialNumber() }.getOrNull()
                ?: container.storedSerialNumber()
            binding.serialNumber.text = if (serial.isNullOrBlank()) getString(R.string.serial_unavailable)
                else getString(R.string.serial_value, serial)
        }
        val cards = CardCatalog.cards
        binding.card.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            cards.map { "${it.displayName}  •  ${it.maskedPan()}" })
        binding.pay.setOnClickListener {
            viewModel.pay(cards[binding.card.selectedItemPosition], binding.amount.text.toString(),
                binding.tip.text.toString(), binding.pin.text.toString())
        }
//        binding.decryptRsa.setOnClickListener {
//            val ciphertext = binding.rsaCiphertext.text.toString()
//            if (ciphertext.isBlank()) {
//                binding.decryptResult.text = "Ciphertext wajib diisi"
//            } else {
//                runCatching { container.decryptRsaBase64(ciphertext).toString(Charsets.UTF_8) }
//                    .onSuccess { binding.decryptResult.text = "Plaintext: $it" }
//                    .onFailure { binding.decryptResult.text = "Decrypt gagal: ${it.message ?: it.javaClass.simpleName}" }
//            }
//        }
        binding.provisionAgain.setOnClickListener { findNavController().navigate(R.id.to_scan) }
//        binding.decryptRsa.setOnClickListener {
//            val ciphertext = binding.rsaCiphertext.text.toString()
//            if (ciphertext.isBlank()) {
//                binding.decryptResult.text = "Ciphertext wajib diisi"
//            } else {
//                runCatching { container.decryptRsaBase64(ciphertext).toString(Charsets.UTF_8) }
//                    .onSuccess { binding.decryptResult.text = "Plaintext: $it" }
//                    .onFailure { binding.decryptResult.text = "Decrypt gagal: ${it.message ?: it.javaClass.simpleName}" }
//            }
//        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.pay.isEnabled = !state.loading
                    binding.result.text = state.result
                }
            }
        }
    }
}
