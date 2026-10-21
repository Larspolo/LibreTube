package com.github.libretube.jellyfin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.github.libretube.LibreTubeApp
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.jellyfin.api.UpdateUserItemDataRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.Instant

/**
 * Sends the progress of downloads that the user watched offline to Jellyfin.
 *
 * LibreTube keeps the position of each video. For a Jellyfin download, this object compares
 * that position with the last position that Jellyfin received. Offline progress only moves
 * the position on the server forward, never back: progress from another device stays.
 */
object JellyfinOfflineSync {
    private const val LOG_TAG = "JellyfinOfflineSync"
    private const val SYNCED_PREFS = "jellyfin_synced_positions"

    /** Smaller differences are not progress (the player saves the position at slightly different times). */
    private const val TOLERANCE_MS = 30_000L

    private val prefs: SharedPreferences by lazy {
        LibreTubeApp.instance.getSharedPreferences(SYNCED_PREFS, Context.MODE_PRIVATE)
    }
    private val mutex = Mutex()

    /**
     * Remember a position that Jellyfin already has, for example from a progress report.
     */
    fun markSynced(videoId: String, positionMs: Long) {
        prefs.edit { putLong(videoId, positionMs) }
    }

    /**
     * Send the offline progress of all Jellyfin downloads. Errors are ignored: the next call tries again.
     */
    suspend fun syncDownloads() = mutex.withLock {
        if (!JellyfinSession.isLoggedIn) return@withLock

        val downloads = Database.downloadDao().getAll()
            .map { it.download }
            .filter { it.videoId.isJellyfinId() }

        for (download in downloads) {
            val localMs = DatabaseHelper.getWatchPosition(download.videoId) ?: continue
            val syncedMs = prefs.getLong(download.videoId, 0L)
            if (localMs <= syncedMs + TOLERANCE_MS) continue

            try {
                syncPosition(download.videoId, localMs, download.duration)
                markSynced(download.videoId, localMs)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "failed to send the offline progress: ${e.message}")
            }
        }
    }

    private suspend fun syncPosition(videoId: String, localMs: Long, durationSeconds: Long?) {
        val itemId = videoId.toJellyfinItemId()
        val serverItem = JellyfinRepository.getItem(itemId)
        val userData = serverItem.userData

        // already watched on the server, or the server is further
        if (userData?.played == true) return
        if (localMs <= serverItem.resumePositionMs + TOLERANCE_MS) return

        if (DatabaseHelper.isVideoWatched(localMs, durationSeconds ?: serverItem.durationSeconds)) {
            JellyfinRepository.setPlayed(itemId, true)
        } else {
            val response = JellyfinClient.api.updateUserData(
                itemId,
                JellyfinSession.userId,
                UpdateUserItemDataRequest(
                    playbackPositionTicks = localMs * TICKS_PER_MS,
                    lastPlayedDate = Instant.now().toString()
                )
            )
            if (!response.isSuccessful) throw HttpException(response)
        }
    }
}
