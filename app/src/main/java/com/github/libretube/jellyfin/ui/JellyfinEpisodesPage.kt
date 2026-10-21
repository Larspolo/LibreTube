package com.github.libretube.jellyfin.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.databinding.FragmentChannelContentBinding
import com.github.libretube.jellyfin.JellyfinRepository
import kotlinx.coroutines.launch

/**
 * One tab of the series page: the episodes of a season, or the movies of a collection.
 * It uses the layout of a channel tab.
 */
class JellyfinEpisodesPage : Fragment(R.layout.fragment_channel_content) {
    private var _binding: FragmentChannelContentBinding? = null
    private val binding get() = _binding!!

    private val adapter = JellyfinCardsAdapter(JellyfinCardStyle.ROW) { load() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentChannelContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.channelRecView.layoutManager = LinearLayoutManager(requireContext())
        binding.channelRecView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // reload after playback, so that the progress bars are up to date
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        if (_binding == null) return
        val args = requireArguments()
        val parentId = args.getString(ARG_PARENT_ID)!!
        val seasonId = args.getString(ARG_SEASON_ID)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items = if (seasonId != null) {
                    JellyfinRepository.getEpisodes(parentId, seasonId)
                } else {
                    JellyfinRepository.getCollectionItems(parentId)
                }
                adapter.submitList(items)
            } catch (e: Exception) {
                showJellyfinError(e)
            } finally {
                _binding?.progressBar?.isGone = true
            }
        }
    }

    companion object {
        private const val ARG_PARENT_ID = "parentId"
        private const val ARG_SEASON_ID = "seasonId"

        /**
         * @param parentId the series, or the collection if [seasonId] is null
         */
        fun newInstance(parentId: String, seasonId: String?) = JellyfinEpisodesPage().apply {
            arguments = bundleOf(ARG_PARENT_ID to parentId, ARG_SEASON_ID to seasonId)
        }
    }
}
