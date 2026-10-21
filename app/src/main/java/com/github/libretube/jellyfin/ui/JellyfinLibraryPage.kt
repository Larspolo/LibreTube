package com.github.libretube.jellyfin.ui

import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.FragmentJellyfinLibraryBinding
import com.github.libretube.jellyfin.JellyfinRepository
import com.github.libretube.jellyfin.JellyfinSession
import com.github.libretube.jellyfin.LibrarySort
import com.github.libretube.jellyfin.api.BaseItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Grid with the posters of all series or all movies, with a sort order and a filter "not watched".
 */
class JellyfinLibraryPage : Fragment(R.layout.fragment_jellyfin_library) {
    private var _binding: FragmentJellyfinLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var itemType: String
    private var sort = LibrarySort.NAME
    private var unplayedOnly = false

    private val adapter = JellyfinCardsAdapter(JellyfinCardStyle.POSTER)
    private val items = mutableListOf<BaseItem>()
    private var totalCount = Int.MAX_VALUE
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemType = requireArguments().getString(ARG_ITEM_TYPE)!!
        sort = JellyfinSession.librarySort(itemType)
        unplayedOnly = JellyfinSession.libraryUnplayedOnly(itemType)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentJellyfinLibraryBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        val columns = (resources.configuration.screenWidthDp / POSTER_MIN_WIDTH_DP).coerceAtLeast(3)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), columns)
        binding.recycler.adapter = adapter
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!recyclerView.canScrollVertically(1)) loadMore()
            }
        })

        binding.refresh.setOnRefreshListener { reload() }

        binding.sortChip.setOnClickListener { showSortMenu() }
        binding.unplayedChip.isChecked = unplayedOnly
        binding.unplayedChip.setOnCheckedChangeListener { _, isChecked ->
            unplayedOnly = isChecked
            JellyfinSession.setLibraryUnplayedOnly(itemType, isChecked)
            reload()
        }
        updateSortChip()

        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), binding.sortChip)
        LibrarySort.entries.forEach { popup.menu.add(0, it.ordinal, it.ordinal, sortTitle(it)) }
        popup.setOnMenuItemClickListener { menuItem ->
            sort = LibrarySort.entries[menuItem.itemId]
            JellyfinSession.setLibrarySort(itemType, sort)
            updateSortChip()
            reload()
            true
        }
        popup.show()
    }

    private fun sortTitle(sort: LibrarySort) = getString(
        when (sort) {
            LibrarySort.NAME -> R.string.jellyfin_sort_name
            LibrarySort.DATE_ADDED -> R.string.jellyfin_sort_date_added
        }
    )

    private fun updateSortChip() {
        binding.sortChip.text = sortTitle(sort)
    }

    private fun reload() {
        loadJob?.cancel()
        loadJob = null
        items.clear()
        totalCount = Int.MAX_VALUE
        adapter.submitList(emptyList())
        loadMore()
    }

    private fun loadMore() {
        if (loadJob?.isActive == true || items.size >= totalCount) return

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = JellyfinRepository.getLibrary(itemType, sort, unplayedOnly, items.size, PAGE_SIZE)
                items.addAll(result.items)
                totalCount = if (result.items.isEmpty()) items.size else result.totalRecordCount
                adapter.submitList(items.toList())
                binding.emptyText.isVisible = items.isEmpty()
            } catch (e: Exception) {
                showJellyfinError(e)
            } finally {
                _binding?.progress?.isGone = true
                _binding?.refresh?.isRefreshing = false
            }
        }
    }

    companion object {
        private const val ARG_ITEM_TYPE = "itemType"
        private const val PAGE_SIZE = 60
        private const val POSTER_MIN_WIDTH_DP = 120

        fun newInstance(itemType: String) = JellyfinLibraryPage().apply {
            arguments = bundleOf(ARG_ITEM_TYPE to itemType)
        }
    }
}
