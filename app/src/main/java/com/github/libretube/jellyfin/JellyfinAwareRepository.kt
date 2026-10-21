package com.github.libretube.jellyfin

import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.api.obj.DeArrowContent
import com.github.libretube.api.obj.SegmentData
import com.github.libretube.api.obj.Streams

/**
 * Sends the calls for `jf-` IDs to [JellyfinRepository]. All other calls go to [delegate].
 *
 * The download service gets all data through [MediaServiceRepository.instance],
 * thus a Jellyfin item downloads like a YouTube video.
 */
class JellyfinAwareRepository(
    private val delegate: MediaServiceRepository
) : MediaServiceRepository by delegate {
    override suspend fun getStreams(videoId: String): Streams =
        if (videoId.isJellyfinId()) JellyfinRepository.getStreams(videoId) else delegate.getStreams(videoId)

    override suspend fun getComments(videoId: String): CommentsPage =
        if (videoId.isJellyfinId()) CommentsPage(disabled = true) else delegate.getComments(videoId)

    override suspend fun getSegments(
        videoId: String,
        category: List<String>,
        actionType: List<String>?
    ): SegmentData = if (videoId.isJellyfinId()) {
        JellyfinRepository.getSegments(videoId, category)
    } else {
        delegate.getSegments(videoId, category, actionType)
    }

    // never send a Jellyfin ID to DeArrow
    override suspend fun getDeArrowContent(videoId: String): DeArrowContent? =
        if (videoId.isJellyfinId()) null else delegate.getDeArrowContent(videoId)
}
