package com.github.libretube.jellyfin

import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.obj.Streams
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.jellyfin.ui.JellyfinCardStyle
import com.github.libretube.jellyfin.ui.JellyfinCardsAdapter
import com.github.libretube.jellyfin.ui.JellyfinDownloadDialog

/**
 * Changes the player screen for a Jellyfin item. The rest of the player stays the same.
 *
 * Most replacements come from the [Streams] object itself (the series as channel,
 * the next episodes as related videos). This object hides the parts of YouTube that
 * have no Jellyfin equivalent, and replaces the click actions that would open YouTube parts.
 */
object JellyfinPlayerUi {
    fun adapt(b: FragmentPlayerBinding, videoId: String, streams: Streams, fm: FragmentManager) {
        if (!videoId.isJellyfinId()) return

        listOf(b.commentsToggle, b.relPlayerShare, b.relPlayerSave, b.playerSubscribe).forEach {
            it.isGone = true
        }
        listOf(R.id.player_views_info, R.id.textLike, R.id.textDislike, R.id.additional_video_info).forEach {
            b.descriptionLayout.findViewById<View>(it)?.isGone = true
        }

        // channel row: the series or the collection, else hidden
        val info = JellyfinRepository.cachedInfo(videoId)
        val parentId = info?.parentId
        b.playerChannel.isVisible = parentId != null || streams.uploader.isNotBlank()
        b.playerChannelSubCount.text = info?.parentSubtitle
        b.playerChannelSubCount.isVisible = !info?.parentSubtitle.isNullOrEmpty()
        // this listener replaces the one of PlayerFragment, because updatePlayerView() runs later
        b.playerChannel.setOnClickListener {
            if (parentId != null) JellyfinNav.openParent(it.context, parentId)
        }

        // own download dialog instead of the YouTube dialog
        b.relPlayerDownload.setOnClickListener { JellyfinDownloadDialog.show(fm, videoId) }

        // own cards for the next episodes: a long press must not open the YouTube options
        // (PlayerFragment sets its adapter before this call; the offline player has no related streams)
        val relatedItems = JellyfinRepository.cachedRelated(videoId)
        if (PlayerHelper.relatedStreamsEnabled && streams.relatedStreams.isNotEmpty() && relatedItems.isNotEmpty()) {
            val layoutManager = b.relatedRecView.layoutManager as? LinearLayoutManager
            val isHorizontal = layoutManager?.orientation == LinearLayoutManager.HORIZONTAL
            b.relatedRecView.adapter = JellyfinCardsAdapter(
                JellyfinCardStyle.WIDE,
                itemWidthDp = if (isHorizontal) RELATED_CARD_WIDTH else null
            ).also { it.submitList(relatedItems) }
        }
    }

    private const val RELATED_CARD_WIDTH = 250f
}
