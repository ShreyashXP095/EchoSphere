#ifndef ECHOSPHERE_OPUS_CODEC_H
#define ECHOSPHERE_OPUS_CODEC_H

#include <cstdint>
#include <vector>

// Forward declare Opus types to avoid header leaks
struct OpusEncoder;
struct OpusDecoder;

class OpusCodec {
public:
    static constexpr int SAMPLE_RATE = 48000;
    static constexpr int CHANNELS = 2;
    static constexpr int FRAME_SIZE_MS = 20;
    static constexpr int SAMPLES_PER_FRAME_PER_CHANNEL = 960; // 48000 * 0.020
    static constexpr int PCM_FRAME_BYTES = SAMPLES_PER_FRAME_PER_CHANNEL * CHANNELS * sizeof(int16_t); // 3840 bytes
    static constexpr int BITRATE = 128000; // 128 kbps

    OpusCodec();
    ~OpusCodec();

    bool initEncoder();
    bool initDecoder();

    // Encode 20ms stereo PCM (3840 bytes) to Opus frame (~320 bytes)
    int encode(const int16_t* pcmIn, int samplesPerChannel, uint8_t* opusOut, int maxBytes);

    // Decode Opus frame to 20ms stereo PCM. Supports PLC if opusIn is nullptr
    int decode(const uint8_t* opusIn, int opusBytes, int16_t* pcmOut, int samplesPerChannel);

private:
    OpusEncoder* mEncoder = nullptr;
    OpusDecoder* mDecoder = nullptr;
};

#endif // ECHOSPHERE_OPUS_CODEC_H
