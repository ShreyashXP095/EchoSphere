# EchoSphere (MusicSphere) — Distributed Mobile Audio Sync Engine
## Master Architectural Specification & Phased Implementation Plan

---

## 1. Executive Summary & Vision

**EchoSphere** (referred to in the technical blueprint as **MusicSphere**) is a 100% serverless, peer-to-peer (P2P) distributed mobile audio synchronization engine for Android. It allows multiple smartphones in the same physical space to form an ad-hoc multi-speaker sound system over a local Wi-Fi network or a Mobile Hotspot without requiring any cloud server or internet relay.

The system delivers acoustic phase synchronization within **≤ 1.5 ms to 2.0 ms** of clock accuracy, eliminating comb filtering, echo, and Haas precedence effects.

### Core Capabilities
1. **Serverless P2P Architecture:** One device acts as the **Host (Master Node)** running an embedded Ktor CIO WebSocket server, an SNTP UDP master clock authority, and an audio broadcast pipeline. Client devices connect as **Peers (Client Nodes)**.
2. **Dual-Engine Playback Architecture:**
   - **Mode A (In-App YouTube / YT Music):** Bandwidth-efficient stream sync where every phone streams directly from YouTube via an embedded WebView IFrame player. The Host broadcasts metadata and playback timestamps; clients align their playback using a 3-tier Phase-Locked Loop (PLL) adjusting HTML5 `playbackRate` (0.97× – 1.03×).
   - **Mode B (External System Audio Broadcast):** Universal system audio capture (Spotify, Apple Music, SoundCloud, local files) using Android's `MediaProjection` and `AudioPlaybackCapture` API. Audio is encoded into 20ms Opus frames via native C++ (libopus), broadcasted via UDP multi-unicast (port 5004), and rendered by peers using Google Oboe (AAudio) with low-latency jitter buffers.
3. **Sub-Millisecond Clock Synchronization:** 8-burst SNTP over UDP (port 5005) with statistical outlier rejection and Exponential Moving Average (EMA) smoothing.
4. **Hardware Latency & Acoustic Calibration:**
   - Manual Sync Nudge Slider (-150 ms to +150 ms at 1 ms precision).
   - Automated One-Tap Acoustic Chirp Calibration using cross-correlation of linear FM chirps (2 kHz → 6 kHz) captured via the phone microphone.

---

## 2. Psychoacoustic Synchronization Thresholds & Mitigation Strategies

When multiple speakers reproduce identical audio simultaneously in an enclosed space, acoustic wave superposition introduces phase phenomena based on inter-device offset ($\Delta t$):

| Desync Offset ($\Delta t$) | Acoustic Phenomenon | Perceptual Result in Room | Mitigation Strategy |
| :--- | :--- | :--- | :--- |
| **0.0 to 1.5 ms** | **Phase Alignment** | Tight, unified stereo/multi-speaker soundstage; enhanced volume and punch. | **Target Lock:** Maintain `playbackRate = 1.00×`; jitter buffer in nominal steady state. |
| **1.5 to 10.0 ms** | **Comb Filtering** | Hollow, metallic, or robotic "flanging" cancellation of high frequencies. | Micro-resampling ($\pm 0.5\%$), Acoustic Chirp Auto-Calibration, and fine nudge offset. |
| **10.0 to 30.0 ms** | **Haas (Precedence) Effect** | Sound localizes solely to the earliest phone; muddy transients; stereo collapse. | Dynamic 3-tier PLL drift correction (`0.97×` or `1.03×` tempo pull-in). |
| **> 30.0 ms** | **Slapback Echo** | Distinct stadium echo; unbearable acoustic clash for group listening. | Hard buffer seek (`player.seekTo()`), ring buffer frame drop, or silence zero-stuffing. |

---

## 3. System Architecture & Topology

```
+---------------------------------------------------------------------------------------+
|                               HOST PHONE (Master Node)                                |
|                                                                                       |
|  +--------------------+   +---------------------------+   +------------------------+  |
|  | Ktor WS Server     |   | SystemAudioCapturer       |   | Master Clock Authority |  |
|  | Port :8080         |   | AudioPlaybackCapture      |   | UDP Port :5005         |  |
|  | - Party Queue      |   | -> JNI libopus Encoder    |   | - SNTP Monotonic Clock |  |
|  | - YT Sync Dispatch |   | -> Multi-Unicast UDP 5004 |   |   Replies (t2, t3)     |  |
|  +--------------------+   +---------------------------+   +------------------------+  |
+-------------------------------------------|-------------------------------------------+
                                            |
                         LOCAL WI-FI / MOBILE HOTSPOT LAN
                         * mDNS Discovery: _musicsphere._tcp / _echosphere._tcp
                         * QR Code Fallback (Host IP + Port)
                         * WS :8080 | UDP :5004 (Opus) | UDP :5005 (SNTP)
                                            |
+-------------------------------------------v-------------------------------------------+
|                              CLIENT PHONES (Peers 1..N)                               |
|                                                                                       |
|  +--------------------+   +---------------------------+   +------------------------+  |
|  | Ktor WS Client     |   | Mode B: Media Receiver    |   | ClockSyncEngine        |  |
|  | - Queue Sync       |   | - UDP 5004 JitterBuffer   |   | - 8-Burst SNTP Probes  |  |
|  | - State Events     |   | - C++ libopus Decoder     |   | - Outlier Filter + EMA |  |
|  |                    |   | - Google Oboe (AAudio DAC)|   | -> Theta (Clock Offset)|  |
|  +--------------------+   +---------------------------+   +------------------------+  |
|                                         |                                              |
|                           +-------------v-------------+                                |
|                           | Mode A: YouTube Player    |                                |
|                           | - WebView IFrame API      |                                |
|                           | - 3-Tier JS PLL Controller|                                |
|                           +---------------------------+                                |
+---------------------------------------------------------------------------------------+
```

---

## 4. Deep-Dive Subsystem Specifications

### 4.1 Subsystem 1: Zero-Config Discovery & P2P Handshake
- **Primary Protocol:** Android `NsdManager` (mDNS / DNS-SD).
  - Service Type: `_musicsphere._tcp.` (or `_echosphere._tcp.`).
  - TXT Record: Protocol version, Room Name, Host Device Model.
- **Fallback Protocol:** ZXing QR Code Generation & Scanning.
  - Required because Android Mobile Hotspot routers frequently disable multicast/mDNS between connected clients.
  - QR Code payload: JSON `{"ip":"192.168.43.1","port":8080,"name":"HostRoom","v":1}`.

### 4.2 Subsystem 2: High-Precision SNTP Clock Synchronization (UDP Port 5005)
Phones cannot rely on standard wall clocks (`System.currentTimeMillis()`) due to NTP jumps and crystal oscillator skew.
- **Clock Source:** `SystemClock.elapsedRealtimeNanos()` converted to microseconds ($\mu s$).
- **Exchange Procedure:**
  1. Every 2.0 seconds, each Client sends a burst of **8 UDP probe packets** spaced 25 ms apart to the Host on port 5005.
  2. Timestamps collected:
     - $t_1$: Client send timestamp
     - $t_2$: Host receive timestamp
     - $t_3$: Host transmit reply timestamp
     - $t_4$: Client receive timestamp
  3. **Formulas:**
     $$\text{Round-Trip Network Delay } (\delta) = (t_4 - t_1) - (t_3 - t_2)$$
     $$\text{Clock Offset } (\theta) = \frac{(t_2 - t_1) + (t_3 - t_4)}{2}$$
  4. **Statistical Outlier Rejection:**
     - Discard the 4 packets with the highest $\delta$ (filtering out Wi-Fi CSMA/CA contention spikes and retransmissions).
     - Calculate the median $\theta$ of the remaining 4 packets.
     - Smooth via Exponential Moving Average (EMA) with factor $\alpha = 0.20$:
       $$\theta_{\text{EMA}} = \alpha \cdot \theta_{\text{median}} + (1 - \alpha) \cdot \theta_{\text{prev}}$$
  5. **Master Clock Projection:**
     $$T_{\text{master}} = T_{\text{client\_monotonic}} + \theta_{\text{EMA}} + \Delta T_{\text{manual\_calibration}}$$

### 4.3 Subsystem 3: Mode A — In-App YouTube Sync & 3-Tier PLL
- **Architecture:** Embedded WebView executing the YouTube IFrame API. Audio streams directly from YouTube servers over cellular or Wi-Fi, using near-zero local LAN bandwidth.
- **Broadcast:** Host broadcasts a `SYNC_STATE` JSON frame over WebSocket every 3 seconds or immediately upon play/pause/seek:
  ```json
  {
    "type": "SYNC_STATE",
    "videoId": "dQw4w9WgXcQ",
    "trackPositionMs": 42150,
    "atMasterClockMs": 178239012,
    "isPlaying": true
  }
  ```
- **Client Drift Calculation (evaluated every 250 ms):**
  $$E_{\text{drift}} = P_{\text{actual\_webview}} - \left[ P_{\text{host\_track}} + (T_{\text{master}} - T_{\text{at\_master}}) \right]$$
- **3-Tier Phase-Locked Loop (PLL):**
  - **Tier 1 — Hard Seek ($|E_{\text{drift}}| > 150\text{ ms}$):**
    Executes `player.seekTo(expectedSec, true)` and resets playback rate to `1.00×`.
  - **Tier 2 — Proportional Pitch-Safe Pull-In ($8\text{ ms} < |E_{\text{drift}}| \le 150\text{ ms}$):**
    - If client is lagging ($E_{\text{drift}} < -8\text{ ms}$): Set `video.playbackRate = 1.03`.
    - If client is ahead ($E_{\text{drift}} > +8\text{ ms}$): Set `video.playbackRate = 0.97`.
    - *A $\pm 3\%$ pitch shift is psychoacoustically transparent to the human ear and closes a 30 ms drift within 1.0 second.*
  - **Tier 3 — Phase Lock ($|E_{\text{drift}}| \le 8\text{ ms}$):**
    Restores `video.playbackRate = 1.00`.

### 4.4 Subsystem 4: Mode B — External System Audio Broadcast (Native C++ / Oboe / Opus)
- **Audio Capture:** Android `MediaProjection` creates an `AudioPlaybackCaptureConfiguration` matching UID rules. An `AudioRecord` instance captures raw 48 kHz 16-bit Stereo PCM (2 channels $\times$ 2 bytes = 4 bytes/sample $\rightarrow$ 3,840 bytes per 20 ms frame).
- **Opus Encoding (libopus C++):**
  - 20 ms frame size (960 samples/channel).
  - Encoded at 128 kbps stereo VBR/CBR $\rightarrow$ ~320 bytes per frame.
- **UDP Audio Packet Schema (Port 5004 - 16-Byte Binary Header):**
  ```
  0                   1                   2                   3
  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
  |          magic (0x4D53)       |          seqNumber            |
  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
  |       seqNumber (cont.)       |       playAtMasterUs          |
  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
  |                   playAtMasterUs (cont.)                      |
  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
  |   playAtMasterUs (cont.)      |          payloadLen           |
  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
  |                     opusPayload (variable) ...                |
  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
  ```
  - Byte 0..1: `magic` (`0x4D53` = "MS" Big Endian). Packets with mismatch are immediately dropped.
  - Byte 2..5: `seqNumber` (`uint32_t` BE) for packet loss concealment (PLC).
  - Byte 6..13: `playAtMasterUs` (`int64_t` BE) target DAC presentation timestamp ($T_{\text{play}} = T_{\text{capture}} + 120,000\ \mu s$).
  - Byte 14..15: `payloadLen` (`uint16_t` BE).
  - Byte 16..N: `opusPayload` raw encoded bytes.
- **Client Playback (Google Oboe / AAudio):**
  - Native `JitterBuffer` priority queue ordered by `playAtMasterUs`.
  - Oboe `AudioStreamDataCallback` requests frames in real-time. If the current frame's presentation timestamp has not arrived, zero-stuffing/PLC interpolation is used. Late frames (> 20 ms past target) are dropped.

### 4.5 Subsystem 5: Hardware DAC Latency & Acoustic Calibration
- **Hardware Latency Discrepancy:** Different Android devices have disparate hardware/HAL buffer delays (e.g., Pixel: ~15 ms; Samsung: ~35 ms; Budget OEM: ~65 ms).
- **Manual Sync Nudge Slider:** Exposes a persistent slider (-150 ms to +150 ms at 1 ms steps) in Jetpack DataStore keyed by `Build.MANUFACTURER + "_" + Build.MODEL`.
- **Automated Acoustic Chirp Calibration:**
  1. Client sends `REQ_CHIRP` over WebSocket to Host.
  2. Client starts 48 kHz mono `AudioRecord` mic stream for 1.5 seconds.
  3. Host plays a 15 ms linear FM sweep ($2\text{ kHz} \rightarrow 6\text{ kHz}$) at $T_0$.
  4. Client plays the identical 15 ms linear FM sweep at $T_0 + 250\text{ ms}$.
  5. C++ `AcousticCalibrator.cpp` performs cross-correlation against the known FM chirp reference kernel to locate the two correlation peaks.
  6. The time difference between the peaks minus the nominal 250 ms delay determines the precise acoustic propagation + DAC hardware offset:
     $$\Delta T_{\text{calibrated}} = (\text{peak}_2 - \text{peak}_1) - 250\text{ ms}$$
  7. Value is saved automatically to Jetpack DataStore.

---

## 5. Technology Stack & Key Dependencies

| Layer | Library / Technology | Version / Configuration | Purpose |
| :--- | :--- | :--- | :--- |
| **Language & Toolchain** | Kotlin + Coroutines + Flow | Kotlin 2.0+, Java 17 | Core application logic and reactive asynchronous streams |
| **UI Framework** | Jetpack Compose + Material 3 | Compose BOM latest | Modern declarative UI, party queue, diagnostic telemetry |
| **Zero-Config Discovery** | Android `NsdManager` + ZXing Android Embedded | ZXing 3.5.x | mDNS broadcast/discovery and QR fallback pairing |
| **Embedded Server & Client**| Ktor CIO Engine | Ktor 2.3.x / 3.x | Lightweight embedded WebSocket server & client on port 8080 |
| **Time Sync & Transport** | Java `DatagramSocket` | Multi-unicast UDP | Low-overhead UDP clock sync (:5005) and audio packet transport (:5004) |
| **System Audio Capture** | `MediaProjection` + `AudioPlaybackCapture` | minSdk = 29 (Android 10+) | Capture internal system audio from Spotify/YT/Music players |
| **Native Audio Engine** | Google Oboe + C++17 NDK | Oboe 1.8+ (via CMake FetchContent) | AAudio low-latency audio rendering callback |
| **Audio Codec** | Xiph `libopus` | 1.4+ (via CMake FetchContent) | 20 ms frame compression/decompression at 48 kHz stereo |
| **Persistent Storage** | Jetpack DataStore (Preferences) | AndroidX DataStore | Persisting per-OEM DAC calibration offsets |

---

## 6. Repository & Module Directory Layout

```
EchoSphere/
├── app/
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── cpp/                                # C++17 NDK Real-Time Audio Engine
│   │   │   │   ├── CMakeLists.txt                  # Pulls Oboe & libopus via FetchContent
│   │   │   │   ├── jni_bridge.cpp                  # JNI interfaces to NativeAudioBridge.kt
│   │   │   │   ├── AudioEngine.h / .cpp            # Oboe callback & playback loop
│   │   │   │   ├── OpusCodec.h / .cpp              # 48kHz Stereo Opus encoder & decoder
│   │   │   │   ├── JitterBuffer.h / .cpp           # Timestamped frame priority queue
│   │   │   │   └── AcousticCalibrator.h / .cpp     # Cross-correlation FM chirp detector
│   │   │   ├── java/com/echosphere/app/
│   │   │   │   ├── data/
│   │   │   │   │   ├── model/                      # QueueItem, RoomState, SyncPacket
│   │   │   │   │   └── preferences/                # DeviceCalibrationStore.kt
│   │   │   │   ├── network/
│   │   │   │   │   ├── nsd/                        # NsdDiscoveryManager.kt
│   │   │   │   │   ├── qr/                         # QrCodeManager.kt
│   │   │   │   │   ├── ws/                         # HostWebSocketServer.kt, ClientWebSocketManager.kt
│   │   │   │   │   └── udp/                        # ClockSyncEngine.kt, UdpAudioTransport.kt
│   │   │   │   ├── audio/
│   │   │   │   │   ├── service/                    # MediaCaptureService.kt (Foreground Service)
│   │   │   │   │   ├── capture/                    # SystemAudioCapturer.kt
│   │   │   │   │   ├── youtube/                    # YouTubeSyncPlayer.kt (WebView + PLL)
│   │   │   │   │   └── nativebridge/               # NativeAudioBridge.kt
│   │   │   │   └── ui/
│   │   │   │       ├── HomeScreen.kt               # Host/Join, Discovery, QR Scanner
│   │   │   │       ├── RoomScreen.kt               # Collaborative Queue, Now Playing, Mode toggle
│   │   │   │       ├── SyncModal.kt                # Nudge Slider, Chirp Auto-Calibrator, Telemetry
│   │   │   │       └── theme/                      # Theme, Color, Type
│   │   │   └── res/
│   │   │       └── values/                         # strings.xml, colors.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 7. Phased Implementation Roadmap (Step-by-Step)

To guarantee stability, zero regressions, and clear verification at every stage, development proceeds through **5 incremental milestones**:

```
+-----------------------------------------------------------------------------------+
| Milestone 1: Foundation, Discovery & P2P Handshake                                |
| - Android project structure (minSdk=29, Kotlin 2.0, Compose M3, C++ CMake)        |
| - NsdDiscoveryManager (mDNS) & ZXing QR Generator/Scanner                         |
| - HomeScreen: Host Room / Join Room workflow                                      |
+-----------------------------------------|-----------------------------------------+
                                          v
+-----------------------------------------------------------------------------------+
| Milestone 2: Collaborative Party Queue & WebSocket Control Plane                  |
| - Ktor Embedded CIO WebSocket Server (:8080) on Host                              |
| - ClientWebSocketManager with automatic reconnection                              |
| - RoomScreen: Collaborative queue additions, voting, track switching              |
+-----------------------------------------|-----------------------------------------+
                                          v
+-----------------------------------------------------------------------------------+
| Milestone 3: Sub-Millisecond Clock Sync & Mode A (YouTube PLL)                    |
| - UDP :5005 8-burst SNTP ClockSyncEngine (Median outlier rejection + EMA)         |
| - YouTubeSyncPlayer: Embedded WebView with IFrame JS Bridge                       |
| - 3-tier PLL playbackRate drift correction (0.97x - 1.03x)                        |
+-----------------------------------------|-----------------------------------------+
                                          v
+-----------------------------------------------------------------------------------+
| Milestone 4: Mode B Native Engine (MediaProjection + Opus + Oboe UDP Stream)      |
| - CMakeLists.txt FetchContent setup for Google Oboe & libopus                     |
| - AudioPlaybackCapture MediaProjection service                                    |
| - JNI Opus 20ms encoder & UDP :5004 multi-unicast transmitter                    |
| - Oboe AAudio receiver & scheduled JitterBuffer player                            |
+-----------------------------------------|-----------------------------------------+
                                          v
+-----------------------------------------------------------------------------------+
| Milestone 5: Hardware DAC Latency & Acoustic Chirp Auto-Calibration               |
| - Manual Sync Nudge Slider with 1ms precision persisted in DataStore              |
| - AcousticCalibrator.cpp FM chirp generator (2-6 kHz) & cross-correlation detector|
| - SyncModal UI & live diagnostic telemetry (RTT, drift ms, packet loss %)         |
+-----------------------------------------------------------------------------------+
```

### Milestone 1: Discovery & P2P Handshake
- **Objective:** Allow two devices on the same Wi-Fi or Mobile Hotspot to discover each other and establish a network route.
- **Deliverables:**
  - Standard Gradle configuration (`minSdk = 29`, `targetSdk = 34`, Kotlin 2.0, Jetpack Compose Material 3, NDK CMake configuration).
  - `NsdDiscoveryManager.kt`: Registers `_echosphere._tcp.` service on Host; discovers services on Client.
  - `QrCodeManager.kt`: Encodes Host IP and port into QR bitmap; scans via camera.
  - `HomeScreen.kt`: Polished UI for "Host a Session" or "Join a Session" with discovery list and QR scan option.
- **Verification Criteria:**
  - Two Android devices discover each other over mDNS in `< 2.0 seconds`.
  - When connected to a Mobile Hotspot that blocks mDNS, scanning the Host's QR code connects instantly.

### Milestone 2: Ktor Party Queue Server & WebSocket Control Plane
- **Objective:** Establish the two-way control plane for real-time room state management and collaborative track queuing.
- **Deliverables:**
  - `HostWebSocketServer.kt`: Ktor CIO embedded server running on port 8080, handling `JOIN`, `QUEUE_ADD`, `QUEUE_VOTE`, `PLAY_TRACK`, and `SYNC_STATE` events.
  - `ClientWebSocketManager.kt`: Ktor client with heartbeats, auto-reconnect, and state flows.
  - `QueueItem.kt` & `RoomState.kt`: Reactive data models.
  - `RoomScreen.kt`: Real-time queue view, voting, search input, and track controls.
- **Verification Criteria:**
  - Multiple clients connect to the Host WebSocket server simultaneously.
  - Adding or voting on a track on one device reflects on all devices in `< 50 ms`.

### Milestone 3: SNTP Clock Synchronization & Mode A (YouTube PLL)
- **Objective:** Achieve sub-millisecond clock projection and synchronized YouTube audio playback across all devices.
- **Deliverables:**
  - `ClockSyncEngine.kt`: UDP port 5005 8-probe burst protocol, outlier rejection, median filter, and EMA ($\alpha = 0.20$).
  - `YouTubeSyncPlayer.kt`: Android WebView with custom HTML5 container, JavaScript IFrame API bridge, and 250 ms PLL drift control loop.
  - Periodic `SYNC_STATE` broadcast from Host every 3 seconds.
- **Verification Criteria:**
  - Projected clock offset $\theta$ stabilizes within 4 seconds of connection.
  - Two devices playing the same YouTube video synchronize within $\le 10\text{ ms}$ drift without audible clicks or pitch distortion.

### Milestone 4: Mode B Native C++ Audio Pipeline (MediaProjection + Opus + Oboe)
- **Objective:** Capture live system audio on Host, compress with Opus, transmit over UDP, and render on client devices with Oboe low-latency audio.
- **Deliverables:**
  - `CMakeLists.txt` fetching `google/oboe` and `xiph/opus`.
  - `OpusCodec.cpp` / `.h`: 48 kHz stereo 20 ms frame encoder and decoder.
  - `AudioEngine.cpp` / `.h`: Oboe AAudio output stream with presentation timestamp scheduling.
  - `JitterBuffer.cpp` / `.h`: Lock-free priority queue for packet ordering and loss concealment.
  - `MediaCaptureService.kt` & `SystemAudioCapturer.kt`: Foreground service capturing audio from external media apps.
  - `UdpAudioTransport.kt`: Multi-unicast UDP broadcaster on port 5004.
- **Verification Criteria:**
  - Spotify / Apple Music audio captured on Host is received, decoded, and rendered on client speakers via Oboe with 120 ms target buffer.

### Milestone 5: Hardware DAC Latency Compensation & Acoustic Calibration
- **Objective:** Eliminate physical OEM DAC delays and acoustic propagation phase errors to achieve $\le 1.5\text{ ms}$ acoustic phase lock.
- **Deliverables:**
  - `DeviceCalibrationStore.kt`: Jetpack DataStore storing hardware offsets per OEM model.
  - `AcousticCalibrator.cpp` / `.h`: 15 ms linear FM sweep chirp (2 kHz → 6 kHz) generator and cross-correlation detector.
  - `SyncModal.kt`: UI dialog with persistent $\pm 150\text{ ms}$ nudge slider and "One-Tap Acoustic Calibration" button.
  - Live Diagnostic Telemetry: Real-time RTT, clock offset, jitter buffer depth, and drift monitoring.
- **Verification Criteria:**
  - Nudge slider smoothly shifts audio phase in real-time.
  - Acoustic chirp test calculates physical speaker delay within $\pm 2\text{ ms}$ accuracy.

---

## 8. Finalized Architectural Decisions (Approved)

Based on engineering review and user confirmation, the following design decisions are finalized:

1. **Project Name & Package Namespace:**
   - **Decision:** **EchoSphere** with package name `com.echosphere.app` and mDNS service type `_echosphere._tcp.` (aligned with the repository directory `EchoSphere`).
2. **YouTube Track Search in Mode A:**
   - **Decision:** **Direct YouTube URL / Video ID input + Curated popular demo tracks** for immediate zero-friction testing without requiring an external Google Cloud API key, with an extensible query interface for future API key integration.
3. **Mode B Host Local Playback Audio Routing:**
   - **Decision:** **Loop Host audio through local Oboe DAC with 120 ms buffer.** When broadcasting system audio, the Host routes its captured PCM audio through its own local Oboe playback pipeline with the identical $T_{\text{capture}} + 120,000\ \mu s$ presentation schedule, ensuring that the Host's speaker and all Client phone speakers play in exact acoustic phase lock.
4. **Build & Development Tooling:**
   - **Decision:** **Android Studio Ready.** Structure the project with modern Android Gradle Plugin 8.5+, Gradle 8.7 wrapper, Kotlin 2.0, Jetpack Compose BOM, and CMake with FetchContent for Google Oboe and libopus so it builds seamlessly in Android Studio.
