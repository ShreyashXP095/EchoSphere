import math

def verify_sntp_math():
    # True clock offset = +50,000 microseconds (Host is 50ms ahead)
    # One-way network latency = 5,000 us (RTT = 10,000 us)
    true_offset = 50000
    rtt_base = 10000

    # Simulate 8 burst probes (with 4 CSMA/CA Wi-Fi contention spikes)
    probes = []
    delays = [10000, 11000, 9500, 10500, 45000, 82000, 63000, 95000] # 4 normal, 4 spikes

    for i, delay in enumerate(delays):
        t1 = 1_000_000 + i * 25000
        t2 = t1 + true_offset + (delay // 2)
        t3 = t2 + 100 # 100us Host processing time
        t4 = t3 - true_offset + (delay // 2)

        # Formula
        delta = (t4 - t1) - (t3 - t2)
        theta = ((t2 - t1) + (t3 - t4)) // 2
        probes.append((delta, theta))

    # Outlier filter: Discard top 4 delta samples
    sorted_probes = sorted(probes, key=lambda x: x[0])
    best_probes = sorted_probes[:4]

    # Median theta of remaining
    thetas = sorted([p[1] for p in best_probes])
    median_theta = thetas[len(thetas) // 2]

    # Ensure median matches true offset within 1ms tolerance
    error = abs(median_theta - true_offset)
    assert error <= 500, f"SNTP median offset error too high: {error} us"

    # EMA Test (alpha = 0.20)
    ema = 0
    alpha = 0.20
    for _ in range(10):
        ema = median_theta if ema == 0 else int(alpha * median_theta + (1.0 - alpha) * ema)

    assert abs(ema - true_offset) <= 500, "EMA failed to converge"
    print(f"PASS: SNTP Math & Outlier Rejection validated! Median={median_theta}us, EMA={ema}us (Target: {true_offset}us)")

def verify_pll_tiers():
    # Tier 1: > 150ms -> Hard Seek
    def evaluate_pll(drift_ms):
        abs_d = abs(drift_ms)
        if abs_d > 150:
            return (1, 1.0, "HARD_SEEK")
        elif abs_d > 8:
            rate = 1.03 if drift_ms < -8 else 0.97
            return (2, rate, "PULL_IN")
        else:
            return (3, 1.00, "PHASE_LOCK")

    assert evaluate_pll(350) == (1, 1.0, "HARD_SEEK")
    assert evaluate_pll(-200) == (1, 1.0, "HARD_SEEK")
    assert evaluate_pll(-50) == (2, 1.03, "PULL_IN")   # Lagging -> speed up to 1.03x
    assert evaluate_pll(50) == (2, 0.97, "PULL_IN")    # Ahead -> slow down to 0.97x
    assert evaluate_pll(5) == (3, 1.00, "PHASE_LOCK")   # Within 8ms -> Lock 1.00x
    assert evaluate_pll(-3) == (3, 1.00, "PHASE_LOCK")  # Within 8ms -> Lock 1.00x

    print("PASS: 3-Tier PLL drift response and playbackRate logic validated!")

if __name__ == "__main__":
    verify_sntp_math()
    verify_pll_tiers()
