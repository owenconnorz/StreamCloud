package com.streamcloud.app.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearableMusicListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        WearMusicBridge.receive(event)
        super.onMessageReceived(event)
    }
}
