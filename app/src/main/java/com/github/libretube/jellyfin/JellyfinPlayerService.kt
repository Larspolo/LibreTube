package com.github.libretube.jellyfin

import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays Jellyfin items in the normal LibreTube player.
 *
 * The phone decodes the video. The service asks Jellyfin for direct play and only uses
 * a stream that the server converts when the transcoding setting allows it.
 * The service does not write to the watch history of LibreTube: Jellyfin keeps the progress.
 */
@OptIn(UnstableApi::class)
class JellyfinPlayerService : AbstractPlayerService() {
    override val isOfflinePlayer: Boolean = false

    private var startTimestampSeconds: Long? = null
    private var streams: Streams? = null

    private val scope = CoroutineScope(Dispatchers.Main)
    private var fetchVideoInfoJob: Job? = null

    private val reporter = JellyfinPlaybackReporter(scope) {
        exoPlayer?.let { it.currentPosition to !it.isPlaying }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    if (!isTransitioning) {
                        reporter.stop(exoPlayer?.currentPosition)
                        playNextVideo(relatedStreams = streams?.relatedStreams)
                    }
                }

                Player.STATE_IDLE -> {
                    onDestroy()
                }

                Player.STATE_READY -> {
                    val player = exoPlayer ?: return
                    reporter.onReady(player.currentPosition, !player.playWhenReady)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = exoPlayer ?: return
            if (player.playbackState == Player.STATE_READY) {
                reporter.onProgress(player.currentPosition, !isPlaying)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            val player = exoPlayer ?: return
            reporter.onProgress(newPosition.positionMs, !player.isPlaying)
        }
    }

    override suspend fun onServiceCreated(args: Bundle) {
        val playerData = args.parcelable<PlayerData>(IntentData.playerData)
        if (playerData == null) {
            stopSelf()
            return
        }
        isAudioOnlyPlayer = args.getBoolean(IntentData.audioOnly)

        videoId = playerData.videoId!!
        startTimestampSeconds = playerData.timestamp.takeIf { it > 0 }

        if (!playerData.keepQueue) PlayingQueue.clear()

        withContext(Dispatchers.Main) {
            exoPlayer?.addListener(playerListener)
            trackSelector?.updateParameters {
                setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnlyPlayer)
            }
        }
    }

    override suspend fun startPlayback() {
        super.startPlayback()

        val startPositionMs = startTimestampSeconds?.times(1000)
        startTimestampSeconds = null

        fetchVideoInfoJob?.cancelAndJoin()

        fetchVideoInfoJob = scope.launch {
            val playback = withContext(Dispatchers.IO) {
                try {
                    JellyfinRepository.preparePlayback(videoId)
                } catch (e: Exception) {
                    Log.e(TAG(), e.stackTraceToString())
                    toastFromMainDispatcher(e.localizedMessage.orEmpty())
                    null
                }
            } ?: return@launch
            streams = playback.streams

            playback.streams.toStreamItem(videoId).let {
                PlayingQueue.updateCurrent(it)
                // the next episodes form the queue, thus "next" plays the next episode
                if (playback.isEpisode && !PlayingQueue.hasNext()) {
                    PlayingQueue.add(*playback.streams.relatedStreams.toTypedArray(), skipExisting = true)
                }
            }

            launch {
                val segments = withContext(Dispatchers.IO) {
                    JellyfinRepository.getSegments(videoId, sponsorBlockConfig.keys.toList()).segments
                }
                setSponsorBlockSegments(segments)
            }

            reporter.prepare(playback)
            setMediaSource(playback)
            configurePlayer(startPositionMs ?: playback.resumePositionMs)
        }

        fetchVideoInfoJob?.join()
        fetchVideoInfoJob = null
    }

    private fun setMediaSource(playback: JellyfinPlayback) {
        // the original file and the HLS stream both need the auth header
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(JellyfinClient.authHeaders())
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(this, httpDataSourceFactory)
        )

        val subtitles = playback.subtitles.map {
            SubtitleConfiguration.Builder(it.url.toUri())
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(it.language)
                .setLabel(it.label)
                .setSelectionFlags(if (it.isForced) C.SELECTION_FLAG_FORCED else 0)
                .build()
        }

        val mediaItem = MediaItem.Builder()
            .setUri(playback.url.toUri())
            .apply { if (playback.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(subtitles)
            .setMetadata(playback.streams, videoId)
            .build()

        exoPlayer?.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
    }

    private fun configurePlayer(startPositionMs: Long) {
        val duration = streams?.duration ?: 0L
        // start from the beginning if the item is (nearly) watched
        if (startPositionMs > 0 && (duration <= 0 || startPositionMs < duration * 1000 * 9 / 10)) {
            exoPlayer?.seekTo(startPositionMs)
        }

        exoPlayer?.apply {
            playWhenReady = PlayerHelper.playAutomatically || isAudioOnlyPlayer
            prepare()
        }
    }

    override fun navigateVideo(videoId: String) {
        reporter.stop(exoPlayer?.currentPosition)
        this.streams = null

        super.navigateVideo(videoId)
    }

    override fun onDestroy() {
        reporter.stop(exoPlayer?.currentPosition)

        super.onDestroy()
    }
}
