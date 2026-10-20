# EchoSphere — Distributed Mobile Audio Sync Engine

> **100% Serverless Peer-to-Peer Synchronized Audio Playback System for Android**
> Acoustic Phase Lock ($\le 1.5$ ms) over Local Wi-Fi and Mobile Hotspot.

---

## 🎧 Overview

**EchoSphere** transforms nearby smartphones into a synchronized multi-speaker array without requiring an internet router or any cloud infrastructure. Whether outdoors around a campfire on a Mobile Hotspot or in a living room on Wi-Fi, EchoSphere delivers acoustic phase alignment ($\le 1.5$ ms to $2.0$ ms) across all connected devices.

- **Mode A (In-App YouTube / YT Music):** Bandwidth-free P2P sync. Host broadcasts track metadata and master timestamps; every device streams directly from YouTube via an embedded WebView IFrame player with a 3-tier Phase-Locked Loop (PLL) adjusting `playbackRate` ($0.97\times - 1.03\times$).
- **Mode B (External System Audio Broadcast):** Universal system audio capture (Spotify, Apple Music, SoundCloud) via Android `MediaProjection` and `AudioPlaybackCapture`. Audio is Opus-encoded (20 ms frames) in C++ and broadcasted via UDP multi-unicast to peers rendering through Google Oboe (AAudio) with low-latency jitter buffers.
- **Sub-Millisecond Clock Synchronization:** 8-burst SNTP over UDP (:5005) with median outlier rejection and EMA smoothing.
- **Hardware Latency Compensation:** 1 ms precision manual nudge slider (-150 ms to +150 ms) and one-tap acoustic chirp auto-calibration using the phone microphone.

---

## 📖 Master Documentation & Implementation Plan

For the full architectural specification, mathematical formulas, UDP packet schemas, and the 5-phase incremental implementation roadmap, please see:

👉 **[PROJECT_PLAN.md](file:///d:/Projects/EchoSphere/PROJECT_PLAN.md)**

---

## 🛠 Tech Stack

- **Platform:** Android (minSdk 29, targetSdk 34)
- **Language:** Kotlin 2.0+ & C++17 (Android NDK)
- **UI:** Jetpack Compose (Material 3)
- **Networking:**
  - mDNS (`NsdManager`) & ZXing QR Code fallback
  - Ktor CIO Embedded WebSocket Server & Client (Port 8080)
  - Java `DatagramSocket` Multi-Unicast UDP (Port 5004 Audio, Port 5005 SNTP)
- **Audio Engine:**
  - Mode A: Android WebView + YouTube IFrame API + JS PLL Loop
  - Mode B: Android `AudioPlaybackCapture` + Xiph `libopus` + Google Oboe (AAudio)
- **Storage:** Jetpack DataStore (Preferences) for per-OEM DAC hardware calibration offsets

---

## 🗺 Implementation Roadmap

- [x] **Phase 1: Zero-Config Discovery & P2P Handshake** (NsdManager, QR code pairing, HomeScreen)
- [x] **Phase 2: Ktor Collaborative Party Queue Server** (WebSocket :8080, RoomScreen, queue state)
- [x] **Phase 3: SNTP Clock Synchronization & Mode A Sync** (UDP :5005, YouTubeSyncPlayer PLL)
- [x] **Phase 4: Mode B Native C++ Audio Engine** (CMake, Oboe, libopus, MediaProjection, UDP :5004)
- [x] **Phase 5: Acoustic Chirp Calibration & Production Polish** (AcousticCalibrator.cpp, DataStore, SyncModal)

---

## 🚀 Getting Started

Open the project folder in **Android Studio (Ladybug / Iguana or later)** with Android NDK and CMake installed.
