package com.cashup.app.ui.provisioning

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.cashup.app.BuildConfig
import com.cashup.app.CashupApp
import com.cashup.app.R
import com.cashup.app.databinding.FragmentResultBinding

class ResultFragment : Fragment(R.layout.fragment_result) {

    private val viewModel: ProvisioningViewModel by activityViewModels {
        ProvisioningViewModel.Factory((requireActivity().application as CashupApp).container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentResultBinding.bind(view)

        when (val state = viewModel.state.value) {
            is ProvisioningUiState.Success -> {
                binding.title.setText(if (state.identityOnly) R.string.result_identity_refreshed else R.string.result_success_title)
                binding.detail.text = buildString {
                    append(getString(R.string.result_serial, state.serialNumber))
                    if (!state.identityOnly) {
                        append('\n')
                        append(getString(R.string.result_order, state.orderId))
                    }
                }
                // Perlindungan tiap purpose ditampilkan, tidak disembunyikan:
                // tidak semua key mendapat modul aman vendor (spec §4.3), dan
                // itu harus terlihat teknisi, bukan hanya tercatat di log.
                binding.backings.text = if (state.identityOnly) getString(R.string.result_keys_unchanged)
                    else state.installed.joinToString("\n") { "${it.purpose}: ${it.backing.name}" }
                showJournal(binding, state.journalText)
                binding.action.setText(R.string.result_continue)
                binding.action.setOnClickListener {
                    val container = (requireActivity().application as CashupApp).container
                    if (container.isProvisioned()) findNavController().navigate(R.id.to_sale)
                    else requireActivity().finish()
                }
            }

            is ProvisioningUiState.Failure -> {
                binding.title.setText(R.string.result_failure_title)
                val hint = hintFor(state.code)
                binding.detail.text = if (hint != 0) getString(hint) else state.message
                binding.backings.text = state.code
                showJournal(binding, state.journalText)
                binding.action.setText(R.string.result_retry)
                binding.action.setOnClickListener {
                    viewModel.reset()
                    findNavController().navigate(R.id.to_scan)
                }
            }

            else -> findNavController().navigate(R.id.to_scan)
        }
    }

    /**
     * SEMENTARA — dicabut bersama package `audit/` (Task 10).
     *
     * Teks jurnal hanya ditulis ke view kalau view itu memang ditampilkan.
     * `visibility = GONE` menyembunyikan dari mata, bukan dari hierarki view:
     * teks yang ditulis tetap hidup di `TextView`, terbaca lewat dump
     * hierarki, layanan aksesibilitas, dan snapshot proses. Jurnal membawa
     * orderId dan token aktivasi terpotong, jadi di build rilis — tempat
     * panel ini selalu GONE — jurnalnya tidak boleh sampai ke view sama
     * sekali.
     */
    private fun showJournal(binding: FragmentResultBinding, text: String) {
        val visible = BuildConfig.PROVISIONING_JOURNAL && text.isNotBlank()
        binding.journalLabel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.journal.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) binding.journal.text = text
    }
}
