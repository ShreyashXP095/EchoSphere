import math
import random

def verify_phase5_chirp_dsp():
    SAMPLE_RATE = 48000
    CHIRP_DURATION = 0.015 # 15ms
    CHIRP_SAMPLES = int(SAMPLE_RATE * CHIRP_DURATION) # 720 samples
    FREQ_START = 2000.0
    FREQ_END = 6000.0
    NOMINAL_SEPARATION_MS = 250.0

    assert CHIRP_SAMPLES == 720, f"Expected 720 samples, got {CHIRP_SAMPLES}"

    # 1. Generate reference template
    template = []
    T = CHIRP_DURATION
    k = (FREQ_END - FREQ_START) / T
    for i in range(CHIRP_SAMPLES):
        t = i / SAMPLE_RATE
        window = 0.5 * (1.0 - math.cos(2.0 * math.pi * i / (CHIRP_SAMPLES - 1)))
        phase = 2.0 * math.pi * (FREQ_START * t + 0.5 * k * t * t)
        template.append(window * math.sin(phase))

    # 2. Synthesize 1.5 seconds of simulated recorded mic audio
    TOTAL_SAMPLES = int(SAMPLE_RATE * 1.5)
    mic_audio = [random.uniform(-0.02, 0.02) for _ in range(TOTAL_SAMPLES)] # Ambient noise floor

    # Inject True Delay:
    # Physical acoustic + DAC delay = +14.0 ms -> Separation = 250.0 + 14.0 = 264.0 ms
    true_delay_ms = 14.0
    host_chirp_start = 5000 # ~104 ms
    separation_samples = int(SAMPLE_RATE * (NOMINAL_SEPARATION_MS + true_delay_ms) / 1000.0)
    client_chirp_start = host_chirp_start + separation_samples

    # Superimpose chirps
    for i in range(CHIRP_SAMPLES):
        mic_audio[host_chirp_start + i] += template[i] * 0.8
        mic_audio[client_chirp_start + i] += template[i] * 0.7

    # 3. Cross-correlation with template
    corr_len = TOTAL_SAMPLES - CHIRP_SAMPLES
    correlation = [0.0] * corr_len

    # Subsample scan to locate candidate peaks
    step = 2
    for n in range(0, corr_len, step):
        s = sum(mic_audio[n + k] * template[k] for k in range(0, CHIRP_SAMPLES, 2))
        correlation[n] = abs(s)

    # Find first peak
    peak1_idx = max(range(0, corr_len, step), key=lambda x: correlation[x])

    # Blank out region around peak 1
    blank_window = int(SAMPLE_RATE * 0.150)
    b_start = max(0, peak1_idx - blank_window)
    b_end = min(corr_len, peak1_idx + blank_window)
    for i in range(b_start, b_end, step):
        correlation[i] = 0.0

    # Find second peak
    peak2_idx = max(range(0, corr_len, step), key=lambda x: correlation[x])

    p_first = min(peak1_idx, peak2_idx)
    p_second = max(peak1_idx, peak2_idx)

    measured_separation_ms = ((p_second - p_first) / SAMPLE_RATE) * 1000.0
    measured_delay_ms = measured_separation_ms - NOMINAL_SEPARATION_MS

    error_ms = abs(measured_delay_ms - true_delay_ms)
    print(f"Chirp Test: Injected={true_delay_ms}ms, Measured={measured_delay_ms:.2f}ms, Error={error_ms:.2f}ms")

    assert error_ms <= 1.5, f"Acoustic calibration error ({error_ms}ms) exceeded target tolerance (<= 1.5ms)"
    print("PASS: Phase 5 Linear FM Sweep Chirp & Cross-Correlation Auto-Calibration verified within <= 1.5ms!")

if __name__ == "__main__":
    verify_phase5_chirp_dsp()
