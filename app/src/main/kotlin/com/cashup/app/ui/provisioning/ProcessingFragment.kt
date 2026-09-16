package com.cashup.app.ui.provisioning

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cashup.app.CashupApp
import com.cashup.app.R
import com.cashup.app.databinding.FragmentProcessingBinding
import kotlinx.coroutines.launch

class ProcessingFragment : Fragment(R.layout.fragment_processing) {

    private val viewModel: ProvisioningViewModel by activityViewModels {
        ProvisioningViewModel.Factory((requireActivity().application as CashupApp).container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentProcessingBinding.bind(view)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        // Nama langkah apa adanya: belum ada salinan teks per
                        // langkah, dan nama enum lebih berguna bagi teknisi
                        // daripada tidak ada keterangan sama sekali.
                        is ProvisioningUiState.Processing ->
                            binding.step.text = getString(R.string.processing_step, state.step.name)

                        is ProvisioningUiState.Success,
                        is ProvisioningUiState.Failure -> findNavController().navigate(R.id.to_result)

                        else -> Unit
                    }
                }
            }
        }
    }
}
