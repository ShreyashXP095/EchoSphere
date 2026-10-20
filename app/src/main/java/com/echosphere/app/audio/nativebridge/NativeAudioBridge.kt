package com.echosphere.app.audio.nativebridge

import android.util.Log

object NativeAudioBridge {
    private const val TAG = "NativeAudioBridge"

    init {
        try {
            System.loadLibrary("echosphere_audio")
            nativeInit()
            Log.i(TAG, "libechosphere_audio loaded and initialized successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library libechosphere_audio not loaded (expected during unit tests): ${e.message}")
        }
    }

    external fun nativeInit()
    external fun nativeStartAudio(): Boolean
    external fun nativeStopAudio()
    external fun nativePushUdpPacket(seqNumber: Int, playAtMasterUs: Long, payload: ByteArray)
    external fun nativeSetClockOffsetUs(offsetUs: Long)
    external fun nativeSetManualCalibrationUs(manualUs: Long)
    external fun nativeEncodePcmToOpus(pcmIn: ShortArray, opusOut: ByteArray): Int
    external fun nativeCalculateChirpOffset(recordedPcm: ShortArray): Float
    external fun nativeGenerateChirp(outBuffer: FloatArray)
}
