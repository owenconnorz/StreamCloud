package com.streamcloud.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

enum class DjVoicePreset(
    val label: String,
    val description: String,
    val pitch: Float,
    val rate: Float,
) {
    // Keep the enum name for preference compatibility while making the default style
    // smoother and more alluring through Android's local TTS controls.
    BrightHost("Velvet host", "Warm, natural, and alluring", 0.95f, 0.96f),
    Midnight("Midnight host", "Low, smooth, and cinematic", 0.88f, 0.93f),
    Chill("Chill host", "Relaxed and intimate", 0.99f, 0.94f),
    Hype("Hype host", "Bright and energetic", 1.08f, 1.05f),
}

/**
 * A small wrapper around Android's own speech engine. It intentionally exposes original
 * presentation presets only; it never attempts to reproduce a real person's or character's voice.
 */
class DjNarrator(context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ready = false
    private var closed = false
    private var completion: (() -> Unit)? = null
    private var activeUtteranceId: String? = null
    private lateinit var textToSpeech: TextToSpeech

    init {
        val engine = TextToSpeech(context.applicationContext) { status ->
            ready = !closed && status == TextToSpeech.SUCCESS
            if (ready) {
                mainHandler.post {
                    if (!closed && ::textToSpeech.isInitialized) {
                        textToSpeech.language = Locale.getDefault()
                        chooseNaturalVoice(textToSpeech, Locale.getDefault())
                    }
                }
            }
        }
        textToSpeech = engine
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finishUtterance(utteranceId)

            override fun onDone(utteranceId: String) = finishUtterance(utteranceId)

            override fun onError(utteranceId: String, errorCode: Int) = finishUtterance(utteranceId)
        })
    }

    /**
     * Returns false when the local speech engine is unavailable. In that case callers should
     * continue the music action without waiting for audio narration.
     */
    fun speak(text: String, preset: DjVoicePreset, onFinished: () -> Unit): Boolean {
        if (closed || !ready || text.isBlank()) return false
        cancel()
        val utteranceId = "streamcloud-dj-${UUID.randomUUID()}"
        completion = onFinished
        activeUtteranceId = utteranceId
        textToSpeech.setPitch(preset.pitch)
        textToSpeech.setSpeechRate(preset.rate)
        val parameters = Bundle().apply {
            // Use the engine's full utterance volume. This does not change the user's system
            // volume, but prevents DJ speech from being quieter than normal TTS guidance.
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val result = textToSpeech.speak(
            text.take(360),
            TextToSpeech.QUEUE_FLUSH,
            parameters,
            utteranceId,
        )
        if (result == TextToSpeech.ERROR) {
            if (activeUtteranceId == utteranceId) {
                activeUtteranceId = null
                completion = null
            }
            return false
        }
        return true
    }

    /** Cancels a pending introduction without invoking its completion action. */
    fun cancel() {
        activeUtteranceId = null
        completion = null
        if (::textToSpeech.isInitialized && !closed) textToSpeech.stop()
    }

    fun close() {
        cancel()
        closed = true
        textToSpeech.shutdown()
    }

    private fun finishUtterance(utteranceId: String) {
        if (utteranceId != activeUtteranceId) return
        activeUtteranceId = null
        val action = completion ?: return
        completion = null
        mainHandler.post(action)
    }

    private fun chooseNaturalVoice(engine: TextToSpeech, locale: Locale) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) return
        val voice = runCatching {
            engine.voices
                .asSequence()
                .filter { it.locale.language == locale.language }
                .sortedWith(
                    compareBy<Voice> { it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality }
                        .thenBy { it.latency },
                )
                .firstOrNull()
        }.getOrNull()
        if (voice != null) engine.voice = voice
    }
}