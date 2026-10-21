package com.github.libretube.jellyfin.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.databinding.FragmentJellyfinSearchBinding
import com.github.libretube.jellyfin.JellyfinRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search in the Jellyfin library: series, movies, collections and episodes.
 * The results show while the user types.
 */
class JellyfinSearchFragment : Fragment(R.layout.fragment_jellyfin_search) {
    private var _binding: FragmentJellyfinSearchBinding? = null
    private val binding get() = _binding!!

    private val adapter = JellyfinCardsAdapter(JellyfinCardStyle.ROW, showSeriesName = true) {
        search(immediately = true)
    }
    private var searchJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentJellyfinSearchBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        JellyfinSearchMenu.addTo(this, showSearchAction = false)

        binding.results.layoutManager = LinearLayoutManager(requireContext())
        binding.results.adapter = adapter

        binding.searchInput.doAfterTextChanged { search(immediately = false) }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            search(immediately = true)
            hideKeyboard()
            true
        }

        // a new search: open the keyboard
        if (savedInstanceState == null && binding.searchInput.text.isNullOrEmpty()) {
            binding.searchInput.requestFocus()
            insetsController()?.show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun search(immediately: Boolean) {
        val query = _binding?.searchInput?.text?.toString()?.trim() ?: return
        searchJob?.cancel()

        if (query.isEmpty()) {
            adapter.submitList(emptyList())
            binding.emptyText.isVisible = false
            return
        }

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            // wait until the user stops to type
            if (!immediately) delay(TYPING_DELAY_MS)
            binding.progress.isVisible = adapter.itemCount == 0
            try {
                val results = JellyfinRepository.search(query)
                adapter.submitList(results)
                binding.emptyText.isVisible = results.isEmpty()
            } catch (e: Exception) {
                showJellyfinError(e)
            } finally {
                _binding?.progress?.isVisible = false
            }
        }
    }

    private fun hideKeyboard() {
        insetsController()?.hide(WindowInsetsCompat.Type.ime())
    }

    private fun insetsController() = activity?.window?.let {
        WindowCompat.getInsetsController(it, binding.searchInput)
    }

    companion object {
        private const val TYPING_DELAY_MS = 300L
    }
}
