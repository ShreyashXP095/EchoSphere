#include "OpusCodec.h"
#include <opus.h>
#include <android/log.h>

#define LOG_TAG "EchoSphere-Opus"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

OpusCodec::OpusCodec() = default;

OpusCodec::~OpusCodec() {
    if (mEncoder) {
        opus_encoder_destroy(mEncoder);
        mEncoder = nullptr;
    }
    if (mDecoder) {
        opus_decoder_destroy(mDecoder);
        mDecoder = nullptr;
    }
}

bool OpusCodec::initEncoder() {
    int error = 0;
    mEncoder = opus_encoder_create(SAMPLE_RATE, CHANNELS, OPUS_APPLICATION_AUDIO, &error);
    if (error != OPUS_OK || !mEncoder) {
        LOGE("Failed to create Opus encoder: %s", opus_strerror(error));
        return false;
    }

    opus_encoder_ctl(mEncoder, OPUS_SET_BITRATE(BITRATE));
    opus_encoder_ctl(mEncoder, OPUS_SET_VBR(1));
    opus_encoder_ctl(mEncoder, OPUS_SET_COMPLEXITY(8));
    opus_encoder_ctl(mEncoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_MUSIC));

    LOGI("Opus Encoder initialized: 48kHz Stereo, 128kbps");
    return true;
}

bool OpusCodec::initDecoder() {
    int error = 0;
    mDecoder = opus_decoder_create(SAMPLE_RATE, CHANNELS, &error);
    if (error != OPUS_OK || !mDecoder) {
        LOGE("Failed to create Opus decoder: %s", opus_strerror(error));
        return false;
    }

    LOGI("Opus Decoder initialized: 48kHz Stereo");
    return true;
}

int OpusCodec::encode(const int16_t* pcmIn, int samplesPerChannel, uint8_t* opusOut, int maxBytes) {
    if (!mEncoder) {
        if (!initEncoder()) return -1;
    }
    return opus_encode(mEncoder, pcmIn, samplesPerChannel, opusOut, maxBytes);
}

int OpusCodec::decode(const uint8_t* opusIn, int opusBytes, int16_t* pcmOut, int samplesPerChannel) {
    if (!mDecoder) {
        if (!initDecoder()) return -1;
    }
    // If opusIn is null or opusBytes is 0, libopus engages internal Packet Loss Concealment (PLC)
    return opus_decode(mDecoder, opusIn, opusBytes, pcmOut, samplesPerChannel, 0);
}
