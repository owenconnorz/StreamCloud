package com.aioweb.app.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Wires the Google Cast SDK into the app via the manifest meta-data
 * `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME`.
 *
 * Uses the Default Media Receiver — no Cast Developer Console registration
 * required. Anyone with a Chromecast / Android TV / Google Nest Hub on the
 * same Wi-Fi network can be picked from the cast button's chooser dialog.
 */
class CastOptionsProviderImpl : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        // Re-bring the user back to MainActivity when they tap the Cast notification
        // or expand the player from the lock-screen.
        val notificationOptions = NotificationOptions.Builder()
            .setTargetActivityClassName("com.aioweb.app.MainActivity")
            .build()

        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
