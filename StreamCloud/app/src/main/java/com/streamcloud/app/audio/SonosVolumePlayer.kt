package com.streamcloud.app.audio

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.streamcloud.app.data.sonos.SonosRepository
import java.util.concurrent.CopyOnWriteArraySet

@OptIn(UnstableApi::class)
internal class SonosVolumePlayer(player: Player) : ForwardingPlayer(player) {

    private val volumeListeners = CopyOnWriteArraySet<Player.Listener>()
    private val applicationHandler = Handler(applicationLooper)
    private var lastAudibleVolume = SonosRepository.sonosVolume.value.coerceAtLeast(1)

    private val sonosDeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        .setMinVolume(0)
        .setMaxVolume(100)
        .build()

    override fun addListener(listener: Player.Listener) {
        volumeListeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        volumeListeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getAvailableCommands(): Player.Commands =
        Player.Commands.Builder()
            .addAll(super.getAvailableCommands())
            .add(Player.COMMAND_GET_DEVICE_VOLUME)
            .add(Player.COMMAND_SET_DEVICE_VOLUME)
            .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
            .add(Player.COMMAND_ADJUST_DEVICE_VOLUME)
            .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
            .build()

    override fun getDeviceInfo(): DeviceInfo = sonosDeviceInfo

    override fun getDeviceVolume(): Int =
        SonosRepository.sonosVolume.value.coerceIn(0, 100).also { volume ->
            if (volume > 0) lastAudibleVolume = volume
        }

    override fun isDeviceMuted(): Boolean = getDeviceVolume() == 0

    override fun setDeviceVolume(volume: Int) {
        SonosRepository.setVolume(volume)
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        SonosRepository.setVolume(volume)
    }

    override fun increaseDeviceVolume() {
        SonosRepository.adjustVolume(VOLUME_STEP)
    }

    override fun increaseDeviceVolume(flags: Int) {
        SonosRepository.adjustVolume(VOLUME_STEP)
    }

    override fun decreaseDeviceVolume() {
        SonosRepository.adjustVolume(-VOLUME_STEP)
    }

    override fun decreaseDeviceVolume(flags: Int) {
        SonosRepository.adjustVolume(-VOLUME_STEP)
    }

    override fun setDeviceMuted(muted: Boolean) {
        setDeviceMuted(muted, C.VOLUME_FLAG_SHOW_UI)
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        if (muted) {
            lastAudibleVolume = getDeviceVolume().coerceAtLeast(1)
            SonosRepository.setVolume(0)
        } else {
            SonosRepository.setVolume(lastAudibleVolume)
        }
    }

    fun notifyDeviceVolumeChanged(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        if (clamped > 0) lastAudibleVolume = clamped
        val dispatch = {
            volumeListeners.forEach { listener ->
                listener.onDeviceVolumeChanged(clamped, clamped == 0)
            }
        }
        if (Looper.myLooper() == applicationLooper) dispatch() else applicationHandler.post(dispatch)
    }

    private companion object {
        const val VOLUME_STEP = 5
    }
}
