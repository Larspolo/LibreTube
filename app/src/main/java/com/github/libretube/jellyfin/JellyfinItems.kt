package com.github.libretube.jellyfin

import android.content.Context
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.jellyfin.api.BaseItem
import java.time.Instant

const val TICKS_PER_MS = 10_000L
private const val TICKS_PER_SECOND = 10_000_000L

private const val WIDE_IMAGE_WIDTH = 640
private const val POSTER_WIDTH = 360

/**
 * The title as LibreTube shows it. An episode gets the form `S1·E3 — <title>`.
 */
fun BaseItem.displayTitle(): String {
    val title = name.orEmpty()
    if (!isEpisode || indexNumber == null) return title

    val prefix = if (parentIndexNumber != null) "S$parentIndexNumber·E$indexNumber" else "E$indexNumber"
    return if (title.isEmpty()) prefix else "$prefix — $title"
}

/**
 * The second line of a card: the series name for an episode, the year for a movie
 * and the number of seasons for a series.
 */
fun BaseItem.cardSubtitle(context: Context): String = when {
    isEpisode -> seriesName.orEmpty()
    isSeries -> childCount?.let {
        context.resources.getQuantityString(R.plurals.jellyfin_seasons, it, it)
    } ?: productionYear?.toString().orEmpty()
    isBoxSet -> childCount?.let {
        context.resources.getQuantityString(R.plurals.jellyfin_movies, it, it)
    }.orEmpty()
    else -> productionYear?.toString().orEmpty()
}

val BaseItem.durationSeconds: Long
    get() = (runTimeTicks ?: 0L) / TICKS_PER_SECOND

val BaseItem.resumePositionMs: Long
    get() = (userData?.playbackPositionTicks ?: 0L) / TICKS_PER_MS

val BaseItem.premiereMillis: Long
    get() = premiereDate?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

/**
 * A 16:9 image: the image of the episode, else a thumb or backdrop of the item or its series.
 */
fun BaseItem.wideImageUrl(): String? {
    if (isEpisode) {
        imageTags[IMAGE_PRIMARY]?.let { return JellyfinClient.imageUrl(id, IMAGE_PRIMARY, it, WIDE_IMAGE_WIDTH) }
    }
    imageTags[IMAGE_THUMB]?.let { return JellyfinClient.imageUrl(id, IMAGE_THUMB, it, WIDE_IMAGE_WIDTH) }
    backdropImageTags.firstOrNull()?.let {
        return JellyfinClient.imageUrl(id, IMAGE_BACKDROP, it, WIDE_IMAGE_WIDTH)
    }
    if (parentThumbItemId != null && parentThumbImageTag != null) {
        return JellyfinClient.imageUrl(parentThumbItemId, IMAGE_THUMB, parentThumbImageTag, WIDE_IMAGE_WIDTH)
    }
    if (parentBackdropItemId != null && parentBackdropImageTags.isNotEmpty()) {
        return JellyfinClient.imageUrl(
            parentBackdropItemId,
            IMAGE_BACKDROP,
            parentBackdropImageTags.first(),
            WIDE_IMAGE_WIDTH
        )
    }
    return posterUrl()
}

/**
 * The 2:3 poster. An episode gets the poster of its series.
 */
fun BaseItem.posterUrl(): String? {
    if (isEpisode && seriesId != null && seriesPrimaryImageTag != null) {
        return JellyfinClient.imageUrl(seriesId, IMAGE_PRIMARY, seriesPrimaryImageTag, POSTER_WIDTH)
    }
    return imageTags[IMAGE_PRIMARY]?.let { JellyfinClient.imageUrl(id, IMAGE_PRIMARY, it, POSTER_WIDTH) }
}

/**
 * An image for the top of the series page, and the format (width / height) to show it in.
 */
data class HeaderImage(val url: String, val aspectRatio: Float)

/**
 * The backdrop has no text, thus a crop of it looks good. The banner has the format of
 * a YouTube channel banner, but often shows the title a second time.
 */
fun BaseItem.headerImage(): HeaderImage? {
    backdropImageTags.firstOrNull()?.let {
        return HeaderImage(JellyfinClient.imageUrl(id, IMAGE_BACKDROP, it, HEADER_IMAGE_WIDTH), BACKDROP_HEADER_RATIO)
    }
    imageTags[IMAGE_BANNER]?.let {
        return HeaderImage(JellyfinClient.imageUrl(id, IMAGE_BANNER, it, HEADER_IMAGE_WIDTH), BANNER_RATIO)
    }
    return null
}

private const val HEADER_IMAGE_WIDTH = 1280

/** A wide crop of the 16:9 backdrop, like a cinema screen. */
private const val BACKDROP_HEADER_RATIO = 2.4f

/** The format of a Jellyfin banner (758 × 140). */
private const val BANNER_RATIO = 5.4f

/**
 * The item as a LibreTube [StreamItem], for the playing queue and the related videos.
 * The series takes the place of the channel.
 */
fun BaseItem.toStreamItem(context: Context) = StreamItem(
    url = id.toJellyfinId(),
    type = StreamItem.TYPE_STREAM,
    title = displayTitle(),
    thumbnail = wideImageUrl(),
    uploaderName = cardSubtitle(context),
    uploaderAvatar = if (isEpisode) posterUrl() else null,
    duration = durationSeconds,
    uploaded = premiereMillis,
    shortDescription = overview
)

const val IMAGE_PRIMARY = "Primary"
const val IMAGE_THUMB = "Thumb"
const val IMAGE_BACKDROP = "Backdrop"
const val IMAGE_BANNER = "Banner"
