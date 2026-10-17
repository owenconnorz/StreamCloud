package com.streamcloud.app.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import com.streamcloud.app.data.watchparty.*
import kotlinx.coroutines.*
import kotlin.math.abs

@androidx.annotation.OptIn(UnstableApi::class)
internal class WatchPartyPlayerBinding(
    context: Context,
    private val player: Player,
    private val media: PartyMedia,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var applying = false
    private var remoteQuietUntil = 0L
    private var lastApplied = -1L
    private var lastSent = 0L
    private var lastStatus = ""
    private var selectedRoom = ""
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = changed()
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = changed()
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) changed()
        }
    }
    init {
        WatchPartyRepository.initialize(context)
        player.addListener(listener)
        scope.launch {
            WatchPartyRepository.state.collect { state ->
                if (!state.connected) return@collect
                if (state.isHost && selectedRoom != state.room?.code) {
                    selectedRoom = state.room?.code.orEmpty()
                    if (state.room?.media?.key != media.key) WatchPartyRepository.selectMedia(media)
                }
                applyRemote()
            }
        }
        scope.launch {
            while (isActive) {
                val state = WatchPartyRepository.state.value
                if (state.connected) {
                    val status = when {
                        state.room?.media?.key != media.key -> "browsing"
                        player.playbackState == Player.STATE_BUFFERING -> "buffering"
                        player.playbackState == Player.STATE_IDLE -> "loading"
                        else -> "ready"
                    }
                    if (status != lastStatus) { WatchPartyRepository.status(status); lastStatus = status }
                    if (state.isHost && System.currentTimeMillis() - lastSent >= 5000) send()
                } else lastStatus = ""
                delay(500)
            }
        }
    }

    private fun changed() {
        if (applying || android.os.SystemClock.elapsedRealtime() < remoteQuietUntil) return
        val state = WatchPartyRepository.state.value
        if (state.canControl) send() else if (state.connected) applyRemote(force = true)
    }

    private fun send() {
        val state = WatchPartyRepository.state.value
        val selected = state.room?.media ?: return
        if (!state.canControl || selected.key != media.key || player.playbackState == Player.STATE_IDLE) return
        lastSent = System.currentTimeMillis()
        WatchPartyRepository.playback(selected, player.currentPosition, !player.playWhenReady, player.playbackParameters.speed)
    }

    private fun applyRemote(force: Boolean = false) {
        val state = WatchPartyRepository.state.value
        val room = state.room ?: return
        if (!state.connected || room.media?.key != media.key || room.seq == 0L) return
        if (!force && (room.seq <= lastApplied || room.playback.memberId == state.memberId)) {
            lastApplied = maxOf(lastApplied, room.seq)
            return
        }
        applying = true
        remoteQuietUntil = android.os.SystemClock.elapsedRealtime() + 350
        try {
            val target = WatchPartyRepository.position(room.playback).let {
                if (player.duration > 0) it.coerceAtMost(player.duration) else it
            }
            if (abs(player.currentPosition - target) > 1250 || force) player.seekTo(target)
            player.setPlaybackSpeed(room.playback.rate)
            player.playWhenReady = !room.playback.paused
            lastApplied = room.seq
        } finally { applying = false }
    }

    fun close() {
        player.removeListener(listener)
        scope.cancel()
        val state = WatchPartyRepository.state.value
        val selected = state.room?.media
        if (state.isHost && selected?.key == media.key) {
            WatchPartyRepository.playback(selected, player.currentPosition, true, player.playbackParameters.speed)
        }
        WatchPartyRepository.status("browsing")
    }
}
