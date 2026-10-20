#ifndef ECHOSPHERE_ACOUSTIC_CALIBRATOR_H
#define ECHOSPHERE_ACOUSTIC_CALIBRATOR_H

#include <vector>
#include <cstdint>

class AcousticCalibrator {
public:
    static constexpr int SAMPLE_RATE = 48000;
    static constexpr float CHIRP_DURATION_SEC = 0.015f; // 15ms
    static constexpr int CHIRP_SAMPLES = static_cast<int>(SAMPLE_RATE * CHIRP_DURATION_SEC); // 720 samples
    static constexpr float FREQ_START = 2000.0f; // 2 kHz
    static constexpr float FREQ_END = 6000.0f;   // 6 kHz
    static constexpr int NOMINAL_SEPARATION_MS = 250; // 250 ms delay between Host and Client chirp

    AcousticCalibrator();

    // Generate 15ms linear FM sweep template (2kHz -> 6kHz)
    void generateChirpTemplate(std::vector<float>& outTemplate);

    // Cross-correlate recorded microphone stream (48kHz mono) against reference chirp
    // Returns detected offset correction in milliseconds
    float calculateOffsetCorrectionMs(const int16_t* recordedMicPcm, size_t numSamples);

private:
    std::vector<float> mReferenceChirp;
};

#endif // ECHOSPHERE_ACOUSTIC_CALIBRATOR_H
