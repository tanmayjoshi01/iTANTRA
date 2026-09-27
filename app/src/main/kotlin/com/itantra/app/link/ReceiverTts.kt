package com.itantra.app.link

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * Phone B: speaks received Hindi text with Android's TextToSpeech.
 *
 * Uses Google's engine explicitly, because Samsung's default engine on the
 * SM-T225 has no Hindi voice. Never throws: any failure becomes [State.status].
 * In alert mode the utterance plays on the alarm stream at maximum alarm
 * volume, with transient audio focus.
 */
class ReceiverTts(context: Context) {

    data class State(
        val status: String = "TTS: initializing…",
        val ready: Boolean = false,
        val hindiDataMissing: Boolean = false,
        val speaking: Boolean = false,
        /** Milliseconds from speak() being called (message received) to audio starting. */
        val lastStartLatencyMs: Long? = null,
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val requestedAt = HashMap<String, Long>()
    private var focusRequest: AudioFocusRequest? = null
    private var nextId = 0

    private val tts: TextToSpeech = TextToSpeech(appContext, { status -> onInit(status) }, GOOGLE_TTS)

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _state.update { it.copy(status = "TTS unavailable (engine init failed: $status)") }
            return
        }
        val result = try {
            tts.setLanguage(HINDI)
        } catch (e: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        when (result) {
            TextToSpeech.LANG_MISSING_DATA ->
                _state.update { it.copy(status = "Hindi TTS unavailable: voice data not installed", hindiDataMissing = true) }
            TextToSpeech.LANG_NOT_SUPPORTED ->
                _state.update { it.copy(status = "Hindi TTS unavailable: not supported by engine") }
            else -> {
                tts.setOnUtteranceProgressListener(progressListener)
                // Silent warm-up: the first real utterance otherwise took about 5 s
                // (engine cold start on the SM-T225); later ones started in under 300 ms.
                tts.speak("नमस्ते", TextToSpeech.QUEUE_FLUSH, Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f) }, "warmup")
                _state.update { it.copy(status = "TTS ready (Hindi, ${tts.voice?.name ?: "default voice"})", ready = true) }
            }
        }
        Log.i(TAG, "init status=$status setLanguage=$result -> ${_state.value.status}")
    }

    fun speak(text: String, alert: Boolean) {
        if (!_state.value.ready) return
        val id = "u${nextId++}"
        requestedAt[id] = SystemClock.elapsedRealtime()
        val params = Bundle()
        if (alert) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            tts.setAudioAttributes(attributes(AudioAttributes.USAGE_ALARM))
        } else {
            tts.setAudioAttributes(attributes(AudioAttributes.USAGE_MEDIA))
        }
        requestFocus(alert)
        // QUEUE_ADD keeps messages in arrival order.
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
    }

    fun shutdown() {
        abandonFocus()
        tts.shutdown()
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            val started = requestedAt.remove(utteranceId)?.let { SystemClock.elapsedRealtime() - it }
            _state.update { it.copy(speaking = true, lastStartLatencyMs = started ?: it.lastStartLatencyMs) }
            Log.i(TAG, "speaking $utteranceId, started ${started}ms after receipt")
        }

        override fun onDone(utteranceId: String) = finished()

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = finished()

        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(TAG, "utterance $utteranceId error $errorCode")
            finished()
        }

        private fun finished() {
            if (!tts.isSpeaking) {
                _state.update { it.copy(speaking = false) }
                abandonFocus()
            }
        }
    }

    private fun attributes(usage: Int) = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus(alert: Boolean) {
        abandonFocus()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes(if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA))
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
        val HINDI: Locale = Locale.forLanguageTag("hi-IN")
        private const val TAG = "ReceiverTts"
    }
}
