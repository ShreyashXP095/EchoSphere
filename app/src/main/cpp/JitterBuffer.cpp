#include "JitterBuffer.h"

JitterBuffer::JitterBuffer(size_t maxCapacity) : mMaxCapacity(maxCapacity) {}

void JitterBuffer::pushPacket(uint32_t seqNumber, int64_t playAtMasterUs, const uint8_t* data, size_t len) {
    std::lock_guard<std::mutex> lock(mMutex);

    // If buffer is overflowing, drop the oldest packet
    if (mQueue.size() >= mMaxCapacity) {
        mQueue.pop();
        mDroppedLateFrames++;
    }

    AudioFrame frame;
    frame.seqNumber = seqNumber;
    frame.playAtMasterUs = playAtMasterUs;
    frame.payload.assign(data, data + len);

    mQueue.push(std::move(frame));
}

bool JitterBuffer::popFrameForPlayback(int64_t currentMasterUs, AudioFrame& outFrame) {
    std::lock_guard<std::mutex> lock(mMutex);

    if (mQueue.empty()) {
        mUnderrunCount++;
        return false;
    }

    constexpr int64_t LATE_DROP_THRESHOLD_US = 20000; // 20ms tolerance

    // Discard stale frames that arrived too late to be rendered at their target timestamp
    while (!mQueue.empty()) {
        const auto& top = mQueue.top();
        if (top.playAtMasterUs < (currentMasterUs - LATE_DROP_THRESHOLD_US)) {
            mQueue.pop();
            mDroppedLateFrames++;
        } else {
            break;
        }
    }

    if (mQueue.empty()) {
        mUnderrunCount++;
        return false;
    }

    const auto& top = mQueue.top();

    // Check if frame is ready for playback (within +/- 20ms of current master clock)
    if (top.playAtMasterUs <= (currentMasterUs + LATE_DROP_THRESHOLD_US)) {
        outFrame = std::move(const_cast<AudioFrame&>(top));
        mQueue.pop();
        return true;
    }

    // Front frame is scheduled in the future (buffer is absorbing jitter)
    return false;
}

void JitterBuffer::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    while (!mQueue.empty()) {
        mQueue.pop();
    }
}

size_t JitterBuffer::size() {
    std::lock_guard<std::mutex> lock(mMutex);
    return mQueue.size();
}
