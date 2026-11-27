package com.streamcloud.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

internal fun ExoPlayer.readSeekPreviewStats(): SeekPreviewStats {
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
        ?.takeIf(String::isNotBlank)
        ?: "Unavailable"

    val resolution = if ((video?.width ?: 0) > 0 && (video?.height ?: 0) > 0) {
        "${video!!.width} × ${video.height}"
    } else {
        "Unavailable"
    }
    val frameRate = video?.frameRate
        ?.takeIf { it.isFinite() && it > 0f }
        ?.let { String.format(Locale.getDefault(), "%.0f fps", it) }
        ?: "Unavailable"
    val bitrate = video?.averageBitrate
        ?.takeIf { it > 0 }
        ?.let {
            if (it >= 1_000_000) String.format(Locale.getDefault(), "%.1f Mbps", it / 1_000_000f)
            else "${it / 1_000} kbps"
        }
        ?: "Unavailable"
    val audioDescription = audio?.let {
        buildList {
            it.language?.takeIf { language -> language.isNotBlank() && language != "und" }?.let(::add)
            it.channelCount.takeIf { channels -> channels > 0 }?.let { channels ->
                add(if (channels == 2) "Stereo" else "$channels ch")
            }
            add(codec(it))
        }.joinToString(" · ")
    } ?: "Unavailable"
    val subtitleDescription = text?.language
        ?.takeIf { it.isNotBlank() && it != "und" }
        ?: text?.let(::codec)
        ?: "Off"
    val status = when (playbackState) {
        Player.STATE_BUFFERING -> "Buffering"
        Player.STATE_READY -> if (isPlaying) "Playing" else "Paused"
        Player.STATE_ENDED -> "Finished"
        else -> "Preparing"
    }

    return SeekPreviewStats(
        resolution = resolution,
        frameRate = frameRate,
        videoCodec = codec(video),
        videoBitrate = bitrate,
        audio = audioDescription,
        subtitles = subtitleDescription,
        status = status,
    )
}

internal val unavailableSeekPreviewStats = SeekPreviewStats(
    resolution = "Unavailable",
    frameRate = "Unavailable",
    videoCodec = "Unavailable",
    videoBitrate = "Unavailable",
    audio = "Unavailable",
    subtitles = "Off",
    status = "Preparing",
)
