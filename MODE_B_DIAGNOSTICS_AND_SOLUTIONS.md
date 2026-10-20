# Mode B (System Audio Broadcast) — Root Cause Diagnostics & Solution Blueprint

---

## 🔍 Executive Summary: Why It Only Played on the Host

When you stream from YouTube Music or Spotify in **Mode B (System Broadcast)**:
1. **The Host Phone** played music through its internal speaker because YouTube Music outputs sound locally to Android's audio framework, and Android's `MediaProjection` captures that PCM stream.
2. **The Client Phone remained completely silent** because:
   - **The Host was broadcasting to 0 IP addresses:** The Host's `UdpAudioTransport.targetPeerIps` list was completely empty. It did not know the client's IP, so no UDP audio packets were ever sent over Wi-Fi/Hotspot.
   - **The Client was not listening:** The Client phone never initialized `UdpAudioTransport`, never opened UDP socket port `5004`, and never started its C++ Google Oboe audio output stream.
   - **The Client never reacted to the Mode B switch:** The Client did not have an observer for `roomState.activeMode == MODE_B_SYSTEM_AUDIO`.

---

## 🚨 Detailed Problem Analysis

### Problem 1: Host `UdpAudioTransport` Had 0 Target Peers (Audio Sent Nowhere)
- **Why it occurs:** In [`MediaCaptureService.kt`](file:///d:/Projects/EchoSphere/app/src/main/java/com/echosphere/app/audio/service/MediaCaptureService.kt), an instance of `UdpAudioTransport` was created in isolation. In [`UdpAudioTransport.kt`](file:///d:/Projects/EchoSphere/app/src/main/java/com/echosphere/app/network/udp/UdpAudioTransport.kt), packets are only broadcasted if `targetPeerIps.isNotEmpty()`:
  ```kotlin
  if (targetPeerIps.isNotEmpty()) {
      // Send UDP packets
  }
  ```
  No code in the entire app ever called `addPeerTarget(...)`. Consequently, captured Opus frames were looped back locally on the Host, but **zero packets were transmitted over the network**.

---

### Problem 2: Client Phone Never Started the UDP Receiver on Port 5004
- **Why it occurs:** In [`MainActivity.kt`](file:///d:/Projects/EchoSphere/app/src/main/java/com/echosphere/app/MainActivity.kt#L282-L315) under `startClientSubsystems(...)`, the client initialized `ClientWebSocketManager`, `ClockSyncEngine`, and `YouTubeSyncPlayer`, but:
  - It **never created an instance of `UdpAudioTransport`**.
  - It **never called `UdpAudioTransport.startReceiver()`**.
  - Port `5004` on the client was completely closed and never receiving UDP packets.

---

### Problem 3: Client Phone Never Started the Native C++ Oboe DAC Stream
- **Why it occurs:** Google Oboe (`AudioEngine.cpp`) requires `NativeAudioBridge.nativeStartAudio()` to open the low-latency hardware output stream and register the `onAudioReady` callback.
  - In `MainActivity.kt`, `nativeStartAudio()` was only called on the Host inside `mediaProjectionLauncher`.
  - It was **never called on the Client phone**. Even if packets reached the client, the client speaker hardware was never activated.

---

### Problem 4: Client Did Not Observe the `activeMode` State Change
- **Why it occurs:** When the Host taps the "Mode B: System Broadcast" pill, the Host broadcasts `ROOM_STATE_UPDATE` with `activeMode = PlaybackMode.MODE_B_SYSTEM_AUDIO`.
  - The Client's `MainActivity.kt` only listened to `syncStateFlow` (for Mode A YouTube progress) and `chirpScheduleFlow` (for calibration).
  - The Client had **no collector observing `clientManager.roomState`**. The Client didn't know the room had switched to Mode B, so it did not pause YouTube or engage Mode B native audio.

---

### Problem 5: Client IP Was Never Sent During Handshake (Hardcoded `"Client"`)
- **Why it occurs:** In [`WsMessage.kt`](file:///d:/Projects/EchoSphere/app/src/main/java/com/echosphere/app/network/ws/WsMessage.kt), `JoinPayload` only had `displayName` and `deviceModel`.
  - In [`HostWebSocketServer.kt`](file:///d:/Projects/EchoSphere/app/src/main/java/com/echosphere/app/network/ws/HostWebSocketServer.kt#L130-L136), `PeerInfo.ipAddress` was hardcoded to the string `"Client"`.
  - The Host had no way of knowing the actual LAN/Hotspot IP address of the connected peer to send unicast UDP packets to.

---

### Problem 6: Clock Domain Mismatch in C++ Audio Engine (`CLOCK_MONOTONIC` vs `CLOCK_BOOTTIME`)
- **Why it occurs:** 
  - On Android Kotlin side ([`SystemAudioCapturer.kt`](file:///d:/Projects/EchoSphere/app/src/main/java/com/echosphere/app/audio/capture/SystemAudioCapturer.kt)), timestamps are generated using:
    `SystemClock.elapsedRealtimeNanos() / 1000L` (which corresponds to Linux kernel `CLOCK_BOOTTIME`).
  - In [`AudioEngine.cpp`](file:///d:/Projects/EchoSphere/app/src/main/cpp/AudioEngine.cpp#L70), the timestamp was calculated using:
    `std::chrono::steady_clock::now()` (which corresponds to `CLOCK_MONOTONIC`).
  - If a device has been asleep or has deep-sleep intervals, `CLOCK_BOOTTIME` and `CLOCK_MONOTONIC` differ by large amounts.
  - As a result, [`JitterBuffer.cpp`](file:///d:/Projects/EchoSphere/app/src/main/cpp/JitterBuffer.cpp#L40) would evaluate every frame as having arrived either millions of microseconds too late or in the distant future, immediately discarding or stalling playback.
  - In C++, it must query `clock_gettime(CLOCK_BOOTTIME, &ts)` to match Android's `SystemClock.elapsedRealtimeNanos()`.

---

### Problem 7: Clock Offset $\theta$ Was Never Forwarded to C++ Engine
- **Why it occurs:** In [`ClockSyncEngine.kt`](file:///d:/Projects/EchoSphere/app/src/main/java/com/echosphere/app/network/udp/ClockSyncEngine.kt), the client computes the filtered clock offset `_clockOffsetMicros`. However, it was never forwarded to `NativeAudioBridge.nativeSetClockOffsetUs(...)`.
  - In `AudioEngine.cpp`, `mClockOffsetUs` remained `0`, preventing accurate presentation timing on clients.

---

## 🛠 Proposed Solutions Matrix

| Problem | Root Cause | Proposed Solution | Target Files |
| :--- | :--- | :--- | :--- |
| **1. Host Target IP List Empty** | `targetPeerIps` never populated | Provide Host `UdpAudioTransport` with live peer IP list from `HostWebSocketServer`. Also support Hotspot subnet broadcast (`192.168.43.255`) as fallback. | `MediaCaptureService.kt`, `HostWebSocketServer.kt`, `UdpAudioTransport.kt` |
| **2. Client UDP Receiver Inactive** | No `UdpAudioTransport` instance on client | Instantiate `UdpAudioTransport` in client subsystems; start listening on UDP `5004` when Mode B is active. | `MainActivity.kt`, `UdpAudioTransport.kt` |
| **3. Client Oboe DAC Inactive** | `nativeStartAudio()` never called on client | Call `NativeAudioBridge.nativeStartAudio()` when entering Mode B on the client. | `MainActivity.kt` |
| **4. Client Unaware of Mode Switch** | No collector for `roomState.activeMode` | Collect `roomState` flow in `MainActivity.kt`. When `activeMode == MODE_B_SYSTEM_AUDIO`, launch client UDP receiver + Oboe DAC and pause Mode A player. When `MODE_A_YOUTUBE`, stop Mode B receiver and stop Oboe DAC. | `MainActivity.kt` |
| **5. Missing Client IP in Handshake** | `JoinPayload` lacked IP address | Include `clientIp` in `JoinPayload`. Extract client IP in `HostWebSocketServer` and register it into `UdpAudioTransport`. | `WsMessage.kt`, `ClientWebSocketManager.kt`, `HostWebSocketServer.kt` |
| **6. Clock Domain Mismatch** | `std::chrono::steady_clock` != `SystemClock.elapsedRealtimeNanos()` | Replace `std::chrono::steady_clock` in `AudioEngine.cpp` with `clock_gettime(CLOCK_BOOTTIME, ...)` so both Kotlin and C++ use identical microsecond timelines. | `AudioEngine.cpp` |
| **7. Clock Offset Not Forwarded** | `nativeSetClockOffsetUs` never called | Launch coroutine in `MainActivity.kt` observing `clockSyncEngine.clockOffsetMicros` and updating `NativeAudioBridge.nativeSetClockOffsetUs(offset)`. | `MainActivity.kt` |

---

## 📋 Step-by-Step Implementation Strategy

1. **Update `WsMessage.kt`**: Add `val clientIp: String = ""` to `JoinPayload`.
2. **Update `ClientWebSocketManager.kt`**: Automatically include `NetworkUtils.getLocalIpAddress()` in the join payload when connecting to the host.
3. **Update `HostWebSocketServer.kt`**: Store real client IP in `PeerInfo` and expose a reactive flow or callback for active peer IPs.
4. **Update `UdpAudioTransport.kt`**:
   - Add subnet broadcast fallback (`broadcastOpusFrame` sends to both registered peer IPs AND local subnet broadcast `192.168.43.255` / `255.255.255.255`).
   - Allow dynamic peer registration.
5. **Update `MediaCaptureService.kt`**: Share/bind the active `UdpAudioTransport` with `MainActivity` and `HostWebSocketServer` so all client IPs are registered.
6. **Update `AudioEngine.cpp`**: Use `clock_gettime(CLOCK_BOOTTIME, &ts)` to achieve true microsecond parity with `SystemClock.elapsedRealtimeNanos()`.
7. **Update `MainActivity.kt`**:
   - Manage `clientAudioTransport` on Client phone.
   - Observe `roomState.activeMode`: automatically start/stop Oboe DAC and UDP receiver.
   - Forward `clockOffsetMicros` to `NativeAudioBridge.nativeSetClockOffsetUs`.
8. **Rebuild & Package APK**: Commit, push to GitHub Actions, compile, and download the new `app-debug.apk`.
