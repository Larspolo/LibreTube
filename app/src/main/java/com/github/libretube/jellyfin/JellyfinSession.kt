package com.github.libretube.jellyfin

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.libretube.LibreTubeApp
import com.github.libretube.helpers.PreferenceHelper
import java.util.UUID

/**
 * When the Jellyfin server may convert a video.
 */
enum class TranscodingMode {
    /** The server never converts anything. Only direct play. */
    NEVER,

    /** The server may convert the audio, but never the video. */
    AUDIO_ONLY,

    /** The server may also convert the video. This is the heaviest load for the server. */
    ALWAYS
}

/**
 * Login data and settings of the Jellyfin tab.
 *
 * The server URL, the user ID and the settings use [PreferenceHelper] with the prefix `jellyfin_`.
 * The access token and the device ID use a separate preferences file,
 * because a LibreTube backup contains all normal preferences.
 * LibreTube never keeps the password.
 */
object JellyfinSession {
    private const val SERVER_URL = "jellyfin_server_url"
    private const val USER_ID = "jellyfin_user_id"
    private const val USERNAME = "jellyfin_username"
    private const val TRANSCODING_MODE = "jellyfin_transcoding_mode"
    private const val LIBRARY_SORT = "jellyfin_sort_"
    private const val LIBRARY_UNPLAYED = "jellyfin_unplayed_"

    private const val AUTH_PREFS = "jellyfin_auth"
    private const val TOKEN = "jellyfin_token"
    private const val DEVICE_ID = "jellyfin_device_id"

    private val authPrefs: SharedPreferences by lazy {
        LibreTubeApp.instance.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * The server URL without a slash at the end, for example `https://media.example.org`.
     */
    val serverUrl: String
        get() = PreferenceHelper.getString(SERVER_URL, "")

    val userId: String
        get() = PreferenceHelper.getString(USER_ID, "")

    val username: String
        get() = PreferenceHelper.getString(USERNAME, "")

    val token: String
        get() = authPrefs.getString(TOKEN, "").orEmpty()

    val isLoggedIn: Boolean
        get() = serverUrl.isNotEmpty() && userId.isNotEmpty() && token.isNotEmpty()

    /**
     * A random ID for this installation. Jellyfin uses it to identify the device.
     */
    val deviceId: String
        get() = authPrefs.getString(DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            authPrefs.edit { putString(DEVICE_ID, it) }
        }

    var transcodingMode: TranscodingMode
        get() = runCatching {
            TranscodingMode.valueOf(
                PreferenceHelper.getString(TRANSCODING_MODE, TranscodingMode.AUDIO_ONLY.name)
            )
        }.getOrDefault(TranscodingMode.AUDIO_ONLY)
        set(value) = PreferenceHelper.putString(TRANSCODING_MODE, value.name)

    /**
     * The sort order of the series or movies tab ([itemType] is the Jellyfin item type).
     */
    fun librarySort(itemType: String): LibrarySort = runCatching {
        LibrarySort.valueOf(PreferenceHelper.getString(LIBRARY_SORT + itemType, LibrarySort.NAME.name))
    }.getOrDefault(LibrarySort.NAME)

    fun setLibrarySort(itemType: String, sort: LibrarySort) =
        PreferenceHelper.putString(LIBRARY_SORT + itemType, sort.name)

    /**
     * True if the series or movies tab only shows items that the user did not watch.
     */
    fun libraryUnplayedOnly(itemType: String) = PreferenceHelper.getBoolean(LIBRARY_UNPLAYED + itemType, false)

    fun setLibraryUnplayedOnly(itemType: String, unplayedOnly: Boolean) =
        PreferenceHelper.putBoolean(LIBRARY_UNPLAYED + itemType, unplayedOnly)

    fun login(serverUrl: String, userId: String, username: String, token: String) {
        PreferenceHelper.putString(SERVER_URL, serverUrl)
        PreferenceHelper.putString(USER_ID, userId)
        PreferenceHelper.putString(USERNAME, username)
        authPrefs.edit { putString(TOKEN, token) }
        JellyfinClient.reset()
    }

    /**
     * Remove the token. The server URL and the username stay for the next login.
     */
    fun logout() {
        PreferenceHelper.remove(USER_ID)
        authPrefs.edit { remove(TOKEN) }
        JellyfinClient.reset()
        JellyfinRepository.clearCache()
    }

    /**
     * Remove all login data, also the server URL and the username.
     */
    fun forgetServer() {
        logout()
        PreferenceHelper.remove(SERVER_URL)
        PreferenceHelper.remove(USERNAME)
    }

    /**
     * Make a server URL from user input and remove slashes at the end.
     * Without a scheme, the URL gets `http://` for the default HTTP port of Jellyfin (8096),
     * else `https://`.
     */
    fun normalizeServerUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed

        val hostAndPort = trimmed.substringBefore('/')
        return if (hostAndPort.endsWith(":$DEFAULT_HTTP_PORT")) "http://$trimmed" else "https://$trimmed"
    }

    private const val DEFAULT_HTTP_PORT = 8096
}
