package com.streamcloud.app.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

@OptIn(UnstableApi::class)
class MusicNotificationProvider(context: Context) : DefaultMediaNotificationProvider(context) {

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        // super() returns: [like, repeat, prev, play/pause, next] (custom layout first, then standard controls)
        val all = super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)

        // Reorder to match the notification screenshot: [like, prev, play/pause, next, repeat]
        val like     = all.firstOrNull { it.sessionCommand?.customAction == MusicPlaybackService.ACTION_LIKE }
        val repeat   = all.firstOrNull { it.sessionCommand?.customAction == MusicPlaybackService.ACTION_REPEAT }
        val standard = all.filter {
            it.sessionCommand?.customAction != MusicPlaybackService.ACTION_LIKE &&
                it.sessionCommand?.customAction != MusicPlaybackService.ACTION_REPEAT
        }
        return ImmutableList.copyOf(listOfNotNull(like) + standard + listOfNotNull(repeat))
    }
}
