package com.streamcloud.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

internal data class TvPlaybackStats(
    val resolution: String,
    val frameRate: String,
    val videoCodec: String,
    val videoBitrate: String,
    val audio: String,
    val subtitles: String,
    val state: String,
)

internal val unavailableTvPlaybackStats = TvPlaybackStats(
    resolution = "Unavailable",
    frameRate = "Unavailable",
    videoCodec = "Unavailable",
    videoBitrate = "Unavailable",
    audio = "Unavailable",
    subtitles = "Off",
    state = "Preparing",
)

internal fun ExoPlayer.readTvPlaybackStats(): TvPlaybackStats {
    fun selectedFormat(trackType: Int): Format? {
        for (group in currentTracks.groups) {
            if (group.type != trackType) continue
            for (index in 0 until group.mediaTrackGroup.length) {
                if (group.isTrackSelected(index)) return group.mediaTrackGroup.getFormat(index)
            }
        }
        return null
    }

    val video = selectedFormat(C.TRACK_TYPE_VIDEO)
    val audio = selectedFormat(C.TRACK_TYPE_AUDIO)
    val text = selectedFormat(C.TRACK_TYPE_TEXT)

    fun codec(format: Format?): String = format?.sampleMimeType
        ?.substringAfter('/')
        ?.replace('-', ' ')
        ?.uppercase(Locale.getDefault())
        ?.takeIf { it.isNotBlank() }
        ?: "Unavailable"

    fun language(format: Format?, fallback: String): String =
        format?.language?.takeIf { it.isNotBlank() && it != "und" } ?: fallback

    val resolution = if ((video?.width ?: 0) > 0 && (video?.height ?: 0) > 0) {
        "${video!!.width} × ${video.height}"
    } else {
        "Unavailable"
    }
    val frameRate = video?.frameRate
        ?.takeIf { it.isFinite() && it > 0f }
        ?.let { String.format(Locale.getDefault(), "%.0f fps", it) }
        ?: "Unavailable"
    val videoBitrate = video?.averageBitrate
        ?.takeIf { it > 0 }
        ?.let { bitrate ->
            if (bitrate >= 1_000_000) {
                String.format(Locale.getDefault(), "%.1f Mbps", bitrate / 1_000_000f)
            } else {
                "${bitrate / 1_000} kbps"
            }
        }
        ?: "Unavailable"
    val audioDescription = audio?.let {
        buildList {
            add(language(it, codec(it)))
            it.channelCount.takeIf { channels -> channels > 0 }?.let { channels ->
                add(if (channels == 2) "Stereo" else "$channels channels")
            }
            add(codec(it))
        }.joinToString(" · ")
    } ?: "Unavailable"
    val subtitleDescription = text?.let { language(it, codec(it)) } ?: "Off"
    val playbackStateLabel = when (playbackState) {
        Player.STATE_BUFFERING -> "Buffering"
        Player.STATE_READY -> if (isPlaying) "Playing" else "Paused"
        Player.STATE_ENDED -> "Finished"
        else -> "Preparing"
    }

    return TvPlaybackStats(
        resolution = resolution,
        frameRate = frameRate,
        videoCodec = codec(video),
        videoBitrate = videoBitrate,
        audio = audioDescription,
        subtitles = subtitleDescription,
        state = playbackStateLabel,
    )
}
