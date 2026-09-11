package com.itantra.speechengine.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AudioRecorder"

/**
 * Captures microphone audio as a sequence of [AudioFrame]s using Android's
 * [AudioRecord] API.
 *
 * This class only captures audio; it has no opinion on voice activity or
 * segmentation (see [com.itantra.speechengine.vad] and
 * [com.itantra.speechengine.segmentation] for those), keeping the capture
 * layer independent of any specific downstream processing. It is the only
 * class in this module that depends on the Android framework.
 *
 * Captures using [MediaRecorder.AudioSource.VOICE_RECOGNITION], which asks
 * the platform to minimize automatic gain control / noise suppression
 * processing that some devices otherwise apply by default — processing
 * that can help general audio recording but is generally undesirable ahead
 * of a speech-recognition front end where the raw signal is preferred.
 *
 * Does not request the RECORD_AUDIO runtime permission itself: doing so
 * requires an Activity/Fragment context and a UI-level result callback,
 * which belongs to the application layer. [start] reports
 * [AudioCaptureException.PermissionDenied] via its `onError` callback if
 * the permission is missing, rather than crashing.
 *
 * Not thread-safe: call [start]/[stop]/[release] from a single thread
 * (the main thread is fine — the actual blocking reads happen on an
 * internal background dispatcher). The `onFrame`/`onError` callbacks passed
 * to [start] are invoked on that internal background dispatcher, not the
 * calling thread; forward to the main thread yourself if needed.
 */
class AudioRecorder(
    private val config: AudioConfig = AudioConfig(),
) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * Initializes the microphone and begins capture on a background
     * dispatcher. [onFrame] is called once per captured frame, in capture
     * order. [onError] is called at most once if capture cannot start or
     * fails while running; after an error, capture has already stopped and
     * [release] should be called before attempting [start] again.
     *
     * Safe to call again after a prior [stop] — each call creates a fresh
     * [AudioRecord] instance. Calling [start] while already recording is a
     * no-op (logged, not treated as an error).
     */
    @SuppressLint("MissingPermission") // Permission is the app layer's responsibility; failures are reported via onError, not crashed on.
    fun start(onFrame: (AudioFrame) -> Unit, onError: (AudioCaptureException) -> Unit = {}) {
        if (isRecording) {
            Log.w(TAG, "start() called while already recording; ignoring")
            return
        }

        val minBufferSizeBytes = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            channelConfig(config.channelCount),
            AudioFormat.ENCODING_PCM_16BIT,
        )

        if (minBufferSizeBytes == AudioRecord.ERROR || minBufferSizeBytes == AudioRecord.ERROR_BAD_VALUE) {
            onError(
                AudioCaptureException.InitializationFailed(
                    "AudioRecord.getMinBufferSize() rejected sampleRate=${config.sampleRateHz}Hz, " +
                        "channelCount=${config.channelCount} on this device",
                ),
            )
            return
        }

        // A few frames of headroom so the capture loop is not forced to keep
        // up with the hardware on every single read.
        val bufferSizeBytes = maxOf(minBufferSizeBytes, config.bytesPerFrame * BUFFER_SIZE_FRAME_MULTIPLE)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                config.sampleRateHz,
                channelConfig(config.channelCount),
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes,
            )
        } catch (e: SecurityException) {
            onError(AudioCaptureException.PermissionDenied(e))
            return
        } catch (e: IllegalArgumentException) {
            onError(AudioCaptureException.InitializationFailed("Invalid AudioRecord configuration", e))
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError(AudioCaptureException.InitializationFailed("AudioRecord failed to initialize (state=${record.state})"))
            return
        }

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            onError(AudioCaptureException.InitializationFailed("AudioRecord.startRecording() failed", e))
            return
        }

        audioRecord = record
        isRecording = true
        Log.i(
            TAG,
            "Recording started: sampleRate=${config.sampleRateHz}Hz, " +
                "channelCount=${config.channelCount}, bufferSizeBytes=$bufferSizeBytes",
        )

        captureJob = scope.launch {
            try {
                val samplesPerFrame = config.samplesPerFrame
                val readBuffer = ShortArray(samplesPerFrame)

                while (isActive) {
                    val samplesRead = record.read(readBuffer, 0, samplesPerFrame)

                    if (samplesRead < 0) {
                        onError(AudioCaptureException.ReadFailed("AudioRecord.read() returned error code $samplesRead"))
                        break
                    }
                    if (samplesRead == 0) continue

                    // readBuffer is reused across iterations; the frame must
                    // own an independent array since downstream components
                    // (e.g. SpeechSegmenter) retain frames across calls.
                    val frameSamples = readBuffer.copyOf(samplesRead)
                    onFrame(AudioFrame(frameSamples, config, System.currentTimeMillis()))
                }
            } finally {
                // Runs on this same coroutine/thread regardless of whether the
                // loop exited via cancellation, a read error, or normally, so
                // AudioRecord.stop()/release() is never called concurrently
                // with an in-flight blocking read() from another thread.
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "AudioRecord.stop() failed during cleanup", e)
                }
                record.release()
                isRecording = false
                Log.i(TAG, "Recording stopped")
            }
        }
    }

    /**
     * Requests that capture stop. Cancellation is cooperative: the capture
     * coroutine finishes its current (already in-flight) blocking read —
     * bounded by roughly one frame duration, since audio is continuously
     * arriving — before releasing the microphone. Safe to call multiple
     * times, and safe to call when not recording (no-op).
     */
    fun stop() {
        captureJob?.cancel()
        captureJob = null
        audioRecord = null
    }

    /**
     * Stops capture (if running) and releases all resources, including the
     * coroutine scope used for capture. Call when this AudioRecorder
     * instance is no longer needed (e.g. from an owning component's
     * lifecycle teardown). After [release], this instance must not be
     * reused — construct a new [AudioRecorder] for a new capture session.
     */
    fun release() {
        stop()
        supervisorJob.cancel()
    }

    private fun channelConfig(channelCount: Int): Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_IN_MONO
        2 -> AudioFormat.CHANNEL_IN_STEREO
        else -> throw IllegalArgumentException("Unsupported channelCount: $channelCount")
    }

    private companion object {
        const val BUFFER_SIZE_FRAME_MULTIPLE = 4
    }
}
