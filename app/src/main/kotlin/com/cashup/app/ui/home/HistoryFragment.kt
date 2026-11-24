package com.cashup.app.ui.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.cashup.app.R
import com.cashup.feature.cardpayment.CardTransactionListFragment
import com.cashup.feature.qris.QrisHistoryFragment
import com.google.android.material.button.MaterialButtonToggleGroup

/** Riwayat dengan dua pilihan: QRIS dan Kartu. Isi tiap pilihan adalah fragment dari SDK. */
class HistoryFragment : Fragment(R.layout.fragment_history_dummy) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toggle = view.findViewById<MaterialButtonToggleGroup>(R.id.historyToggle)
        // Setelah rotasi, child fragment dipulihkan oleh FragmentManager; hanya tab yang diselaraskan.
        val current = childFragmentManager.findFragmentById(R.id.historyContainer)
        toggle.check(if (current is CardTransactionListFragment) R.id.historyTabCard else R.id.historyTabQris)
        if (current == null) show(QrisHistoryFragment())
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val showingCard = childFragmentManager.findFragmentById(R.id.historyContainer) is CardTransactionListFragment
            when {
                checkedId == R.id.historyTabCard && !showingCard -> show(CardTransactionListFragment())
                checkedId == R.id.historyTabQris && showingCard -> show(QrisHistoryFragment())
            }
        }
    }

    private fun show(fragment: Fragment) {
        childFragmentManager.beginTransaction()
            .replace(R.id.historyContainer, fragment)
            .commit()
    }
}
