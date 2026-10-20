#include "AcousticCalibrator.h"
#include <cmath>
#include <algorithm>
#include <limits>
#if defined(__ANDROID__)
#include <android/log.h>
#define LOG_TAG "EchoSphere-Calibrator"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...)
#endif

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

AcousticCalibrator::AcousticCalibrator() {
    generateChirpTemplate(mReferenceChirp);
}

void AcousticCalibrator::generateChirpTemplate(std::vector<float>& outTemplate) {
    outTemplate.resize(CHIRP_SAMPLES);
    const float T = CHIRP_DURATION_SEC;
    const float k = (FREQ_END - FREQ_START) / T;

    for (int i = 0; i < CHIRP_SAMPLES; ++i) {
        float t = static_cast<float>(i) / static_cast<float>(SAMPLE_RATE);
        // Hann window smoothing
        float window = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI) * i / (CHIRP_SAMPLES - 1)));
        // Linear FM Sweep formula: sin(2*pi*(f0*t + (k/2)*t^2))
        float phase = 2.0f * static_cast<float>(M_PI) * (FREQ_START * t + 0.5f * k * t * t);
        outTemplate[i] = window * std::sin(phase);
    }
}

float AcousticCalibrator::calculateOffsetCorrectionMs(const int16_t* recordedMicPcm, size_t numSamples) {
    if (numSamples < static_cast<size_t>(CHIRP_SAMPLES * 2)) {
        return 0.0f;
    }

    size_t correlationLen = numSamples - CHIRP_SAMPLES;
    std::vector<float> correlation(correlationLen, 0.0f);

    // Compute cross-correlation
    for (size_t n = 0; n < correlationLen; ++n) {
        float sum = 0.0f;
        for (int k = 0; k < CHIRP_SAMPLES; ++k) {
            float sample = static_cast<float>(recordedMicPcm[n + k]) / 32768.0f;
            sum += sample * mReferenceChirp[k];
        }
        correlation[n] = std::abs(sum);
    }

    // Find the first dominant peak (Host chirp arrival)
    auto maxIt1 = std::max_element(correlation.begin(), correlation.end());
    size_t peak1Idx = std::distance(correlation.begin(), maxIt1);

    // Zero out a window around the first peak to find the second distinct peak (Client chirp)
    size_t blankWindow = static_cast<size_t>(SAMPLE_RATE * 0.150); // 150ms window
    size_t blankStart = (peak1Idx > blankWindow) ? (peak1Idx - blankWindow) : 0;
    size_t blankEnd = std::min(correlationLen, peak1Idx + blankWindow);

    for (size_t i = blankStart; i < blankEnd; ++i) {
        correlation[i] = 0.0f;
    }

    // Find the second dominant peak
    auto maxIt2 = std::max_element(correlation.begin(), correlation.end());
    size_t peak2Idx = std::distance(correlation.begin(), maxIt2);

    // Ensure peak1 is earlier than peak2
    size_t firstPeak = std::min(peak1Idx, peak2Idx);
    size_t secondPeak = std::max(peak1Idx, peak2Idx);

    // Measured separation in milliseconds
    float measuredSeparationMs = (static_cast<float>(secondPeak - firstPeak) / static_cast<float>(SAMPLE_RATE)) * 1000.0f;
    // Offset delta = measured - nominal (250ms)
    float deltaOffsetMs = measuredSeparationMs - static_cast<float>(NOMINAL_SEPARATION_MS);

    LOGI("Acoustic Chirp peaks: Peak1=%zu, Peak2=%zu, Measured=%f ms, DeltaOffset=%f ms",
         firstPeak, secondPeak, measuredSeparationMs, deltaOffsetMs);

    return deltaOffsetMs;
}
