package com.itantra.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Loud looping alarm for a received SOS: alarm stream at maximum volume with
 * transient exclusive audio focus. Plays the device's alarm (or ringtone)
 * sound, or a generated emergency tone if none can be played. Stops on
 * [stop] or after [MAX_RING_MS]; the SOS screen itself stays until acknowledged.
 */
class SosAlarm(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var focus: AudioFocusRequest? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start() {
        if (player != null || tone != null) return
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .build()
                .also { audio.requestAudioFocus(it) }
        }
        player = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .firstNotNullOfOrNull { type -> tryPlay(type) }
        if (player == null) {
            // No playable system sound: fall back to a generated tone on the alarm stream.
            tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME).also {
                    it.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, MAX_RING_MS.toInt())
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "tone generator failed: $e")
                null
            }
        }
        Log.i(TAG, "alarm started (${if (player != null) "system alarm sound" else if (tone != null) "tone" else "NO SOUND"})")
        handler.postDelayed(::stop, MAX_RING_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
        tone?.release()
        tone = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun tryPlay(type: Int): MediaPlayer? {
        val uri = RingtoneManager.getDefaultUri(type) ?: return null
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(attributes)
            p.setDataSource(app, uri)
            p.isLooping = true
            p.prepare()
            p.start()
            p
        } catch (e: Exception) {
            Log.w(TAG, "cannot play sound type $type: $e")
            p.release()
            null
        }
    }

    private companion object {
        const val TAG = "Sos"
        const val MAX_RING_MS = 60_000L
    }
}
