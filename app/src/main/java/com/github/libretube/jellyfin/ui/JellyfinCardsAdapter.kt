package com.github.libretube.jellyfin.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.JellyfinPosterBadgeBinding
import com.github.libretube.databinding.TrendingRowBinding
import com.github.libretube.databinding.VideoRowBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.dpToPx
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.jellyfin.JellyfinNav
import com.github.libretube.jellyfin.JellyfinRepository
import com.github.libretube.jellyfin.api.BaseItem
import com.github.libretube.jellyfin.cardSubtitle
import com.github.libretube.jellyfin.displayTitle
import com.github.libretube.jellyfin.durationSeconds
import com.github.libretube.jellyfin.posterUrl
import com.github.libretube.jellyfin.premiereMillis
import com.github.libretube.jellyfin.resumePositionMs
import com.github.libretube.jellyfin.toJellyfinId
import com.github.libretube.jellyfin.wideImageUrl
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.extensions.setFormattedDuration
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.viewholders.VideoCardsViewHolder
import com.github.libretube.ui.viewholders.VideosViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

enum class JellyfinCardStyle {
    /** 16:9 card with the series poster as avatar (layout of the home page). */
    WIDE,

    /** 2:3 poster card. */
    POSTER,

    /** Row with the thumbnail at the left (layout of a channel page). */
    ROW
}

/**
 * Cards for Jellyfin items, with the item layouts of LibreTube.
 *
 * LibreTube's own adapters open the YouTube options at a long press, thus this adapter
 * is necessary. A long press shows: play, download, mark as watched or not watched.
 *
 * @param itemWidthDp a fixed width for horizontal rows
 * @param showSeriesName show the series name in a row (for search results)
 * @param onItemChanged called after the user marked an item as watched or not watched
 */
class JellyfinCardsAdapter(
    private val style: JellyfinCardStyle,
    private val itemWidthDp: Float? = null,
    private val showSeriesName: Boolean = false,
    private val onItemChanged: () -> Unit = {}
) : ListAdapter<BaseItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (style == JellyfinCardStyle.ROW) {
            return VideosViewHolder(VideoRowBinding.inflate(inflater, parent, false))
        }

        val binding = TrendingRowBinding.inflate(inflater, parent, false)
        if (style == JellyfinCardStyle.POSTER) {
            // the layout of LibreTube has no place for a badge, thus it is added at the top right
            val container = binding.thumbnailcard.getChildAt(0) as ConstraintLayout
            val badge = JellyfinPosterBadgeBinding.inflate(inflater, container, false)
            badge.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            container.addView(badge.root)
            binding.root.setTag(R.id.jellyfin_badge, badge)
        }
        return VideoCardsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(holder.bindingAdapterPosition)
        when (holder) {
            is VideosViewHolder -> bindRow(holder.binding, item)
            is VideoCardsViewHolder -> bindCard(holder.trendingRowBinding!!, item)
        }
    }

    private fun bindCard(binding: TrendingRowBinding, item: BaseItem) {
        with(binding) {
            val context = root.context
            val isPoster = style == JellyfinCardStyle.POSTER

            itemWidthDp?.let { width -> root.updateLayoutParams { this.width = width.dpToPx() } }
            thumbnailcard.updateLayoutParams<ConstraintLayout.LayoutParams> {
                dimensionRatio = if (isPoster) "2:3" else "16:9"
            }

            textViewTitle.text = if (isPoster) item.name else item.displayTitle()
            textViewChannel.text = subtitle(context, item, showTimeLeft = !isPoster)
            ImageHelper.loadImage(if (isPoster) item.posterUrl() else item.wideImageUrl(), thumbnail)

            val showDuration = !isPoster && item.isPlayable && item.durationSeconds > 0
            (thumbnailDuration.parent as View).isVisible = showDuration
            if (showDuration) thumbnailDuration.setFormattedDuration(item.durationSeconds, false, 0)
            watchProgress.setJellyfinWatchProgress(item)
            sponsorBadgeCard.isGone = true
            (root.getTag(R.id.jellyfin_badge) as? JellyfinPosterBadgeBinding)?.let { bindBadge(it, item) }

            // the series poster takes the place of the channel avatar
            val seriesId = item.seriesId
            val avatarUrl = if (!isPoster && item.isEpisode) item.posterUrl() else null
            channelImageContainer.isVisible = avatarUrl != null
            if (avatarUrl != null) {
                channelImageContainer.radius = POSTER_CORNER_DP.dpToPx().toFloat()
                channelImage.setPosterSize(CARD_POSTER_WIDTH_DP)
                ImageHelper.loadImage(avatarUrl, channelImage)
            }
            channelImage.setOnClickListener {
                if (seriesId != null) JellyfinNav.openParent(context, seriesId)
            }

            root.setOnClickListener { open(context, item) }
            root.setOnLongClickListener {
                showOptions(context, item)
                true
            }
        }
    }

    private fun bindRow(binding: VideoRowBinding, item: BaseItem) {
        with(binding) {
            val context = root.context

            videoTitle.text = item.displayTitle()
            val date = item.premiereMillis.takeIf { it > 0 && item.isPlayable }
                ?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
            val seriesName = item.seriesName.takeIf { showSeriesName && item.isEpisode }
            videoInfo.text = listOfNotNull(seriesName, date).ifEmpty { listOf(item.cardSubtitle(context)) }
                .joinToString(" · ")
            thumbnailDurationCard.isVisible = item.isPlayable && item.durationSeconds > 0
            thumbnailDuration.setFormattedDuration(item.durationSeconds, false, 0)
            watchProgress.setJellyfinWatchProgress(item)
            ImageHelper.loadImage(item.wideImageUrl(), thumbnail)
            channelContainer.isGone = true

            root.setOnClickListener { open(context, item) }
            root.setOnLongClickListener {
                showOptions(context, item)
                true
            }

            downloadBadge.isVisible = false
            val videoId = item.id.toJellyfinId()
            CoroutineScope(Dispatchers.IO).launch {
                val isDownloaded = DatabaseHolder.Database.downloadDao().exists(videoId)
                withContext(Dispatchers.Main) {
                    downloadBadge.isVisible = isDownloaded
                }
            }
        }
    }

    /**
     * Series and collections: the number of unwatched items. Watched items: a check mark.
     */
    private fun bindBadge(badge: JellyfinPosterBadgeBinding, item: BaseItem) {
        val userData = item.userData
        val unplayedCount = userData?.unplayedItemCount?.takeIf { !item.isPlayable && it > 0 }
        val isPlayed = userData?.played == true

        badge.root.isVisible = isPlayed || unplayedCount != null
        badge.badgeIcon.isVisible = isPlayed
        badge.badgeText.isVisible = !isPlayed && unplayedCount != null
        badge.badgeText.text = unplayedCount?.toString()
    }

    private fun subtitle(context: Context, item: BaseItem, showTimeLeft: Boolean): String {
        val subtitle = item.cardSubtitle(context)
        val positionMs = item.resumePositionMs
        if (!showTimeLeft || positionMs <= 0 || item.durationSeconds <= 0) return subtitle

        val minutesLeft = ((item.durationSeconds * 1000 - positionMs) / 60_000).coerceAtLeast(1)
        val timeLeft = context.getString(R.string.jellyfin_time_left, minutesLeft.toInt())
        return listOf(subtitle, timeLeft).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun open(context: Context, item: BaseItem) {
        if (item.isPlayable) {
            NavigationHelper.navigateVideo(context, PlayerData(item.id.toJellyfinId()))
        } else {
            JellyfinNav.openParent(context, item.id)
        }
    }

    /**
     * Long press: play and download (episodes and movies), and mark as watched or not watched.
     * A started item gets both marks: "not watched" also removes the position.
     */
    private fun showOptions(context: Context, item: BaseItem) {
        val activity = ContextHelper.unwrapActivity<BaseActivity>(context)
        val videoId = item.id.toJellyfinId()
        val isPlayed = item.userData?.played == true
        val isStarted = item.resumePositionMs > 0

        val options = buildList<Pair<Int, suspend () -> Unit>> {
            if (item.isPlayable) {
                add(R.string.jellyfin_play to { NavigationHelper.navigateVideo(activity, PlayerData(videoId)) })
                add(R.string.download to { JellyfinDownloadDialog.show(activity.supportFragmentManager, videoId) })
            }
            if (!isPlayed) add(R.string.mark_as_watched to { setPlayed(activity, item, true) })
            if (isPlayed || isStarted) add(R.string.mark_as_unwatched to { setPlayed(activity, item, false) })
        }

        BaseBottomSheet()
            .apply { setTitle(item.displayTitle()) }
            .setSimpleItems(options.map { context.getString(it.first) }) { index ->
                options[index].second.invoke()
            }
            .show(activity.supportFragmentManager)
    }

    private suspend fun setPlayed(context: Context, item: BaseItem, played: Boolean) {
        val result = withContext(Dispatchers.IO) {
            runCatching { JellyfinRepository.setPlayed(item.id, played) }
        }
        val message = result.exceptionOrNull()?.localizedMessage
            ?: context.getString(if (played) R.string.jellyfin_marked_watched else R.string.jellyfin_marked_unwatched)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onItemChanged()
    }

    private val BaseItem.isPlayable get() = isEpisode || isMovie

    companion object {
        private const val CARD_POSTER_WIDTH_DP = 30f
    }

    private object DiffCallback : DiffUtil.ItemCallback<BaseItem>() {
        override fun areItemsTheSame(oldItem: BaseItem, newItem: BaseItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: BaseItem, newItem: BaseItem) = oldItem == newItem
    }
}
