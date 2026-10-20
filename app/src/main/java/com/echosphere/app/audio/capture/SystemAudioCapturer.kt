package com.echosphere.app.audio.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.echosphere.app.audio.nativebridge.NativeAudioBridge
import com.echosphere.app.network.udp.UdpAudioTransport
import kotlinx.coroutines.*

class SystemAudioCapturer(
    private val mediaProjection: MediaProjection,
    private val udpAudioTransport: UdpAudioTransport,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "SystemAudioCapturer"
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2 // Stereo
        const val FRAME_SIZE_MS = 20
        const val SAMPLES_PER_FRAME = (SAMPLE_RATE * FRAME_SIZE_MS / 1000) * CHANNELS // 1920 shorts (3840 bytes)
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startCapture() {
        if (captureJob != null) return

        try {
            // Android 10+ AudioPlaybackCaptureConfiguration
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLES_PER_FRAME * 2 * 4)

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize with AudioPlaybackCaptureConfig")
                return
            }

            audioRecord?.startRecording()
            Log.i(TAG, "System Audio Capture started: 48kHz Stereo 20ms frames")

            captureJob = coroutineScope.launch(Dispatchers.IO) {
                val pcmBuffer = ShortArray(SAMPLES_PER_FRAME)
                val opusBuffer = ByteArray(512) // Opus frame typically 280-340 bytes

                while (isActive) {
                    val readCount = audioRecord?.read(pcmBuffer, 0, SAMPLES_PER_FRAME) ?: -1
                    if (readCount == SAMPLES_PER_FRAME) {
                        val captureTimeUs = SystemClock.elapsedRealtimeNanos() / 1000L

                        // Native C++ libopus 20ms frame encoding
                        val encodedBytes = NativeAudioBridge.nativeEncodePcmToOpus(pcmBuffer, opusBuffer)
                        if (encodedBytes > 0) {
                            // Multi-unicast broadcast over UDP port 5004
                            udpAudioTransport.broadcastOpusFrame(opusBuffer, encodedBytes, captureTimeUs)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting system audio capture", e)
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
        Log.i(TAG, "System Audio Capture stopped")
    }
}
