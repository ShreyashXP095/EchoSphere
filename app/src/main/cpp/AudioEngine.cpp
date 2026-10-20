#include "AudioEngine.h"
#include <cstring>
#include <ctime>
#include <android/log.h>

#define LOG_TAG "EchoSphere-AudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

AudioEngine::AudioEngine() {
    mCodec.initDecoder();
}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start() {
    if (mIsRunning.load()) return true;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(OpusCodec::SAMPLE_RATE)
        ->setFramesPerDataCallback(OpusCodec::SAMPLES_PER_FRAME_PER_CHANNEL) // 960 frames (20ms)
        ->setDataCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
        return false;
    }

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
        mStream->close();
        return false;
    }

    mIsRunning.store(true);
    LOGI("AudioEngine low-latency Oboe stream started successfully");
    return true;
}

void AudioEngine::stop() {
    if (!mIsRunning.load()) return;
    mIsRunning.store(false);

    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
    mJitterBuffer.clear();
    LOGI("AudioEngine stream stopped");
}

void AudioEngine::pushUdpPacket(uint32_t seq, int64_t playAtMasterUs, const uint8_t* payload, size_t len) {
    mJitterBuffer.pushPacket(seq, playAtMasterUs, payload, len);
}

void AudioEngine::setClockOffsetUs(int64_t offsetUs) {
    mClockOffsetUs.store(offsetUs);
}

void AudioEngine::setManualCalibrationUs(int64_t manualUs) {
    mManualCalibrationUs.store(manualUs);
}

int64_t AudioEngine::getCurrentMasterTimeUs() {
    struct timespec ts;
    clock_gettime(CLOCK_BOOTTIME, &ts);
    int64_t localUs = static_cast<int64_t>(ts.tv_sec) * 1000000LL + ts.tv_nsec / 1000LL;
    return localUs + mClockOffsetUs.load() + mManualCalibrationUs.load();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream *oboeStream,
    void *audioData,
    int32_t numFrames
) {
    auto* outputBuffer = static_cast<int16_t*>(audioData);
    int64_t currentMasterTimeUs = getCurrentMasterTimeUs();

    AudioFrame frame;
    bool hasFrame = mJitterBuffer.popFrameForPlayback(currentMasterTimeUs, frame);

    if (hasFrame && !frame.payload.empty()) {
        // Decode Opus packet to 20ms stereo PCM
        int decodedSamples = mCodec.decode(
            frame.payload.data(),
            static_cast<int>(frame.payload.size()),
            outputBuffer,
            numFrames
        );
        if (decodedSamples < 0) {
            // Decoding failed, fill with silence
            std::memset(audioData, 0, numFrames * OpusCodec::CHANNELS * sizeof(int16_t));
        }
    } else {
        // Packet loss concealment (PLC) or zero-stuffing
        int plcSamples = mCodec.decode(nullptr, 0, outputBuffer, numFrames);
        if (plcSamples < 0) {
            std::memset(audioData, 0, numFrames * OpusCodec::CHANNELS * sizeof(int16_t));
        }
    }

    return oboe::DataCallbackResult::Continue;
}
