package com.cashup.feature.qris

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cashup.feature.qris.databinding.FragmentQrisBinding
import com.google.zxing.common.BitMatrix
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrisFragment : Fragment(R.layout.fragment_qris) {
    private val dependencies: QrisDependencies by lazy {
        (requireActivity().application as QrisDependenciesOwner).qrisDependencies
    }
    private val viewModel: QrisViewModel by viewModels {
        QrisViewModel.Factory(dependencies, requireArguments().getLong(ARG_AMOUNT))
    }
    private var countdown: Job? = null
    private var appDialog: AlertDialog? = null
    private var renderedQr: String? = null
    private var qrExpired = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentQrisBinding.bind(view)
        renderedQr = null
        qrExpired = false
        binding.back.setOnClickListener { findNavController().popBackStack() }
        binding.primaryAction.setOnClickListener {
            if (viewModel.state.value.stage == QrisStage.PAID) findNavController().popBackStack()
            else viewModel.retry()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(binding, it) }
            }
        }
    }

    override fun onDestroyView() {
        appDialog?.dismiss()
        appDialog = null
        countdown?.cancel()
        super.onDestroyView()
    }

    private fun render(binding: FragmentQrisBinding, state: QrisUiState) {
        val stage = state.stage
        binding.amountValue.text = state.amount.rupiah()
        binding.progress.isVisible = stage == QrisStage.LOADING_APPS || stage == QrisStage.GENERATING
        binding.qrImage.isVisible = stage == QrisStage.SHOWING_QR && !qrExpired
        binding.countdown.isVisible = stage == QrisStage.SHOWING_QR
        binding.details.isVisible = stage == QrisStage.PAID
        binding.primaryAction.isVisible = stage == QrisStage.PAID || stage == QrisStage.EXPIRED || stage == QrisStage.FAILED
        binding.primaryAction.setText(if (stage == QrisStage.PAID) R.string.qris_done else R.string.qris_new_qr)

        binding.statusText.setTextColor(requireContext().getColor(when (stage) {
            QrisStage.PAID -> R.color.qris_ok
            QrisStage.EXPIRED, QrisStage.FAILED -> R.color.qris_error
            else -> R.color.qris_navy
        }))
        binding.statusText.text = when (stage) {
            QrisStage.LOADING_APPS -> getString(R.string.qris_loading)
            QrisStage.GENERATING -> getString(R.string.qris_generating)
            QrisStage.SELECT_APP -> getString(R.string.qris_select_app)
            QrisStage.SHOWING_QR -> getString(R.string.qris_scan_hint)
            QrisStage.PAID -> getString(R.string.qris_paid)
            QrisStage.EXPIRED -> getString(R.string.qris_expired)
            QrisStage.FAILED -> failureText(state)
        }

        if (stage == QrisStage.SHOWING_QR) {
            state.qr?.let { showQr(binding, it.qrContent) }
            startCountdown(binding, state.qrDeadline)
        } else {
            countdown?.cancel()
        }
        if (stage == QrisStage.PAID) renderDetails(binding, state)
        if (stage == QrisStage.SELECT_APP && appDialog == null) showAppPicker(state)
    }

    private fun failureText(state: QrisUiState): String {
        val message = getString(state.messageRes ?: R.string.qris_error_generic)
        val code = state.errorCode?.let { "\n" + getString(R.string.qris_error_code_fmt, it) }.orEmpty()
        return message + code
    }

    private fun showAppPicker(state: QrisUiState) {
        appDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.qris_select_app)
            .setItems(state.apps.map { it.displayName }.toTypedArray()) { _, which ->
                appDialog = null
                viewModel.selectApp(state.apps[which].qrisHostId)
            }
            .setOnCancelListener { appDialog = null; findNavController().popBackStack() }
            .show()
    }

    private fun showQr(binding: FragmentQrisBinding, content: String) {
        if (renderedQr == content) return
        renderedQr = content
        qrExpired = false
        binding.qrImage.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) { QrCodeEncoder.encode(content, QR_SIZE_PX).toBitmap() }
            binding.qrImage.setImageBitmap(bitmap)
        }
    }

    private fun startCountdown(binding: FragmentQrisBinding, deadline: Long) {
        if (countdown?.isActive == true) return
        countdown = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val remaining = maxOf(0L, (deadline - monotonicMillis()) / 1_000L)
                binding.countdown.text = getString(R.string.qris_expires_in, "%d:%02d".format(remaining / 60, remaining % 60))
                if (remaining == 0L) {
                    // Jangan biarkan QR yang mungkin sudah kedaluwarsa di server tetap bisa dipindai.
                    qrExpired = true
                    binding.qrImage.isVisible = false
                    break
                }
                delay(1_000L)
            }
        }
    }

    private fun renderDetails(binding: FragmentQrisBinding, state: QrisUiState) {
        val result = state.result
        // Nilai null berarti tidak tersedia: sembunyikan barisnya, jangan tampilkan "null".
        binding.detailIssuer.bind(R.string.qris_detail_issuer, result?.issuerName ?: result?.pushDetail?.issuerName)
        binding.detailRrn.bind(R.string.qris_detail_rrn, result?.rrn ?: result?.pushDetail?.rrn)
        binding.detailApproval.bind(R.string.qris_detail_approval, result?.approvalCode ?: result?.pushDetail?.approvalCode)
    }

    private fun TextView.bind(format: Int, value: String?) {
        isVisible = !value.isNullOrBlank()
        if (isVisible) text = getString(format, value)
    }

    private fun BitMatrix.toBitmap(): Bitmap {
        val pixels = IntArray(width * height) { if (get(it % width, it / width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun BigDecimal.rupiah(): String =
        "Rp " + NumberFormat.getIntegerInstance(Locale("id", "ID")).format(this)

    companion object {
        /** Nama argumen di `nav_home.xml` dan deep-link `cashup://qris?amount={amount}`. */
        const val ARG_AMOUNT = "amount"
        private const val QR_SIZE_PX = 512
    }
}
