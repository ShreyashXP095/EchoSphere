package com.echosphere.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.echosphere.app.audio.calibration.AcousticChirpController
import com.echosphere.app.audio.nativebridge.NativeAudioBridge
import com.echosphere.app.audio.service.MediaCaptureService
import com.echosphere.app.audio.youtube.YouTubeSyncPlayer
import com.echosphere.app.data.model.*
import com.echosphere.app.data.preferences.DeviceCalibrationStore
import com.echosphere.app.network.NetworkUtils
import com.echosphere.app.network.nsd.NsdDiscoveryManager
import com.echosphere.app.network.udp.ClockSyncEngine
import com.echosphere.app.network.udp.UdpAudioTransport
import com.echosphere.app.network.ws.ClientWebSocketManager
import com.echosphere.app.network.ws.HostWebSocketServer
import com.echosphere.app.ui.*
import com.echosphere.app.ui.theme.EchoSphereTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppNavState {
    HOME,
    ROOM
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "EchoSphere-MainActivity"
    }

    private lateinit var nsdManager: NsdDiscoveryManager
    private lateinit var calibrationStore: DeviceCalibrationStore
    private var chirpController: AcousticChirpController? = null

    // Subsystems
    private var hostServer: HostWebSocketServer? = null
    private var clientManager: ClientWebSocketManager? = null
    private var clockSyncEngine: ClockSyncEngine? = null
    private var youTubeSyncPlayer: YouTubeSyncPlayer? = null

    // Client Mode B subsystems
    private var clientAudioTransport: UdpAudioTransport? = null
    private var clientModeBObserverJob: Job? = null
    private var clientClockOffsetForwarderJob: Job? = null

    // Host → MediaCaptureService binding for peer IP propagation
    private var mediaCaptureService: MediaCaptureService? = null
    private var isServiceBound = false
    private var hostPeerIpSyncJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MediaCaptureService.LocalBinder ?: return
            mediaCaptureService = binder.getService()
            isServiceBound = true
            Log.i(TAG, "Bound to MediaCaptureService — starting peer IP sync")
            startHostPeerIpSync()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaCaptureService = null
            isServiceBound = false
            hostPeerIpSyncJob?.cancel()
            hostPeerIpSyncJob = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nsdManager = NsdDiscoveryManager(this)
        calibrationStore = DeviceCalibrationStore(this)
        chirpController = AcousticChirpController(lifecycleScope)

        // Native audio initialize
        NativeAudioBridge.nativeInit()

        setContent {
            EchoSphereTheme {
                MainAppContent()
            }
        }
    }

    @Composable
    private fun MainAppContent() {
        var navState by remember { mutableStateOf(AppNavState.HOME) }
        var isHostRole by remember { mutableStateOf(false) }
        var currentRoomInfo by remember { mutableStateOf<RoomInfo?>(null) }

        // Discovery state
        val discoveredRooms by nsdManager.discoveredRooms.collectAsState()
        val isDiscovering by nsdManager.isDiscovering.collectAsState()

        // Room State
        val hostRoomState = hostServer?.roomState?.collectAsState()?.value
        val clientRoomState = clientManager?.roomState?.collectAsState()?.value
        val activeRoomState = if (isHostRole) hostRoomState else clientRoomState

        // Calibration Offset State
        val storedCalibrationOffset by calibrationStore.calibrationOffsetMsFlow.collectAsState(initial = 0L)
        var liveNudgeMs by remember { mutableStateOf(0L) }

        LaunchedEffect(storedCalibrationOffset) {
            liveNudgeMs = storedCalibrationOffset
        }

        // Telemetry States
        val pllTelemetry = youTubeSyncPlayer?.telemetry?.collectAsState()?.value
            ?: com.echosphere.app.audio.youtube.PllTelemetry()
        val clockOffset = clockSyncEngine?.clockOffsetMicros?.collectAsState()?.value ?: 0L
        val lastRtt = clockSyncEngine?.lastRttMicros?.collectAsState()?.value ?: 0L

        // Dialogs
        var showHostQrModal by remember { mutableStateOf(false) }
        var showCameraScannerModal by remember { mutableStateOf(false) }
        var showSyncModal by remember { mutableStateOf(false) }

        // MediaProjection Launcher for Mode B
        val mediaProjectionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, MediaCaptureService::class.java).apply {
                    action = MediaCaptureService.ACTION_START
                    putExtra(MediaCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(MediaCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                NativeAudioBridge.nativeStartAudio()

                // Bind to the service so we can push peer IPs into its UdpAudioTransport
                bindService(
                    Intent(this, MediaCaptureService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
            }
        }

        // Invisible WebView container for Mode A YouTube Audio Player
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).also { wv ->
                        youTubeSyncPlayer?.attachWebView(wv)
                    }
                },
                modifier = Modifier.size(1.dp) // Hidden offscreen
            )

            when (navState) {
                AppNavState.HOME -> {
                    HomeScreen(
                        discoveredRooms = discoveredRooms,
                        isDiscovering = isDiscovering,
                        onStartHost = { roomName ->
                            val localIp = NetworkUtils.getLocalIpAddress() ?: "192.168.43.1"
                            val newRoom = RoomInfo(
                                roomId = UUID.randomUUID().toString().take(8),
                                roomName = roomName,
                                hostIp = localIp
                            )
                            currentRoomInfo = newRoom
                            isHostRole = true

                            startHostSubsystems(newRoom)
                            navState = AppNavState.ROOM
                        },
                        onJoinRoom = { selectedRoom ->
                            currentRoomInfo = selectedRoom
                            isHostRole = false

                            startClientSubsystems(selectedRoom)
                            navState = AppNavState.ROOM
                        },
                        onRefreshDiscovery = {
                            nsdManager.stopDiscovery()
                            nsdManager.startDiscovery()
                        }
                    )
                }

                AppNavState.ROOM -> {
                    activeRoomState?.let { roomState ->
                        RoomScreen(
                            roomState = roomState,
                            isHost = isHostRole,
                            onTogglePlayPause = {
                                hostServer?.togglePlayPause()
                            },
                            onPlayNext = {
                                hostServer?.playNextTrack()
                            },
                            onAddTrack = { videoId, title, artist ->
                                if (isHostRole) {
                                    hostServer?.addTrackToQueue(videoId, title, artist)
                                } else {
                                    clientManager?.addTrack(videoId, title, artist)
                                }
                            },
                            onVoteTrack = { itemId ->
                                if (isHostRole) {
                                    // Host vote
                                } else {
                                    clientManager?.voteTrack(itemId)
                                }
                            },
                            onSwitchMode = { targetMode ->
                                if (isHostRole) {
                                    hostServer?.switchPlaybackMode(targetMode)
                                    if (targetMode == PlaybackMode.MODE_B_SYSTEM_AUDIO) {
                                        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                    } else {
                                        unbindMediaCaptureService()
                                        stopService(Intent(this@MainActivity, MediaCaptureService::class.java))
                                    }
                                }
                            },
                            onShowQrModal = { showHostQrModal = true },
                            onOpenSyncModal = { showSyncModal = true },
                            onLeaveRoom = {
                                stopAllSubsystems()
                                navState = AppNavState.HOME
                            }
                        )
                    }
                }
            }
        }

        // Modals
        if (showHostQrModal && currentRoomInfo != null) {
            HostQrModal(
                roomInfo = currentRoomInfo!!,
                onDismiss = { showHostQrModal = false }
            )
        }

        if (showCameraScannerModal) {
            CameraQrScannerModal(
                onDismiss = { showCameraScannerModal = false },
                onQrScanned = { discovered ->
                    showCameraScannerModal = false
                    currentRoomInfo = discovered.roomInfo
                    isHostRole = false
                    startClientSubsystems(discovered.roomInfo)
                    navState = AppNavState.ROOM
                }
            )
        }

        if (showSyncModal) {
            SyncModal(
                currentNudgeMs = liveNudgeMs,
                onNudgeChanged = { newOffset ->
                    liveNudgeMs = newOffset
                    clockSyncEngine?.setManualCalibrationOffset(newOffset)
                    NativeAudioBridge.nativeSetManualCalibrationUs(newOffset * 1000L)
                    lifecycleScope.launch {
                        calibrationStore.saveCalibrationOffsetMs(newOffset)
                    }
                },
                pllTelemetry = pllTelemetry,
                clockOffsetMicros = clockOffset,
                lastRttMicros = lastRtt,
                onTriggerChirpCalibration = {
                    if (isHostRole) {
                        chirpController?.playChirpNow()
                    } else {
                        clientManager?.requestChirpCalibration()
                    }
                },
                onDismiss = { showSyncModal = false }
            )
        }
    }

    private fun startHostSubsystems(roomInfo: RoomInfo) {
        nsdManager.startAdvertising(roomInfo)

        hostServer = HostWebSocketServer(roomInfo, lifecycleScope).apply {
            start()
        }

        clockSyncEngine = ClockSyncEngine(isHost = true, coroutineScope = lifecycleScope).apply {
            start()
        }

        youTubeSyncPlayer = YouTubeSyncPlayer(this, clockSyncEngine!!, isHost = true, lifecycleScope)
    }

    /**
     * Starts observing HostWebSocketServer.activePeerIps and syncing them
     * into the MediaCaptureService's UdpAudioTransport, so the host knows
     * where to send captured Opus frames over UDP.
     */
    private fun startHostPeerIpSync() {
        hostPeerIpSyncJob?.cancel()
        hostPeerIpSyncJob = lifecycleScope.launch {
            hostServer?.activePeerIps?.collectLatest { peerIps ->
                val transport = mediaCaptureService?.getUdpTransport()
                if (transport != null) {
                    transport.syncPeerTargets(peerIps)
                    Log.i(TAG, "Host peer IPs synced to UdpAudioTransport: $peerIps")
                } else {
                    Log.w(TAG, "UdpAudioTransport not yet available in MediaCaptureService")
                }
            }
        }
    }

    private fun startClientSubsystems(roomInfo: RoomInfo) {
        clientManager = ClientWebSocketManager(displayName = Build.MODEL, coroutineScope = lifecycleScope).apply {
            connectToHost(roomInfo)
        }

        clockSyncEngine = ClockSyncEngine(
            isHost = false,
            hostAddress = roomInfo.hostIp,
            udpPort = roomInfo.udpClockPort,
            coroutineScope = lifecycleScope
        ).apply {
            start()
        }

        youTubeSyncPlayer = YouTubeSyncPlayer(this, clockSyncEngine!!, isHost = false, lifecycleScope)

        // Observe SYNC_STATE broadcast
        lifecycleScope.launch {
            clientManager?.syncStateFlow?.collect { syncPayload ->
                youTubeSyncPlayer?.onSyncStateReceived(syncPayload)
            }
        }

        // Observe Chirp Calibration trigger
        lifecycleScope.launch {
            clientManager?.chirpScheduleFlow?.collect { t0Micros ->
                val calibratedOffsetMs = chirpController?.runCalibrationRoutine(t0Micros + 250_000) ?: 0.0f
                val roundedOffset = calibratedOffsetMs.toLong()
                calibrationStore.saveCalibrationOffsetMs(roundedOffset)
                clockSyncEngine?.setManualCalibrationOffset(roundedOffset)
                NativeAudioBridge.nativeSetManualCalibrationUs(roundedOffset * 1000L)
            }
        }

        // ===== FIX #4: Observe activeMode changes for Mode B =====
        startClientModeBObserver()

        // ===== FIX #7: Forward clock offset to native C++ engine =====
        startClientClockOffsetForwarder()
    }

    /**
     * FIX #4: Client observes roomState.activeMode.
     * When Mode B is activated:
     *   - Start client UdpAudioTransport receiver on port 5004
     *   - Start native Oboe DAC output stream
     * When Mode A is restored:
     *   - Stop the UDP receiver and Oboe DAC
     */
    private fun startClientModeBObserver() {
        clientModeBObserverJob?.cancel()
        clientModeBObserverJob = lifecycleScope.launch {
            clientManager?.roomState
                ?.filterNotNull()
                ?.map { it.activeMode }
                ?.distinctUntilChanged()
                ?.collect { mode ->
                    when (mode) {
                        PlaybackMode.MODE_B_SYSTEM_AUDIO -> {
                            Log.i(TAG, "Client: Mode B detected — starting UDP receiver + Oboe DAC")

                            // Start client UDP receiver to listen for incoming Opus packets
                            if (clientAudioTransport == null) {
                                clientAudioTransport = UdpAudioTransport(coroutineScope = lifecycleScope)
                            }
                            clientAudioTransport?.startReceiver()

                            // Start native Oboe low-latency output stream
                            NativeAudioBridge.nativeStartAudio()

                            // Pause Mode A YouTube player
                            youTubeSyncPlayer?.pauseVideo()
                        }
                        PlaybackMode.MODE_A_YOUTUBE -> {
                            Log.i(TAG, "Client: Mode A detected — stopping UDP receiver + Oboe DAC")

                            // Stop client UDP receiver
                            clientAudioTransport?.stop()
                            clientAudioTransport = null

                            // Stop native Oboe output stream
                            NativeAudioBridge.nativeStopAudio()
                        }
                    }
                }
        }
    }

    /**
     * FIX #7: Forward the ClockSyncEngine's computed clock offset
     * to the native C++ AudioEngine so that the jitter buffer uses
     * the correct master time for frame scheduling.
     */
    private fun startClientClockOffsetForwarder() {
        clientClockOffsetForwarderJob?.cancel()
        clientClockOffsetForwarderJob = lifecycleScope.launch {
            clockSyncEngine?.clockOffsetMicros?.collect { offsetUs ->
                NativeAudioBridge.nativeSetClockOffsetUs(offsetUs)
            }
        }
    }

    private fun unbindMediaCaptureService() {
        hostPeerIpSyncJob?.cancel()
        hostPeerIpSyncJob = null
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding MediaCaptureService", e)
            }
            isServiceBound = false
            mediaCaptureService = null
        }
    }

    private fun stopAllSubsystems() {
        nsdManager.stopAdvertising()
        hostServer?.stop()
        hostServer = null

        clientManager?.disconnect()
        clientManager = null

        clockSyncEngine?.stop()
        clockSyncEngine = null

        youTubeSyncPlayer?.destroy()
        youTubeSyncPlayer = null

        // Stop client Mode B subsystems
        clientModeBObserverJob?.cancel()
        clientModeBObserverJob = null
        clientClockOffsetForwarderJob?.cancel()
        clientClockOffsetForwarderJob = null
        clientAudioTransport?.stop()
        clientAudioTransport = null

        // Unbind and stop host service
        unbindMediaCaptureService()
        stopService(Intent(this, MediaCaptureService::class.java))
        NativeAudioBridge.nativeStopAudio()
    }

    override fun onDestroy() {
        stopAllSubsystems()
        chirpController?.release()
        super.onDestroy()
    }
}

