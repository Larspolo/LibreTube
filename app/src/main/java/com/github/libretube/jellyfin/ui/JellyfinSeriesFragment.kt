package com.github.libretube.jellyfin.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.libretube.R
import com.github.libretube.databinding.FragmentChannelBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.dpToPx
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.jellyfin.DownloadCheck
import com.github.libretube.jellyfin.JellyfinNav
import com.github.libretube.jellyfin.JellyfinRepository
import com.github.libretube.jellyfin.api.BaseItem
import com.github.libretube.jellyfin.headerImage
import com.github.libretube.jellyfin.cardSubtitle
import com.github.libretube.jellyfin.posterUrl
import com.github.libretube.jellyfin.toJellyfinId
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page of a series, like a channel page: header, button, one tab per season, episodes.
 * A collection of movies uses the same page with one tab.
 */
class JellyfinSeriesFragment : Fragment(R.layout.fragment_channel) {
    private var _binding: FragmentChannelBinding? = null
    private val binding get() = _binding!!

    private lateinit var itemId: String
    private var item: BaseItem? = null

    /** One entry per tab: the title and the season ID (null for a collection). */
    private var tabs: List<Pair<String, String?>> = emptyList()
    private var tabLayoutMediator: TabLayoutMediator? = null
    private var isAppBarFullyExpanded = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = requireArguments().getString(JellyfinNav.ARG_ITEM_ID)!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentChannelBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.channelAppBar.addOnOffsetChangedListener { _, verticalOffset ->
            isAppBarFullyExpanded = verticalOffset == 0
        }
        binding.channelRefresh.setOnChildScrollUpCallback { _, _ -> !isAppBarFullyExpanded }
        binding.channelRefresh.setOnRefreshListener { load() }

        binding.channelSubscribe.setIconResource(R.drawable.ic_play)
        binding.notificationBell.setIconResource(R.drawable.ic_download)
        binding.notificationBell.tooltipText = getString(R.string.jellyfin_download_all)
        binding.notificationBell.contentDescription = getString(R.string.jellyfin_download_all)
        binding.notificationBell.setOnClickListener { downloadCurrentTab() }
        binding.showMore.setOnClickListener { showMoreOptions() }
        JellyfinSearchMenu.addTo(this)

        load()
    }

    override fun onDestroyView() {
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        binding.channelRefresh.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                coroutineScope {
                    val item = JellyfinRepository.getItem(itemId)
                    if (item.isSeries) {
                        val seasons = async { JellyfinRepository.getSeasons(itemId) }
                        val nextUp = async { JellyfinRepository.getNextUpForSeries(itemId) }
                        showItem(item, seasons.await(), nextUp.await())
                    } else {
                        val movies = JellyfinRepository.getCollectionItems(itemId)
                        val nextMovie = movies.firstOrNull { (it.userData?.playbackPositionTicks ?: 0) > 0 }
                            ?: movies.firstOrNull { it.userData?.played != true }
                        showItem(item, emptyList(), nextMovie)
                    }
                }
            } catch (e: Exception) {
                showJellyfinError(e)
            } finally {
                _binding?.channelRefresh?.isRefreshing = false
            }
        }
    }

    private fun showItem(item: BaseItem, seasons: List<BaseItem>, playTarget: BaseItem?) {
        this.item = item
        val context = requireContext()
        binding.channelCoordinator.isVisible = true

        binding.channelName.text = item.name
        binding.channelSubs.text = listOfNotNull(
            item.cardSubtitle(context).takeIf { it.isNotEmpty() },
            item.productionYear?.toString()
        ).distinct().joinToString(" · ")
        binding.channelDescription.isGone = item.overview.isNullOrBlank()
        binding.channelDescription.text = item.overview.orEmpty().trim()

        val header = item.headerImage()
        binding.channelBanner.isVisible = header != null
        if (header != null) {
            val height = resources.displayMetrics.widthPixels / header.aspectRatio
            binding.channelBanner.updateLayoutParams {
                this.height = height.toInt().coerceAtMost(MAX_HEADER_HEIGHT_DP.dpToPx())
            }
            binding.channelBanner.scaleType = ImageView.ScaleType.CENTER_CROP
            ImageHelper.loadImage(header.url, binding.channelBanner)
        }

        // the poster of the series, in its own format instead of a round channel avatar
        binding.channelImage.showAsPoster(HEADER_POSTER_WIDTH_DP)
        ImageHelper.loadImage(item.posterUrl(), binding.channelImage)

        // "Continue: S2·E4" plays the next episode (or the next movie of a collection)
        binding.channelSubscribe.text = playTarget?.let { getString(R.string.jellyfin_continue, shortLabel(it)) }
            ?: getString(R.string.jellyfin_play)
        binding.channelSubscribe.isEnabled = playTarget != null
        binding.channelSubscribe.setOnClickListener {
            if (playTarget != null) {
                NavigationHelper.navigateVideo(requireContext(), PlayerData(playTarget.id.toJellyfinId()))
            }
        }

        tabs = if (item.isSeries) {
            seasons.map { season ->
                (season.name ?: getString(R.string.jellyfin_season, season.indexNumber ?: 0)) to season.id
            }
        } else {
            listOf(getString(R.string.jellyfin_movies_tab) to null)
        }

        tabLayoutMediator?.detach()
        binding.pager.adapter = SeasonsAdapter(this, itemId, tabs.map { it.second })
        tabLayoutMediator = TabLayoutMediator(binding.tabParent, binding.pager) { tab, position ->
            tab.text = tabs[position].first
        }.also { it.attach() }

        // open the season of the next episode
        val seasonIndex = tabs.indexOfFirst { it.second != null && it.second == playTarget?.seasonId }
        if (seasonIndex > 0) binding.pager.setCurrentItem(seasonIndex, false)
    }

    private fun shortLabel(item: BaseItem): String {
        val season = item.parentIndexNumber
        val episode = item.indexNumber
        return if (item.isEpisode && season != null && episode != null) "S$season·E$episode" else item.name.orEmpty()
    }

    private fun currentTab(): Pair<String, String?>? = tabs.getOrNull(binding.pager.currentItem)

    /**
     * Download all episodes of the visible season (or all movies of the collection).
     */
    private fun downloadCurrentTab() {
        val item = item ?: return
        val (tabTitle, seasonId) = currentTab() ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val items = try {
                JellyfinRepository.getItemsForDownload(itemId, seasonId)
            } catch (e: Exception) {
                showJellyfinError(e)
                return@launch
            }

            val toDownload = withContext(Dispatchers.IO) {
                items.filter { !DatabaseHolder.Database.downloadDao().exists(it.id.toJellyfinId()) }
            }
            if (toDownload.isEmpty()) {
                Toast.makeText(requireContext(), R.string.already_downloaded, Toast.LENGTH_SHORT).show()
                return@launch
            }

            // one test is enough: all files come from the same server and user
            val check = withContext(Dispatchers.IO) {
                JellyfinRepository.checkDownload(JellyfinRepository.downloadUrl(toDownload.first().id))
            }
            if (check != DownloadCheck.OK) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.jellyfin_download_all)
                    .setMessage(JellyfinDownloadDialog.downloadCheckMessage(check))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.jellyfin_download_all)
                .setMessage(
                    getString(R.string.jellyfin_download_all_confirm, toDownload.size, "${item.name} · $tabTitle")
                )
                .setPositiveButton(R.string.download) { _, _ ->
                    val requests = toDownload.mapNotNull { JellyfinRepository.downloadData(it) }
                    requests.forEach { DownloadHelper.startDownloadService(requireContext(), it) }
                    val message = getString(R.string.jellyfin_download_all_started, requests.size)
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showMoreOptions() {
        val (_, seasonId) = currentTab() ?: return
        val options = listOf(R.string.mark_as_watched, R.string.mark_as_unwatched)

        BaseBottomSheet()
            .setSimpleItems(options.map { getString(it) }) { index ->
                val played = options[index] == R.string.mark_as_watched
                // Jellyfin marks all episodes of a season (or all movies of a collection)
                val result = withContext(Dispatchers.IO) {
                    runCatching { JellyfinRepository.setPlayed(seasonId ?: itemId, played) }
                }
                val context = context ?: return@setSimpleItems
                val message = result.exceptionOrNull()?.localizedMessage
                    ?: context.getString(if (played) R.string.jellyfin_marked_watched else R.string.jellyfin_marked_unwatched)
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                if (_binding != null) load()
            }
            .show(childFragmentManager)
    }

    companion object {
        private const val MAX_HEADER_HEIGHT_DP = 220f
        private const val HEADER_POSTER_WIDTH_DP = 44f
    }

    private class SeasonsAdapter(
        fragment: Fragment,
        private val parentId: String,
        private val seasonIds: List<String?>
    ) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = seasonIds.size

        override fun createFragment(position: Int): Fragment =
            JellyfinEpisodesPage.newInstance(parentId, seasonIds[position])
    }
}
