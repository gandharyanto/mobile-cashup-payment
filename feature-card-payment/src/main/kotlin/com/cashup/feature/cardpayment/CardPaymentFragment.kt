package com.cashup.feature.cardpayment

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cashup.feature.cardpayment.databinding.FragmentCardPaymentBinding
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CardPaymentFragment : Fragment(R.layout.fragment_card_payment) {
    private val dependencies: CardPaymentDependencies by lazy {
        (requireActivity().application as CardPaymentDependenciesOwner).cardPaymentDependencies
    }
    private val viewModel: CardPaymentViewModel by viewModels { CardPaymentViewModel.Factory(dependencies) }
    private var countdown: Job? = null
    private var readerDialog: AlertDialog? = null
    private val calculator = SimpleCalculator()
    private var previousStatusBarColor: Int? = null
    private var previousLightStatusBar: Boolean? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCardPaymentBinding.bind(view)
        val window = requireActivity().window
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        previousStatusBarColor = window.statusBarColor
        previousLightStatusBar = insetsController.isAppearanceLightStatusBars
        window.statusBarColor = Color.rgb(0, 77, 64)
        insetsController.isAppearanceLightStatusBars = false
        binding.cardAnimation.setImageResource(cardAnimationForModel(Build.MODEL))
        val simpleKeys = mapOf(
            binding.simpleKey1 to "1", binding.simpleKey2 to "2", binding.simpleKey3 to "3",
            binding.simpleKey4 to "4", binding.simpleKey5 to "5", binding.simpleKey6 to "6",
            binding.simpleKey7 to "7", binding.simpleKey8 to "8", binding.simpleKey9 to "9",
            binding.simpleKey000 to "000", binding.simpleKey0 to "0",
        )
        val calculatorKeys = mapOf(
            binding.keyClear to "C", binding.keyPercent to "%", binding.keyDivide to "/",
            binding.keyMultiply to "x", binding.key1 to "1", binding.key2 to "2",
            binding.key3 to "3", binding.keyMinus to "-", binding.key4 to "4",
            binding.key5 to "5", binding.key6 to "6", binding.keyPlus to "+",
            binding.key7 to "7", binding.key8 to "8", binding.key9 to "9",
            binding.key0 to "0", binding.key000 to "000", binding.keyEquals to "=",
            binding.keyBackspace to "←",
        )
        simpleKeys.forEach { (button, key) ->
            button.setOnClickListener { renderCalculator(binding, calculator.press(key)) }
        }
        calculatorKeys.forEach { (button, key) ->
            button.setOnClickListener { renderCalculator(binding, calculator.press(key)) }
        }
        binding.keyBackspace.setOnClickListener { renderCalculator(binding, calculator.press("←")) }
        binding.amountBackspace.setOnClickListener { renderCalculator(binding, calculator.press("←")) }
        binding.showCalculator.setOnClickListener { showCalculator(binding, true) }
        binding.hideCalculator.setOnClickListener { showCalculator(binding, false) }
        binding.payQris.setOnClickListener { showUnavailable() }
        binding.otherPayment.setOnClickListener { showUnavailable() }
        renderCalculator(binding, calculator.current())
        binding.pay.setOnClickListener {
            val amount = calculator.current().amount
            if (amount <= 0) {
                Toast.makeText(requireContext(), R.string.card_payment_invalid_amount, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.start(amount.toString(), "0")
            }
        }
        binding.secondaryAction.setOnClickListener {
            when (viewModel.state.value.stage) {
                PaymentStage.FAILURE -> viewModel.retry()
                PaymentStage.SUCCESS -> {
                    calculator.press("C")
                    renderCalculator(binding, calculator.current())
                    viewModel.reset()
                }
                else -> viewModel.reset()
            }
        }
        val back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.state.value.stage == PaymentStage.ENTRY) {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                } else {
                    viewModel.cancel()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        binding.back.setOnClickListener { back.handleOnBackPressed() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(binding, it) }
            }
        }
    }

    private fun render(binding: FragmentCardPaymentBinding, state: CardPaymentUiState) {
        val entry = state.stage == PaymentStage.ENTRY
        binding.calculatorScreen.isVisible = entry
        binding.paymentScreen.isVisible = !entry
        binding.amountValue.text = state.amount?.rupiah().orEmpty()
        binding.prompt.text = when (state.stage) {
            PaymentStage.CONNECTING, PaymentStage.SELECT_READER, PaymentStage.WAITING_CARD -> getString(
                if (state.externalReader) R.string.card_payment_insert_mpos
                else R.string.card_payment_insert_or_tap,
            )
            else -> state.message
        }
        binding.remaining.isVisible = state.stage == PaymentStage.WAITING_CARD
        binding.secondaryAction.isVisible = !entry
        binding.secondaryAction.text = when (state.stage) {
            PaymentStage.FAILURE -> getString(R.string.card_payment_retry)
            PaymentStage.SUCCESS -> getString(R.string.card_payment_new)
            else -> getString(R.string.card_payment_change)
        }
        if (state.stage == PaymentStage.WAITING_CARD) startCountdown(binding) else stopCountdown()
        if (state.stage == PaymentStage.SELECT_READER && readerDialog == null) {
            readerDialog = AlertDialog.Builder(requireContext())
                .setTitle(state.message)
                .setItems(state.readers.map { it.name }.toTypedArray()) { _, which ->
                    readerDialog = null
                    viewModel.selectReader(state.readers[which].id)
                }
                .setOnCancelListener { readerDialog = null; viewModel.cancel() }
                .show()
        }
    }

    private fun renderCalculator(binding: FragmentCardPaymentBinding, display: CalculatorDisplay) {
        binding.calculatorExpression.text = if (display.expression == "0") {
            getString(R.string.card_payment_amount_hint)
        } else display.expression
        binding.calculatorTotal.text = display.amount.rupiah()
        binding.calculatorTotal.setTextColor(
            requireContext().getColor(
                if (display.amount == 0L) R.color.card_payment_amount_empty else R.color.card_payment_navy,
            ),
        )
        display.error?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
    }

    private fun showCalculator(binding: FragmentCardPaymentBinding, visible: Boolean) {
        binding.simpleKeypad.isVisible = !visible
        binding.calculatorKeypad.isVisible = visible
    }

    private fun showUnavailable() {
        Toast.makeText(requireContext(), R.string.card_payment_not_available, Toast.LENGTH_SHORT).show()
    }

    private fun cardAnimationForModel(model: String): Int = when {
        model.equals("T6", ignoreCase = true) -> R.drawable.anim_cdcp_t6
        model.equals("T1", ignoreCase = true) -> R.drawable.anim_cdcp_t1
        else -> R.drawable.card_payment_anfu
    }

    private fun startCountdown(binding: FragmentCardPaymentBinding) {
        if (countdown?.isActive == true) return
        countdown = viewLifecycleOwner.lifecycleScope.launch {
            for (seconds in 60 downTo 0) {
                val text = getString(R.string.card_payment_remaining, seconds / 60, seconds % 60)
                val time = text.takeLast(5)
                binding.remaining.text = SpannableString(text).apply {
                    setSpan(
                        StyleSpan(Typeface.BOLD), text.length - time.length, text.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                delay(1_000)
            }
        }
    }

    private fun stopCountdown() {
        countdown?.cancel()
        countdown = null
    }

    override fun onDestroyView() {
        readerDialog?.dismiss()
        readerDialog = null
        stopCountdown()
        val window = activity?.window
        if (window != null) {
            previousStatusBarColor?.let { window.statusBarColor = it }
            previousLightStatusBar?.let {
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = it
            }
        }
        super.onDestroyView()
    }

    private fun BigDecimal.rupiah(): String =
        "Rp " + NumberFormat.getIntegerInstance(Locale("id", "ID")).format(this)

    private fun Long.rupiah(): String =
        "Rp " + NumberFormat.getIntegerInstance(Locale("id", "ID")).format(this)
}
