package com.echosphere.app.audio.calibration

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.echosphere.app.audio.nativebridge.NativeAudioBridge
import kotlinx.coroutines.*
import kotlin.math.roundToLong

class AcousticChirpController(
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "AcousticChirpController"
        const val SAMPLE_RATE = 48000
        const val RECORD_DURATION_MS = 1500L // 1.5 seconds
        const val TOTAL_SAMPLES = (SAMPLE_RATE * RECORD_DURATION_MS / 1000).toInt() // 72,000 samples
    }

    private var chirpAudioTrack: AudioTrack? = null
    private var chirpShorts: ShortArray? = null

    init {
        initChirpPlaybackBuffer()
    }

    private fun initChirpPlaybackBuffer() {
        val chirpFloat = FloatArray(720) // 15ms at 48kHz
        NativeAudioBridge.nativeGenerateChirp(chirpFloat)

        chirpShorts = ShortArray(720) { i ->
            (chirpFloat[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
        }

        val bufferSize = 720 * 2
        chirpAudioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        chirpShorts?.let {
            chirpAudioTrack?.write(it, 0, it.size)
        }
    }

    fun playChirpNow() {
        try {
            chirpAudioTrack?.stop()
            chirpAudioTrack?.reloadStaticData()
            chirpAudioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing calibration chirp", e)
        }
    }

    /**
     * Executes the one-tap calibration routine:
     * 1. Opens 48kHz mono AudioRecord
     * 2. Waits for scheduled T0 + 250ms emission
     * 3. Records 1.5 seconds
     * 4. Runs native C++ cross-correlation
     */
    @SuppressLint("MissingPermission")
    suspend fun runCalibrationRoutine(scheduledChirpTimeMicros: Long): Float = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(TOTAL_SAMPLES * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize
        )

        val recordedSamples = ShortArray(TOTAL_SAMPLES)

        try {
            recorder.startRecording()
            Log.i(TAG, "Acoustic calibration recording started")

            // Schedule client chirp emission at target microsecond timestamp
            val emitJob = launch {
                val nowMicros = SystemClock.elapsedRealtimeNanos() / 1000L
                val waitMicros = scheduledChirpTimeMicros - nowMicros
                if (waitMicros > 0) {
                    delay(waitMicros / 1000L)
                }
                playChirpNow()
                Log.i(TAG, "Emitted client calibration chirp")
            }

            // Read 1.5 seconds of recorded mono mic audio
            var totalRead = 0
            while (totalRead < TOTAL_SAMPLES) {
                val read = recorder.read(recordedSamples, totalRead, TOTAL_SAMPLES - totalRead)
                if (read > 0) {
                    totalRead += read
                } else break
            }

            emitJob.join()

            // Run native cross-correlation peak analysis
            val offsetCorrectionMs = NativeAudioBridge.nativeCalculateChirpOffset(recordedSamples)
            Log.i(TAG, "Calibration result: $offsetCorrectionMs ms")
            offsetCorrectionMs
        } catch (e: Exception) {
            Log.e(TAG, "Error during calibration routine", e)
            0.0f
        } finally {
            try {
                recorder.stop()
                recorder.release()
            } catch (ignored: Exception) {}
        }
    }

    fun release() {
        try {
            chirpAudioTrack?.release()
        } catch (ignored: Exception) {}
        chirpAudioTrack = null
    }
}
