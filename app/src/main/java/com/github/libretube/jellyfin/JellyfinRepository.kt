package com.github.libretube.jellyfin

import android.content.Context
import androidx.annotation.StringRes
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.PreviewFrames
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.SegmentData
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.jellyfin.api.BaseItem
import com.github.libretube.jellyfin.api.ItemsResult
import com.github.libretube.jellyfin.api.MediaSourceInfo
import com.github.libretube.jellyfin.api.MediaStream
import com.github.libretube.jellyfin.api.PlaybackInfoRequest
import com.github.libretube.parcelable.DownloadData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Sort orders of the series and movie lists.
 */
enum class LibrarySort(val sortBy: String, val sortOrder: String) {
    NAME("SortName", "Ascending"),
    DATE_ADDED("DateCreated,SortName", "Descending")
}

/**
 * Result of the test that the download of the original file works.
 */
enum class DownloadCheck {
    OK,

    /** The user has no permission to download media. */
    NOT_ALLOWED,

    /** The server sends the full file instead of "206 Partial Content", thus a resumed download breaks. */
    NO_RESUME,
    FAILED
}

/**
 * The series or the collection of the item that plays now. The player shows it in the channel row.
 */
data class JellyfinItemInfo(
    val itemId: String,
    val parentId: String?,
    val parentSubtitle: String?
)

data class JellyfinSubtitle(
    val url: String,
    val language: String?,
    val label: String?,
    val isForced: Boolean
)

/**
 * All data that the player service needs to play one item.
 */
class JellyfinPlayback(
    val itemId: String,
    val streams: Streams,
    val isEpisode: Boolean,
    val mediaSourceId: String?,
    val playSessionId: String?,
    val playMethod: String,
    val url: String,
    val isHls: Boolean,
    val subtitles: List<JellyfinSubtitle>,
    val resumePositionMs: Long
) {
    companion object {
        const val PLAY_METHOD_DIRECT_PLAY = "DirectPlay"
        const val PLAY_METHOD_DIRECT_STREAM = "DirectStream"
        const val PLAY_METHOD_TRANSCODE = "Transcode"
    }
}

/**
 * An error with a message for the user.
 */
class JellyfinPlaybackException(@StringRes private val messageRes: Int) : Exception() {
    override val message: String
        get() = LibreTubeApp.instance.getString(messageRes)
}

/**
 * Gets Jellyfin data and makes LibreTube objects from it, mainly [Streams].
 *
 * The player, the download service and the player UI all use the same [Streams] object,
 * thus a Jellyfin item looks like a normal LibreTube video.
 */
object JellyfinRepository {
    private const val LIST_FIELDS = "Overview,ChildCount,PrimaryImageAspectRatio"
    private const val RELATED_LIMIT = 10
    private const val MAX_COLLECTIONS = 200
    private const val MAX_STREAMING_BITRATE = 1_000_000_000L

    private const val HTTP_OK = 200
    private const val HTTP_PARTIAL_CONTENT = 206
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403

    private val VIDEO_TRANSCODE_REASONS = setOf(
        "ContainerBitrateExceedsLimit",
        "RefFramesNotSupported",
        "AnamorphicVideoNotSupported",
        "InterlacedVideoNotSupported",
        "SubtitleCodecNotSupported",
        "UnknownVideoStreamInfo",
        "DirectPlayError"
    )

    /** Jellyfin media segment type → SponsorBlock category. */
    private val SEGMENT_CATEGORIES = mapOf(
        "Intro" to "intro",
        "Outro" to "outro",
        "Recap" to "preview",
        "Preview" to "preview",
        "Commercial" to "sponsor"
    )

    private val api get() = JellyfinClient.api
    private val userId get() = JellyfinSession.userId
    private val context: Context get() = LibreTubeApp.instance

    private val itemCache = ConcurrentHashMap<String, BaseItem>()
    private val infoCache = ConcurrentHashMap<String, JellyfinItemInfo>()
    private val relatedCache = ConcurrentHashMap<String, List<BaseItem>>()

    private class CollectionIndex(
        val collectionOfMovie: Map<String, BaseItem>,
        val children: Map<String, List<BaseItem>>
    )

    private val collectionMutex = Mutex()
    private var collections: CollectionIndex? = null

    /**
     * The parent info of an item that [getStreams] loaded before.
     */
    fun cachedInfo(videoId: String): JellyfinItemInfo? = infoCache[videoId]

    /**
     * The full item (with its media sources) that [getStreams] loaded before.
     */
    fun cachedItem(videoId: String): BaseItem? = itemCache[videoId.toJellyfinItemId()]

    /**
     * The related items (next episodes, other movies) that [getStreams] loaded before.
     */
    fun cachedRelated(videoId: String): List<BaseItem> = relatedCache[videoId].orEmpty()

    fun clearCache() {
        itemCache.clear()
        infoCache.clear()
        relatedCache.clear()
        collections = null
    }

    suspend fun getResumeItems(limit: Int = 12): List<BaseItem> =
        api.getResumeItems(userId, fields = LIST_FIELDS, limit = limit).items

    // without the started episodes, these are already in "continue watching"
    suspend fun getNextUp(limit: Int = 12): List<BaseItem> =
        api.getNextUp(userId, LIST_FIELDS, limit, enableResumable = false).items

    suspend fun getNextUpForSeries(seriesId: String): BaseItem? =
        api.getNextUp(userId, LIST_FIELDS, 1, seriesId).items.firstOrNull()

    suspend fun getLatest(limit: Int = 16): List<BaseItem> =
        api.getLatest(userId, "Movie,Series", LIST_FIELDS, limit)

    suspend fun getLibrary(
        itemType: String,
        sort: LibrarySort,
        unplayedOnly: Boolean,
        startIndex: Int,
        limit: Int
    ): ItemsResult = api.getItems(
        userId,
        includeItemTypes = itemType,
        recursive = true,
        sortBy = sort.sortBy,
        sortOrder = sort.sortOrder,
        filters = if (unplayedOnly) "IsUnplayed" else null,
        fields = LIST_FIELDS,
        startIndex = startIndex,
        limit = limit
    )

    suspend fun getItem(itemId: String): BaseItem = api.getItem(itemId, userId)

    suspend fun getSeasons(seriesId: String): List<BaseItem> =
        api.getSeasons(seriesId, userId, LIST_FIELDS).items

    suspend fun getEpisodes(seriesId: String, seasonId: String): List<BaseItem> =
        api.getEpisodes(seriesId, userId, LIST_FIELDS, seasonId = seasonId).items

    suspend fun getCollectionItems(collectionId: String): List<BaseItem> = api.getItems(
        userId,
        parentId = collectionId,
        sortBy = "ProductionYear,SortName",
        sortOrder = "Ascending",
        fields = LIST_FIELDS
    ).items

    suspend fun markPlayed(itemId: String) {
        api.markPlayed(itemId, userId)
    }

    /**
     * The episodes of a season or the movies of a collection, with their media sources for a download.
     */
    suspend fun getItemsForDownload(parentId: String, seasonId: String?): List<BaseItem> {
        val fields = "$LIST_FIELDS,MediaSources"
        val items = if (seasonId != null) {
            api.getEpisodes(parentId, userId, fields, seasonId = seasonId).items
        } else {
            api.getItems(userId, parentId = parentId, fields = fields).items
        }
        return items.filter { it.isEpisode || it.isMovie }
    }

    /**
     * The request for the download service. The format and quality are the same as in [getStreams].
     */
    fun downloadData(item: BaseItem): DownloadData? {
        val source = item.mediaSources.firstOrNull() ?: return null
        return DownloadData(
            videoId = item.id.toJellyfinId(),
            videoFormat = fileContainer(source.container).uppercase(),
            videoQuality = context.getString(R.string.jellyfin_original_quality),
            audioFormat = null,
            audioQuality = null,
            audioLanguage = null,
            subtitleCode = null
        )
    }

    /**
     * The URL of the original file. The download service cannot send a header,
     * thus the token is in the URL.
     */
    fun downloadUrl(itemId: String) =
        JellyfinClient.url("Items/$itemId/Download", "ApiKey" to JellyfinSession.token)

    /**
     * Ask for the first byte of the file, like the download service does. Do not call this on the main thread.
     */
    fun checkDownload(url: String): DownloadCheck {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .build()

        val code = runCatching {
            OkHttpClient().newCall(request).execute().use { it.code }
        }.getOrNull()

        return when (code) {
            HTTP_PARTIAL_CONTENT -> DownloadCheck.OK
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> DownloadCheck.NOT_ALLOWED
            HTTP_OK -> DownloadCheck.NO_RESUME
            else -> DownloadCheck.FAILED
        }
    }

    /**
     * Get the item as a LibreTube [Streams] object.
     *
     * The only video stream is the original file, for the download service.
     * The series (or the collection of a movie) takes the place of the channel.
     */
    suspend fun getStreams(videoId: String): Streams = coroutineScope {
        val itemId = videoId.toJellyfinItemId()
        val item = api.getItem(itemId, userId)
        itemCache[itemId] = item

        val parent = async { runCatching { loadParent(item) }.getOrNull() }
        val related = async { runCatching { loadRelated(item) }.getOrDefault(emptyList()) }

        val parentItem = parent.await()
        infoCache[videoId] = JellyfinItemInfo(
            itemId = itemId,
            parentId = parentItem?.id,
            parentSubtitle = parentItem?.let { parentSubtitle(item, it) }
        )

        val relatedItems = related.await()
        relatedCache[videoId] = relatedItems
        item.toStreams(parentItem, relatedItems.map { it.toStreamItem(context) })
    }

    /**
     * Ask the server how to play the item, and choose the method that costs the server least.
     */
    suspend fun preparePlayback(videoId: String): JellyfinPlayback = coroutineScope {
        val itemId = videoId.toJellyfinItemId()
        val streams = async { getStreams(videoId) }

        val mode = JellyfinSession.transcodingMode
        val request = PlaybackInfoRequest(
            userId = userId,
            maxStreamingBitrate = MAX_STREAMING_BITRATE,
            // no subtitle in the request: the server must never burn subtitles into the video
            subtitleStreamIndex = -1,
            deviceProfile = JellyfinDeviceProfile.build(),
            enableDirectStream = mode != TranscodingMode.NEVER,
            enableTranscoding = mode != TranscodingMode.NEVER
        )
        val info = api.getPlaybackInfo(itemId, request)
        if (info.errorCode != null) throw JellyfinPlaybackException(R.string.jellyfin_playback_not_allowed)
        val source = info.mediaSources.firstOrNull()
            ?: throw JellyfinPlaybackException(R.string.jellyfin_playback_not_possible)

        val (url, playMethod) = when {
            source.supportsDirectPlay -> JellyfinClient.url(
                "Videos/$itemId/stream",
                "static" to true,
                "mediaSourceId" to source.id,
                "playSessionId" to info.playSessionId,
                "deviceId" to JellyfinSession.deviceId
            ) to JellyfinPlayback.PLAY_METHOD_DIRECT_PLAY

            source.transcodingUrl != null && mode != TranscodingMode.NEVER -> {
                val transcodeReasons = transcodeReasons(source)
                if (mode != TranscodingMode.ALWAYS && needsVideoTranscoding(source, transcodeReasons)) {
                    throw JellyfinPlaybackException(R.string.jellyfin_needs_video_transcoding)
                }
                val playMethod = if (transcodeReasons.isEmpty()) {
                    JellyfinPlayback.PLAY_METHOD_DIRECT_STREAM
                } else {
                    JellyfinPlayback.PLAY_METHOD_TRANSCODE
                }
                JellyfinSession.serverUrl + source.transcodingUrl to playMethod
            }

            mode == TranscodingMode.NEVER -> throw JellyfinPlaybackException(R.string.jellyfin_needs_transcoding)
            else -> throw JellyfinPlaybackException(R.string.jellyfin_playback_not_possible)
        }
        val isHls = playMethod != JellyfinPlayback.PLAY_METHOD_DIRECT_PLAY

        // direct play: ExoPlayer reads the subtitles in the file, only external files are added
        // HLS: the stream has no subtitles, thus all text subtitles come as WebVTT files
        val subtitles = source.mediaStreams
            .filter { it.type == MediaStream.TYPE_SUBTITLE && it.isTextSubtitleStream }
            .filter { isHls || it.isExternal }
            .map {
                JellyfinSubtitle(
                    url = JellyfinClient.url("Videos/$itemId/${source.id}/Subtitles/${it.index}/0/Stream.vtt"),
                    language = it.language,
                    label = it.displayTitle ?: it.title,
                    isForced = it.isForced
                )
            }

        // getStreams() puts the item into the cache
        val itemStreams = streams.await()
        val item = itemCache[itemId]
        JellyfinPlayback(
            itemId = itemId,
            streams = itemStreams,
            isEpisode = item?.isEpisode == true,
            mediaSourceId = source.id,
            playSessionId = info.playSessionId,
            playMethod = playMethod,
            url = url,
            isHls = isHls,
            subtitles = subtitles,
            resumePositionMs = item?.resumePositionMs ?: 0L
        )
    }

    /**
     * Jellyfin media segments as SponsorBlock segments, so that the skip settings of LibreTube
     * also work for series. Only the [categories] that the user enabled are given.
     */
    suspend fun getSegments(videoId: String, categories: List<String>): SegmentData {
        val itemId = videoId.toJellyfinItemId()
        // servers before Jellyfin 10.10 do not have media segments
        val segments = runCatching { api.getMediaSegments(itemId).items }.getOrDefault(emptyList())
        val videoDuration = itemCache[itemId]?.durationSeconds?.toDouble() ?: 0.0

        return SegmentData(
            videoID = videoId,
            segments = segments.mapNotNull { segment ->
                val category = SEGMENT_CATEGORIES[segment.type] ?: return@mapNotNull null
                if (category !in categories) return@mapNotNull null

                Segment(
                    uuid = segment.id,
                    actionType = "skip",
                    category = category,
                    locked = 0,
                    segment = listOf(
                        segment.startTicks.toFloat() / TICKS_PER_MS / 1000f,
                        segment.endTicks.toFloat() / TICKS_PER_MS / 1000f
                    ),
                    videoDuration = videoDuration,
                    votes = 0
                )
            }
        )
    }

    private fun transcodeReasons(source: MediaSourceInfo): List<String> {
        val url = (JellyfinSession.serverUrl + source.transcodingUrl).toHttpUrlOrNull()
        return url?.queryParameter("TranscodeReasons")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }

    private fun needsVideoTranscoding(source: MediaSourceInfo, transcodeReasons: List<String>): Boolean {
        val videoCodec = source.mediaStreams.firstOrNull { it.type == MediaStream.TYPE_VIDEO }?.codec
        if (videoCodec != null && videoCodec.lowercase() !in JellyfinDeviceProfile.videoCodecs) return true

        return transcodeReasons.any { it.startsWith("Video") || it in VIDEO_TRANSCODE_REASONS }
    }

    /**
     * The series of an episode, or the collection of a movie.
     */
    private suspend fun loadParent(item: BaseItem): BaseItem? = when {
        item.isEpisode && item.seriesId != null -> api.getItem(item.seriesId, userId)
        item.isMovie -> collectionIndex().collectionOfMovie[item.id]
        else -> null
    }

    private suspend fun parentSubtitle(item: BaseItem, parent: BaseItem): String? {
        if (parent.isBoxSet) {
            val count = collectionIndex().children[parent.id]?.size ?: parent.childCount ?: return null
            return context.resources.getQuantityString(R.plurals.jellyfin_movies, count, count)
        }

        val seasonName = item.seasonName
            ?: item.parentIndexNumber?.let { context.getString(R.string.jellyfin_season, it) }
            ?: return null
        val episodeCount = item.seasonId
            ?.let { runCatching { api.getItem(it, userId).childCount }.getOrNull() }
            ?: return seasonName
        val episodes = context.resources.getQuantityString(R.plurals.jellyfin_episodes, episodeCount, episodeCount)
        return "$seasonName · $episodes"
    }

    /**
     * The next episodes of the series, or for a movie the rest of its collection
     * (else the movies that were added last).
     */
    private suspend fun loadRelated(item: BaseItem): List<BaseItem> {
        val related = when {
            item.isEpisode && item.seriesId != null -> api.getEpisodes(
                item.seriesId,
                userId,
                LIST_FIELDS,
                startItemId = item.id,
                limit = RELATED_LIMIT + 1
            ).items

            item.isMovie -> {
                val index = collectionIndex()
                index.collectionOfMovie[item.id]?.let { index.children[it.id] }
                    ?: api.getLatest(userId, BaseItem.TYPE_MOVIE, LIST_FIELDS, RELATED_LIMIT + 1)
            }

            else -> emptyList()
        }

        return related
            .filter { it.id != item.id && (it.isEpisode || it.isMovie) }
            .take(RELATED_LIMIT)
    }

    /**
     * Jellyfin does not tell the collection of a movie, thus LibreTube loads all collections once.
     */
    private suspend fun collectionIndex(): CollectionIndex = collectionMutex.withLock {
        collections ?: buildCollectionIndex().also { collections = it }
    }

    private suspend fun buildCollectionIndex(): CollectionIndex = coroutineScope {
        val boxSets = api.getItems(
            userId,
            includeItemTypes = BaseItem.TYPE_BOX_SET,
            recursive = true,
            fields = LIST_FIELDS,
            limit = MAX_COLLECTIONS
        ).items

        val children = boxSets.map { boxSet ->
            async { boxSet.id to runCatching { getCollectionItems(boxSet.id) }.getOrDefault(emptyList()) }
        }.awaitAll().toMap()

        val collectionOfMovie = mutableMapOf<String, BaseItem>()
        for (boxSet in boxSets) {
            for (child in children[boxSet.id].orEmpty()) {
                if (child.isMovie) collectionOfMovie.putIfAbsent(child.id, boxSet)
            }
        }

        CollectionIndex(collectionOfMovie, children)
    }

    private fun BaseItem.toStreams(parent: BaseItem?, related: List<StreamItem>): Streams {
        val source = mediaSources.firstOrNull()
        val videoStream = source?.mediaStreams?.firstOrNull { it.type == MediaStream.TYPE_VIDEO }

        // the original file, without any conversion on the server
        val downloadStream = source?.let {
            val container = fileContainer(it.container)
            PipedStream(
                url = downloadUrl(id),
                format = container.uppercase(),
                quality = context.getString(R.string.jellyfin_original_quality),
                mimeType = "video/$container",
                codec = videoStream?.codec,
                videoOnly = false,
                height = videoStream?.height,
                contentLength = it.size ?: -1
            )
        }

        return Streams(
            title = displayTitle(),
            description = overview.orEmpty(),
            uploaded = premiereMillis,
            uploader = parent?.name.orEmpty(),
            uploaderUrl = null,
            uploaderAvatar = parent?.posterUrl(),
            thumbnailUrl = wideImageUrl().orEmpty(),
            category = "",
            license = "",
            visibility = "",
            uploaderVerified = false,
            duration = durationSeconds,
            dislikes = -1,
            videoStreams = listOfNotNull(downloadStream),
            relatedStreams = related,
            chapters = chapterSegments(),
            uploaderSubscriberCount = -1,
            previewFrames = previewFrames(source?.id)
        )
    }

    /**
     * Jellyfin gives the container as ffprobe names it, for example `mov,mp4,m4a,3gp,3g2,mj2`.
     */
    private fun fileContainer(container: String?): String {
        val names = container.orEmpty().lowercase().split(",").map { it.trim() }
        return when {
            "mkv" in names || "matroska" in names -> "mkv"
            "mp4" in names -> "mp4"
            "webm" in names -> "webm"
            else -> names.firstOrNull { it.isNotEmpty() } ?: "mkv"
        }
    }

    private fun BaseItem.chapterSegments(): List<ChapterSegment> = chapters.mapIndexed { index, chapter ->
        ChapterSegment(
            title = chapter.name.orEmpty(),
            image = chapter.imageTag?.let {
                JellyfinClient.url("Items/$id/Images/Chapter/$index", "tag" to it, "maxWidth" to 480)
            }.orEmpty(),
            start = chapter.startPositionTicks / TICKS_PER_MS / 1000
        )
    }

    /**
     * The trickplay images of Jellyfin as seekbar preview. LibreTube uses the smallest width.
     */
    private fun BaseItem.previewFrames(mediaSourceId: String?): List<PreviewFrames> {
        val byWidth = mediaSourceId?.let { trickplay[it] } ?: trickplay.values.firstOrNull() ?: return emptyList()
        val (width, info) = byWidth.entries.minByOrNull { it.key.toIntOrNull() ?: Int.MAX_VALUE }
            ?: return emptyList()

        val framesPerTile = info.tileWidth * info.tileHeight
        if (framesPerTile <= 0 || info.interval <= 0) return emptyList()

        val tileCount = (info.thumbnailCount + framesPerTile - 1) / framesPerTile
        // Coil cannot send the auth header, thus the token is in the URL
        val urls = (0 until tileCount).map { index ->
            JellyfinClient.url(
                "Videos/$id/Trickplay/$width/$index.jpg",
                "MediaSourceId" to mediaSourceId,
                "ApiKey" to JellyfinSession.token
            )
        }

        return listOf(
            PreviewFrames(
                urls = urls,
                frameWidth = info.width,
                frameHeight = info.height,
                totalCount = info.thumbnailCount,
                durationPerFrame = info.interval.toLong(),
                framesPerPageX = info.tileWidth,
                framesPerPageY = info.tileHeight
            )
        )
    }
}
