#ifndef ECHOSPHERE_AUDIO_ENGINE_H
#define ECHOSPHERE_AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <memory>
#include <atomic>
#include "OpusCodec.h"
#include "JitterBuffer.h"

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    bool start();
    void stop();

    void pushUdpPacket(uint32_t seq, int64_t playAtMasterUs, const uint8_t* payload, size_t len);
    void setClockOffsetUs(int64_t offsetUs);
    void setManualCalibrationUs(int64_t manualUs);

    // Oboe Callback
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *oboeStream,
        void *audioData,
        int32_t numFrames
    ) override;

    size_t getJitterBufferSize() { return mJitterBuffer.size(); }
    uint64_t getDroppedFrames() { return mJitterBuffer.getDroppedFramesCount(); }

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    OpusCodec mCodec;
    JitterBuffer mJitterBuffer;

    std::atomic<int64_t> mClockOffsetUs{0};
    std::atomic<int64_t> mManualCalibrationUs{0};
    std::atomic<bool> mIsRunning{false};

    int64_t getCurrentMasterTimeUs();
};

#endif // ECHOSPHERE_AUDIO_ENGINE_H
