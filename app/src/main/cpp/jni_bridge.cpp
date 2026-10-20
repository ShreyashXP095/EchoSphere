#include <jni.h>
#include <memory>
#include "AudioEngine.h"
#include "OpusCodec.h"
#include "AcousticCalibrator.h"

static std::unique_ptr<AudioEngine> gAudioEngine;
static std::unique_ptr<OpusCodec> gEncoderCodec;
static std::unique_ptr<AcousticCalibrator> gCalibrator;

extern "C" {

JNIEXPORT void JNICALL
Java_com_echosphere_app_audio_nativebridge_NativeAudioBridge_nativeInit(JNIEnv *env, jobject thiz) {
    if (!gAudioEngine) {
        gAudioEngine = std::make_unique<AudioEngine>();
    }
    if (!gEncoderCodec) {
        gEncoderCodec = std::make_unique<OpusCodec>();
        gEncoderCodec->initEncoder();
    }
    if (!gCalibrator) {
        gCalibrator = std::make_unique<AcousticCalibrator>();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_echosphere_app_audio_nativebridge_NativeAudioBridge_nativeStartAudio(JNIEnv *env, jobject thiz) {
    if (gAudioEngine) {
        return gAudioEngine->start();
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_echosphere_app_audio_nativebridge_NativeAudioBridge_nativeStopAudio(JNIEnv *env, jobject thiz) {
    if (gAudioEngine) {
        gAudioEngine->stop();
    }
}

JNIEXPORT void JNICALL
Java_com_echosphere_app_audio_nativebridge_NativeAudioBridge_nativePushUdpPacket(
    JNIEnv *env, jobject thiz,
    jint seqNumber,
    jlong playAtMasterUs,
    jbyteArray payload
) {
    if (!gAudioEngine) return;

    jsize len = env->GetArrayLength(payload);
    jbyte *bytes = env->GetByteArrayElements(payload, nullptr);
    if (bytes) {
        gAudioEngine->pushUdpPacket(
            static_cast<uint32_t>(seqNumber),
            static_cast<int64_t>(playAtMasterUs),
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<size_t>(len)
        );
        env->ReleaseByteArrayElements(payload, bytes, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_echosphere_app_audio_nativebridge_NativeAudioBridge_nativeSetClockOffsetUs(
    JNIEnv *env, jobject thiz,
    jlong offsetUs
) {
    if (gAudioEngine) {
        gAudioEngine->setClockOffsetUs(static_cast<int64_t>(offsetUs));
    }
}

JNIEXPORT void JNICALL
Java_com_echosphere_app_audio_nativebridge_NativeAudioBridge_nativeSetManualCalibrationUs(
    JNIEnv *env, jobject thiz,
    jlong manualUs
) {
    if (gAudioEngine) {
        gAudioEngine->setManualCalibrationUs(static_cast<int64_t>(manualUs));
    }
}

JNIEXPORT jint JNICALL
Java_com_echosphere_app_audio_nativebridge_NativeAudioBridge_nativeEncodePcmToOpus(
    JNIEnv *env, jobject thiz,
    jshortArray pcmIn,
    jbyteArray opusOut
) {
    if (!gEncoderCodec) return -1;

    jsize pcmLen = env->GetArrayLength(pcmIn);
    jsize maxOpusBytes = env->GetArrayLength(opusOut);

    jshort *pcmData = env->GetShortArrayElements(pcmIn, nullptr);
    jbyte *opusData = env->GetByteArrayElements(opusOut, nullptr);

    int encodedBytes = -1;
    if (pcmData && opusData) {
        // samplesPerChannel = 960 (pcmLen / 2)
        int samplesPerChannel = pcmLen / OpusCodec::CHANNELS;
        encodedBytes = gEncoderCodec->encode(
            reinterpret_cast<const int16_t*>(pcmData),
            samplesPerChannel,
            reinterpret_cast<uint8_t*>(opusData),
            maxOpusBytes
        );
    }

    if (pcmData) env->ReleaseShortArrayElements(pcmIn, pcmData, JNI_ABORT);
    if (opusData) env->ReleaseByteArrayElements(opusOut, opusData, 0);

    return encodedBytes;
}

JNIEXPORT jfloat JNICALL
Java_com_echosphere_app_audio_nativebridge_NativeAudioBridge_nativeCalculateChirpOffset(
    JNIEnv *env, jobject thiz,
    jshortArray recordedPcm
) {
    if (!gCalibrator) return 0.0f;

    jsize len = env->GetArrayLength(recordedPcm);
    jshort *samples = env->GetShortArrayElements(recordedPcm, nullptr);

    float resultMs = 0.0f;
    if (samples) {
        resultMs = gCalibrator->calculateOffsetCorrectionMs(
            reinterpret_cast<const int16_t*>(samples),
            static_cast<size_t>(len)
        );
        env->ReleaseShortArrayElements(recordedPcm, samples, JNI_ABORT);
    }
    return resultMs;
}

JNIEXPORT void JNICALL
Java_com_echosphere_app_audio_nativebridge_NativeAudioBridge_nativeGenerateChirp(
    JNIEnv *env, jobject thiz,
    jfloatArray outBuffer
) {
    if (!gCalibrator) return;

    jfloat *buffer = env->GetFloatArrayElements(outBuffer, nullptr);
    if (buffer) {
        std::vector<float> chirp;
        gCalibrator->generateChirpTemplate(chirp);
        for (size_t i = 0; i < chirp.size(); ++i) {
            buffer[i] = chirp[i];
        }
        env->ReleaseFloatArrayElements(outBuffer, buffer, 0);
    }
}

} // extern "C"
