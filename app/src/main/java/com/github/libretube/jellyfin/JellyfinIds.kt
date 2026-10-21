package com.github.libretube.jellyfin

import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.OnlinePlayerService

private const val PREFIX = "jf-"
private const val JELLYFIN_ID_LENGTH = 32

/**
 * Jellyfin items use the ID `jf-<32 hex chars>` inside LibreTube.
 *
 * A YouTube video ID always has 11 characters, so both kinds of ID never collide.
 * The ID has no colon, because it is also part of the file name of a download.
 */
fun String.isJellyfinId() = startsWith(PREFIX) && length == PREFIX.length + JELLYFIN_ID_LENGTH

fun String.toJellyfinId() = PREFIX + this

/**
 * The Jellyfin item ID without the `jf-` prefix.
 */
fun String.toJellyfinItemId() = removePrefix(PREFIX)

object JellyfinIds {
    /**
     * The online player service that LibreTube started last.
     *
     * The audio player has no video ID when it connects to the running service,
     * thus it uses this value.
     */
    var onlineService: Class<*> = OnlinePlayerService::class.java
        private set

    /**
     * The online player service for [videoId]. The value is also kept in [onlineService].
     */
    fun onlineServiceFor(videoId: String?): Class<*> {
        val service = if (videoId?.isJellyfinId() == true) {
            JellyfinPlayerService::class.java
        } else {
            OnlinePlayerService::class.java
        }
        onlineService = service
        return service
    }

    /**
     * True if [playerData] needs a different online player service than the one that runs now.
     * The running player can then not play the item, and LibreTube must open a new player.
     */
    fun isSourceSwitch(playerData: PlayerData): Boolean {
        if (playerData.isOffline) return false
        val nextService = if (playerData.videoId?.isJellyfinId() == true) {
            JellyfinPlayerService::class.java
        } else {
            OnlinePlayerService::class.java
        }
        return nextService != onlineService
    }
}
