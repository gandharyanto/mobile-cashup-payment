package com.cashup.app.ui.home

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.cashup.app.R
import com.cashup.app.databinding.FragmentHomeBinding

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var binding: FragmentHomeBinding? = null
    private var destinationListener: NavController.OnDestinationChangedListener? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view)
        this.binding = binding

        val navHostFragment = childFragmentManager
            .findFragmentById(R.id.homeNavHost) as androidx.navigation.fragment.NavHostFragment
        androidx.navigation.ui.NavigationUI.setupWithNavController(
            binding.bottomNavigationView,
            navHostFragment.navController,
        )

        // Tab Transaksi/Kasir hanya milik layar Transaksi/Kasir; sembunyikan di fragment lain (Riwayat, QRIS, dst).
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            binding.toolbar.root.isVisible = showsModeSelector(destination.id)
        }
        navHostFragment.navController.addOnDestinationChangedListener(listener)
        destinationListener = listener

        binding.toolbar.btnSimple.setOnClickListener { selectMode(pos = false) }
        binding.toolbar.btnPos.setOnClickListener { selectMode(pos = true) }

        // Simple mode is active on first render. `android:selected` is not a valid layout XML
        // attribute on View, so the initial visual state must be set here rather than declaratively.
        binding.toolbar.btnSimple.isSelected = true
        binding.toolbar.btnPos.isSelected = false
    }

    private fun selectMode(pos: Boolean) {
        val toolbar = binding?.toolbar ?: return
        val (simpleSelected, posSelected) = modeSelectionState(pos)
        toolbar.btnSimple.isSelected = simpleSelected
        toolbar.btnPos.isSelected = posSelected
        if (pos) {
            Toast.makeText(requireContext(), getString(R.string.home_pos_mode_toast), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        destinationListener?.let { listener ->
            (childFragmentManager.findFragmentById(R.id.homeNavHost) as? NavHostFragment)
                ?.navController?.removeOnDestinationChangedListener(listener)
        }
        destinationListener = null
        super.onDestroyView()
        binding = null
    }
}

/**
 * Tujuan yang memperlihatkan tab Transaksi/Kasir. Mode Kasir belum punya fragment sendiri (hanya toast);
 * tambahkan id tujuannya di sini bila nanti ada.
 */
private val MODE_SELECTOR_DESTINATIONS = setOf(R.id.nav_home)

internal fun showsModeSelector(destinationId: Int): Boolean = destinationId in MODE_SELECTOR_DESTINATIONS

/** Pure toggle rule: which of Simple/POS should be visually selected. Returns (simpleSelected, posSelected). */
internal fun modeSelectionState(pos: Boolean): Pair<Boolean, Boolean> = Pair(!pos, pos)
