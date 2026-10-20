#ifndef ECHOSPHERE_JITTER_BUFFER_H
#define ECHOSPHERE_JITTER_BUFFER_H

#include <cstdint>
#include <vector>
#include <queue>
#include <mutex>
#include <atomic>

struct AudioFrame {
    uint32_t seqNumber;
    int64_t playAtMasterUs;
    std::vector<uint8_t> payload;

    // Priority queue comparator: earlier playAtMasterUs has higher priority
    bool operator>(const AudioFrame& other) const {
        return playAtMasterUs > other.playAtMasterUs;
    }
};

class JitterBuffer {
public:
    explicit JitterBuffer(size_t maxCapacity = 50);
    ~JitterBuffer() = default;

    // Push newly received UDP Opus packet into jitter buffer
    void pushPacket(uint32_t seqNumber, int64_t playAtMasterUs, const uint8_t* data, size_t len);

    // Pop the frame scheduled to play at targetMasterUs. Returns false on underrun/wait
    bool popFrameForPlayback(int64_t currentMasterUs, AudioFrame& outFrame);

    void clear();

    size_t size();
    uint64_t getDroppedFramesCount() const { return mDroppedLateFrames.load(); }
    uint64_t getUnderrunCount() const { return mUnderrunCount.load(); }

private:
    std::priority_queue<AudioFrame, std::vector<AudioFrame>, std::greater<AudioFrame>> mQueue;
    std::mutex mMutex;
    size_t mMaxCapacity;

    std::atomic<uint64_t> mDroppedLateFrames{0};
    std::atomic<uint64_t> mUnderrunCount{0};
};

#endif // ECHOSPHERE_JITTER_BUFFER_H
