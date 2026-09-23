package com.cashup.app.ui.home

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cashup.app.R
import com.cashup.app.databinding.FragmentHomeBinding

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var binding: FragmentHomeBinding? = null

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
        super.onDestroyView()
        binding = null
    }
}

/** Pure toggle rule: which of Simple/POS should be visually selected. Returns (simpleSelected, posSelected). */
internal fun modeSelectionState(pos: Boolean): Pair<Boolean, Boolean> = Pair(!pos, pos)
