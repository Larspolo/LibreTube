package com.github.libretube.jellyfin

import android.media.MediaCodecList
import com.github.libretube.jellyfin.api.DeviceProfile
import com.github.libretube.jellyfin.api.DirectPlayProfile
import com.github.libretube.jellyfin.api.SubtitleProfile
import com.github.libretube.jellyfin.api.TranscodingProfile

/**
 * The Jellyfin device profile of this phone.
 *
 * The profile only names codecs that the phone can decode itself (from [MediaCodecList]).
 * The server then sends the original file when it can, and does not convert it.
 */
object JellyfinDeviceProfile {
    /** No limit: a bitrate limit makes the server transcode. */
    private const val MAX_BITRATE = 1_000_000_000L

    /** Containers that ExoPlayer reads without help from the server. */
    private const val DIRECT_PLAY_CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts,avi,flv"

    private val VIDEO_CODECS = mapOf(
        "video/avc" to listOf("h264"),
        "video/hevc" to listOf("hevc", "h265"),
        "video/x-vnd.on2.vp8" to listOf("vp8"),
        "video/x-vnd.on2.vp9" to listOf("vp9"),
        "video/av01" to listOf("av1"),
        "video/mp4v-es" to listOf("mpeg4"),
        "video/mpeg2" to listOf("mpeg2video"),
        "video/3gpp" to listOf("h263")
    )

    private val AUDIO_CODECS = mapOf(
        "audio/mp4a-latm" to listOf("aac"),
        "audio/mpeg" to listOf("mp3"),
        "audio/mpeg-l2" to listOf("mp2"),
        "audio/opus" to listOf("opus"),
        "audio/vorbis" to listOf("vorbis"),
        "audio/flac" to listOf("flac"),
        "audio/alac" to listOf("alac"),
        "audio/ac3" to listOf("ac3"),
        "audio/eac3" to listOf("eac3"),
        "audio/eac3-joc" to listOf("eac3"),
        "audio/true-hd" to listOf("truehd"),
        "audio/vnd.dts" to listOf("dts"),
        "audio/vnd.dts.hd" to listOf("dts")
    )

    /** Subtitle formats that ExoPlayer reads from the video file. */
    private val EMBEDDED_SUBTITLES = listOf(
        "srt", "subrip", "ass", "ssa", "vtt", "webvtt", "mov_text", "pgs", "pgssub", "dvdsub", "dvbsub"
    )

    private val decoderTypes: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.toList() }
            .map { it.lowercase() }
            .toSet()
    }

    /** Jellyfin names of the video codecs that this phone decodes. */
    val videoCodecs: Set<String> by lazy {
        decoderTypes.flatMap { VIDEO_CODECS[it].orEmpty() }.toSet()
    }

    /** Jellyfin names of the audio codecs that this phone decodes. */
    val audioCodecs: Set<String> by lazy {
        decoderTypes.flatMap { AUDIO_CODECS[it].orEmpty() }.toSet()
    }

    fun build(): DeviceProfile {
        // HLS from the server uses H.264 or HEVC; the server copies the video stream if it can
        val hlsVideoCodecs = listOf("h264", "hevc").filter { it in videoCodecs }.ifEmpty { listOf("h264") }

        return DeviceProfile(
            name = "LibreTube",
            maxStreamingBitrate = MAX_BITRATE,
            maxStaticBitrate = MAX_BITRATE,
            directPlayProfiles = listOf(
                DirectPlayProfile(
                    container = DIRECT_PLAY_CONTAINERS,
                    type = "Video",
                    videoCodec = videoCodecs.joinToString(","),
                    audioCodec = audioCodecs.joinToString(",")
                )
            ),
            transcodingProfiles = listOf(
                TranscodingProfile(
                    container = "ts",
                    type = "Video",
                    videoCodec = hlsVideoCodecs.joinToString(","),
                    audioCodec = "aac,mp3",
                    protocol = "hls",
                    maxAudioChannels = "2"
                )
            ),
            // text subtitles as external WebVTT files: the server never burns them into the video
            subtitleProfiles = EMBEDDED_SUBTITLES.map { SubtitleProfile(it, "Embed") } +
                SubtitleProfile("vtt", "External")
        )
    }
}
