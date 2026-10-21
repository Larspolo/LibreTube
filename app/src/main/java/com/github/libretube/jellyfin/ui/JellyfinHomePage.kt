package com.github.libretube.jellyfin.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.FragmentJellyfinHomeBinding
import com.github.libretube.jellyfin.JellyfinOfflineSync
import com.github.libretube.jellyfin.JellyfinRepository
import com.github.libretube.jellyfin.api.BaseItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Overview page of the Jellyfin tab, like the start page of LibreTube:
 * sections with a title and a horizontal row of cards.
 */
class JellyfinHomePage : Fragment(R.layout.fragment_jellyfin_home) {
    private var _binding: FragmentJellyfinHomeBinding? = null
    private val binding get() = _binding!!

    private val resumeAdapter = JellyfinCardsAdapter(JellyfinCardStyle.WIDE, WIDE_CARD_WIDTH) { load() }
    private val nextUpAdapter = JellyfinCardsAdapter(JellyfinCardStyle.WIDE, WIDE_CARD_WIDTH) { load() }
    private val latestAdapter = JellyfinCardsAdapter(JellyfinCardStyle.POSTER, POSTER_CARD_WIDTH)

    private var loadJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentJellyfinHomeBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.resumeRv.adapter = resumeAdapter
        binding.nextUpRv.adapter = nextUpAdapter
        binding.latestRv.adapter = latestAdapter

        binding.refresh.setOnRefreshListener { load() }
    }

    override fun onResume() {
        super.onResume()
        // reload after playback, so that "continue watching" is up to date
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        if (_binding == null) return
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // first the offline progress, so that "continue watching" shows it
                JellyfinOfflineSync.syncDownloads()
                coroutineScope {
                    val resume = async { JellyfinRepository.getResumeItems() }
                    val nextUp = async { JellyfinRepository.getNextUp() }
                    val latest = async { JellyfinRepository.getLatest() }

                    showSection(binding.resumeTitle, binding.resumeRv, resumeAdapter, resume.await())
                    showSection(binding.nextUpTitle, binding.nextUpRv, nextUpAdapter, nextUp.await())
                    showSection(binding.latestTitle, binding.latestRv, latestAdapter, latest.await())
                }
                binding.emptyText.isVisible = listOf(resumeAdapter, nextUpAdapter, latestAdapter)
                    .all { it.itemCount == 0 }
            } catch (e: Exception) {
                showJellyfinError(e)
            } finally {
                _binding?.progress?.isGone = true
                _binding?.refresh?.isRefreshing = false
            }
        }
    }

    private fun showSection(
        title: View,
        recyclerView: RecyclerView,
        adapter: JellyfinCardsAdapter,
        items: List<BaseItem>
    ) {
        adapter.submitList(items)
        title.isVisible = items.isNotEmpty()
        recyclerView.isVisible = items.isNotEmpty()
    }

    companion object {
        private const val WIDE_CARD_WIDTH = 250f
        private const val POSTER_CARD_WIDTH = 130f
    }
}
