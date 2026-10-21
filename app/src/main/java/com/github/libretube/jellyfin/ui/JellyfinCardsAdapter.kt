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
 * is necessary. A long press shows: play, download, mark as watched.
 *
 * @param itemWidthDp a fixed width for horizontal rows
 * @param onItemChanged called after the user marked an item as watched
 */
class JellyfinCardsAdapter(
    private val style: JellyfinCardStyle,
    private val itemWidthDp: Float? = null,
    private val onItemChanged: () -> Unit = {}
) : ListAdapter<BaseItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (style == JellyfinCardStyle.ROW) {
            VideosViewHolder(VideoRowBinding.inflate(inflater, parent, false))
        } else {
            VideoCardsViewHolder(TrendingRowBinding.inflate(inflater, parent, false))
        }
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
                if (item.isPlayable) showOptions(context, item)
                item.isPlayable
            }
        }
    }

    private fun bindRow(binding: VideoRowBinding, item: BaseItem) {
        with(binding) {
            val context = root.context

            videoTitle.text = item.displayTitle()
            videoInfo.text = item.premiereMillis.takeIf { it > 0 }
                ?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
                ?: item.cardSubtitle(context)
            thumbnailDuration.setFormattedDuration(item.durationSeconds, false, 0)
            watchProgress.setJellyfinWatchProgress(item)
            ImageHelper.loadImage(item.wideImageUrl(), thumbnail)
            channelContainer.isGone = true

            root.setOnClickListener { open(context, item) }
            root.setOnLongClickListener {
                if (item.isPlayable) showOptions(context, item)
                item.isPlayable
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

    private fun showOptions(context: Context, item: BaseItem) {
        val activity = ContextHelper.unwrapActivity<BaseActivity>(context)
        val videoId = item.id.toJellyfinId()
        val options = listOf(
            context.getString(R.string.jellyfin_play),
            context.getString(R.string.download),
            context.getString(R.string.mark_as_watched)
        )

        BaseBottomSheet()
            .apply { setTitle(item.displayTitle()) }
            .setSimpleItems(options) { index ->
                when (index) {
                    0 -> NavigationHelper.navigateVideo(activity, PlayerData(videoId))
                    1 -> JellyfinDownloadDialog.show(activity.supportFragmentManager, videoId)
                    2 -> {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { JellyfinRepository.markPlayed(item.id) }
                        }
                        val message = result.exceptionOrNull()?.localizedMessage
                            ?: activity.getString(R.string.jellyfin_marked_watched)
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                        onItemChanged()
                    }
                }
            }
            .show(activity.supportFragmentManager)
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
