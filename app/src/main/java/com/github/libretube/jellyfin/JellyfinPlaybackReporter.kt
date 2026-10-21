package com.github.libretube.jellyfin

import android.util.Log
import com.github.libretube.jellyfin.api.JellyfinApi
import com.github.libretube.jellyfin.api.PlaybackReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tells Jellyfin the start, the progress and the stop of a playback.
 * Then "continue watching" and "watched" are also correct on the TV and in the web app.
 *
 * Call all functions on the main thread, because the positions come from the player.
 */
class JellyfinPlaybackReporter(
    private val mainScope: CoroutineScope,
    private val currentPosition: () -> Pair<Long, Boolean>?
) {
    private class Session(val playback: JellyfinPlayback, var started: Boolean = false)

    private var session: Session? = null
    private var progressJob: Job? = null

    /**
     * Start a new session for [playback]. Call [stop] for the previous session first.
     */
    fun prepare(playback: JellyfinPlayback) {
        session = Session(playback)
    }

    /**
     * Call this when the player is ready for the first time.
     */
    fun onReady(positionMs: Long, isPaused: Boolean) {
        val session = session ?: return
        if (session.started) return
        session.started = true

        send(session.playback, positionMs) { api, report ->
            api.reportPlaybackStart(
                report.copy(playMethod = session.playback.playMethod, isPaused = isPaused, canSeek = true)
            )
        }

        progressJob?.cancel()
        progressJob = mainScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val (position, paused) = currentPosition() ?: continue
                onProgress(position, paused)
            }
        }
    }

    /**
     * Call this every few seconds, at a pause and after a seek.
     */
    fun onProgress(positionMs: Long, isPaused: Boolean) {
        val session = session ?: return
        if (!session.started) return

        send(session.playback, positionMs) { api, report ->
            api.reportPlaybackProgress(
                report.copy(playMethod = session.playback.playMethod, isPaused = isPaused, canSeek = true)
            )
        }
    }

    /**
     * Call this when the playback stops: at the end, before the next item and when the service stops.
     * Jellyfin then marks the item as watched if the position is far enough (by default 90 %).
     */
    fun stop(positionMs: Long?) {
        val session = session ?: return
        this.session = null
        progressJob?.cancel()
        progressJob = null

        val playback = session.playback
        if (session.started && positionMs != null) {
            send(playback, positionMs) { api, report -> api.reportPlaybackStopped(report) }
        }

        // without this call, ffmpeg can continue to run on the server
        val playSessionId = playback.playSessionId
        if (playback.isHls && playSessionId != null) {
            ioScope.launch {
                runCatching {
                    JellyfinClient.api.stopActiveEncodings(JellyfinSession.deviceId, playSessionId)
                }
            }
        }
    }

    private fun send(
        playback: JellyfinPlayback,
        positionMs: Long,
        call: suspend (api: JellyfinApi, report: PlaybackReport) -> Unit
    ) {
        // the offline sync must not send this position again
        JellyfinOfflineSync.markSynced(playback.itemId.toJellyfinId(), positionMs)

        val report = PlaybackReport(
            itemId = playback.itemId,
            mediaSourceId = playback.mediaSourceId,
            playSessionId = playback.playSessionId,
            positionTicks = positionMs * TICKS_PER_MS
        )
        ioScope.launch {
            try {
                call(JellyfinClient.api, report)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "failed to report the playback state: ${e.message}")
            }
        }
    }

    companion object {
        private const val LOG_TAG = "JellyfinReporter"
        private const val PROGRESS_INTERVAL_MS = 10_000L

        // the stop report must also complete after the player service stopped
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
