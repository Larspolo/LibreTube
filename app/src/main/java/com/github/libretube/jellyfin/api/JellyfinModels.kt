package com.github.libretube.jellyfin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Data classes for the parts of the Jellyfin API that LibreTube uses.
 * Jellyfin uses PascalCase names in its JSON.
 */

@Serializable
data class AuthenticateRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String
)

@Serializable
data class AuthenticationResult(
    @SerialName("User") val user: JellyfinUser? = null,
    @SerialName("AccessToken") val accessToken: String? = null
)

@Serializable
data class JellyfinUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<BaseItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0
)

@Serializable
data class BaseItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("UserData") val userData: UserItemData? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("Chapters") val chapters: List<ChapterInfo> = emptyList(),
    /** Media source ID → image width → trickplay info. */
    @SerialName("Trickplay") val trickplay: Map<String, Map<String, TrickplayInfo>> = emptyMap(),
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo> = emptyList()
) {
    val isEpisode get() = type == TYPE_EPISODE
    val isMovie get() = type == TYPE_MOVIE
    val isSeries get() = type == TYPE_SERIES
    val isBoxSet get() = type == TYPE_BOX_SET

    companion object {
        const val TYPE_EPISODE = "Episode"
        const val TYPE_MOVIE = "Movie"
        const val TYPE_SERIES = "Series"
        const val TYPE_SEASON = "Season"
        const val TYPE_BOX_SET = "BoxSet"
    }
}

@Serializable
data class UserItemData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null
)

@Serializable
data class ChapterInfo(
    @SerialName("StartPositionTicks") val startPositionTicks: Long = 0,
    @SerialName("Name") val name: String? = null,
    @SerialName("ImageTag") val imageTag: String? = null
)

@Serializable
data class TrickplayInfo(
    @SerialName("Width") val width: Int,
    @SerialName("Height") val height: Int,
    @SerialName("TileWidth") val tileWidth: Int,
    @SerialName("TileHeight") val tileHeight: Int,
    @SerialName("ThumbnailCount") val thumbnailCount: Int,
    /** Time between two thumbnails in milliseconds. */
    @SerialName("Interval") val interval: Int
)

@Serializable
data class MediaSourceInfo(
    @SerialName("Id") val id: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream> = emptyList()
)

@Serializable
data class MediaStream(
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("Index") val index: Int = 0,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsTextSubtitleStream") val isTextSubtitleStream: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("Height") val height: Int? = null
) {
    companion object {
        const val TYPE_VIDEO = "Video"
        const val TYPE_AUDIO = "Audio"
        const val TYPE_SUBTITLE = "Subtitle"
    }
}

@Serializable
data class PlaybackInfoRequest(
    @SerialName("UserId") val userId: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long,
    @SerialName("StartTimeTicks") val startTimeTicks: Long? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("DeviceProfile") val deviceProfile: DeviceProfile,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = true,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean,
    @SerialName("AllowVideoStreamCopy") val allowVideoStreamCopy: Boolean = true,
    @SerialName("AllowAudioStreamCopy") val allowAudioStreamCopy: Boolean = true,
    @SerialName("AutoOpenLiveStream") val autoOpenLiveStream: Boolean = false
)

@Serializable
data class PlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null
)

@Serializable
data class DeviceProfile(
    @SerialName("Name") val name: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long,
    @SerialName("MaxStaticBitrate") val maxStaticBitrate: Long,
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<DirectPlayProfile>,
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<TranscodingProfile>,
    @SerialName("SubtitleProfiles") val subtitleProfiles: List<SubtitleProfile>
)

@Serializable
data class DirectPlayProfile(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: String,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null
)

@Serializable
data class TranscodingProfile(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: String,
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("AudioCodec") val audioCodec: String,
    @SerialName("Protocol") val protocol: String,
    @SerialName("Context") val context: String = "Streaming",
    @SerialName("MaxAudioChannels") val maxAudioChannels: String? = null,
    @SerialName("MinSegments") val minSegments: Int = 1,
    @SerialName("BreakOnNonKeyFrames") val breakOnNonKeyFrames: Boolean = true
)

@Serializable
data class SubtitleProfile(
    @SerialName("Format") val format: String,
    @SerialName("Method") val method: String
)

/**
 * Body for the start, progress and stop reports of a playback session.
 */
@Serializable
data class PlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String?,
    @SerialName("PlaySessionId") val playSessionId: String?,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("PlayMethod") val playMethod: String? = null,
    @SerialName("IsPaused") val isPaused: Boolean? = null,
    @SerialName("CanSeek") val canSeek: Boolean? = null
)

@Serializable
data class MediaSegmentsResult(
    @SerialName("Items") val items: List<MediaSegment> = emptyList()
)

@Serializable
data class MediaSegment(
    @SerialName("Id") val id: String,
    @SerialName("Type") val type: String,
    @SerialName("StartTicks") val startTicks: Long,
    @SerialName("EndTicks") val endTicks: Long
)
