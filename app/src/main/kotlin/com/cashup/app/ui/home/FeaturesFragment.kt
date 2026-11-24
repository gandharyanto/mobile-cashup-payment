package com.cashup.app.ui.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.cashup.app.R
import com.cashup.feature.cardpayment.SettlementDialog

class FeaturesFragment : Fragment(R.layout.fragment_features_dummy) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnFeatureSettlement).setOnClickListener { showSettlement() }
    }

    private fun showSettlement() {
        // showNow commits synchronously, so a fast double tap does not stack two dialogs.
        if (childFragmentManager.findFragmentByTag(SettlementDialog.TAG) != null) return
        SettlementDialog().showNow(childFragmentManager, SettlementDialog.TAG)
    }
}
