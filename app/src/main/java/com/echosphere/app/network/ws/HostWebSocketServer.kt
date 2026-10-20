package com.echosphere.app.network.ws

import android.os.SystemClock
import android.util.Log
import com.echosphere.app.data.model.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HostWebSocketServer(
    private val hostRoomInfo: RoomInfo,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "HostWebSocketServer"
    }

    private var serverEngine: ApplicationEngine? = null
    private val connectedSessions = ConcurrentHashMap<String, WebSocketSession>()

    private val _roomState = MutableStateFlow(
        RoomState(
            roomId = hostRoomInfo.roomId,
            roomName = hostRoomInfo.roomName,
            hostPeerId = "host-${hostRoomInfo.roomId.take(6)}",
            connectedPeers = listOf(
                PeerInfo(
                    peerId = "host-${hostRoomInfo.roomId.take(6)}",
                    displayName = "Host (${hostRoomInfo.hostDeviceModel})",
                    deviceModel = hostRoomInfo.hostDeviceModel,
                    ipAddress = hostRoomInfo.hostIp,
                    isHost = true
                )
            )
        )
    )
    val roomState: StateFlow<RoomState> = _roomState.asStateFlow()

    private var syncBroadcastJob: Job? = null

    /**
     * Start the Ktor CIO WebSocket server on port 8080
     */
    fun start() {
        if (serverEngine != null) return

        serverEngine = embeddedServer(CIO, port = hostRoomInfo.wsPort) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(10)
                timeout = Duration.ofSeconds(30)
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }

            routing {
                webSocket("/ws") {
                    var clientPeerId: String? = null
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                val message = WsMessage.fromJson(text) ?: continue
                                clientPeerId = message.senderPeerId

                                handleIncomingMessage(this, message)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket session error for peer $clientPeerId", e)
                    } finally {
                        clientPeerId?.let { peerId ->
                            connectedSessions.remove(peerId)
                            _roomState.update { state ->
                                state.copy(
                                    connectedPeers = state.connectedPeers.filterNot { it.peerId == peerId }
                                )
                            }
                            broadcastRoomState()
                            Log.i(TAG, "Peer disconnected: $peerId. Total peers: ${connectedSessions.size}")
                        }
                    }
                }
            }
        }.start(wait = false)

        startSyncStateBroadcaster()
        Log.i(TAG, "EchoSphere Host WebSocket server started on port ${hostRoomInfo.wsPort}")
    }

    fun stop() {
        syncBroadcastJob?.cancel()
        syncBroadcastJob = null

        coroutineScope.launch {
            connectedSessions.values.forEach { session ->
                try {
                    session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Host stopped"))
                } catch (ignored: Exception) {}
            }
            connectedSessions.clear()
            serverEngine?.stop(1000, 2000)
            serverEngine = null
            Log.i(TAG, "EchoSphere Host WebSocket server stopped")
        }
    }

    private suspend fun handleIncomingMessage(session: WebSocketSession, message: WsMessage) {
        val peerId = message.senderPeerId

        when (message.action) {
            WsAction.JOIN_REQUEST -> {
                connectedSessions[peerId] = session
                val joinPayload = try {
                    WsMessage.jsonInstance.decodeFromString<JoinPayload>(message.payloadJson)
                } catch (e: Exception) {
                    JoinPayload("Guest Phone", "Android")
                }

                val newPeer = PeerInfo(
                    peerId = peerId,
                    displayName = joinPayload.displayName,
                    deviceModel = joinPayload.deviceModel,
                    ipAddress = "Client",
                    isHost = false
                )

                _roomState.update { state ->
                    val updatedPeers = state.connectedPeers.filterNot { it.peerId == peerId } + newPeer
                    state.copy(connectedPeers = updatedPeers)
                }

                // Send ACK and complete RoomState
                val ackMsg = WsMessage(
                    action = WsAction.JOIN_ACK,
                    senderPeerId = _roomState.value.hostPeerId,
                    payloadJson = WsMessage.jsonInstance.encodeToString(RoomState.serializer(), _roomState.value)
                )
                session.send(Frame.Text(ackMsg.toJson()))
                broadcastRoomState()
            }

            WsAction.QUEUE_ADD -> {
                val addPayload = WsMessage.jsonInstance.decodeFromString<QueueAddPayload>(message.payloadJson)
                val newItem = QueueItem(
                    id = UUID.randomUUID().toString().take(8),
                    videoId = addPayload.videoId,
                    title = addPayload.title,
                    artist = addPayload.artist,
                    durationSeconds = addPayload.durationSeconds,
                    addedByPeerId = peerId,
                    addedByName = _roomState.value.connectedPeers.find { it.peerId == peerId }?.displayName ?: "Guest",
                    upvotes = 1,
                    upvotedPeerIds = listOf(peerId)
                )

                _roomState.update { state ->
                    val updatedQueue = state.queue + newItem
                    // If nothing is playing, automatically start playing the added track
                    if (state.currentTrack == null) {
                        state.copy(
                            currentTrack = newItem,
                            queue = updatedQueue.filterNot { it.id == newItem.id },
                            playbackState = TrackPlaybackState(
                                isPlaying = true,
                                trackPositionMs = 0,
                                atMasterClockMs = SystemClock.elapsedRealtime()
                            )
                        )
                    } else {
                        state.copy(queue = updatedQueue)
                    }
                }
                broadcastRoomState()
            }

            WsAction.QUEUE_VOTE -> {
                val votePayload = WsMessage.jsonInstance.decodeFromString<QueueVotePayload>(message.payloadJson)
                _roomState.update { state ->
                    val updatedQueue = state.queue.map { item ->
                        if (item.id == votePayload.itemId) {
                            val alreadyVoted = item.upvotedPeerIds.contains(peerId)
                            val newVoters = if (alreadyVoted) {
                                item.upvotedPeerIds - peerId
                            } else {
                                item.upvotedPeerIds + peerId
                            }
                            item.copy(
                                upvotes = newVoters.size.coerceAtLeast(1),
                                upvotedPeerIds = newVoters
                            )
                        } else item
                    }
                    state.copy(queue = updatedQueue)
                }
                broadcastRoomState()
            }

            WsAction.QUEUE_REMOVE -> {
                val removePayload = WsMessage.jsonInstance.decodeFromString<QueueVotePayload>(message.payloadJson)
                _roomState.update { state ->
                    state.copy(queue = state.queue.filterNot { it.id == removePayload.itemId })
                }
                broadcastRoomState()
            }

            WsAction.PLAY_TRACK -> {
                val playPayload = WsMessage.jsonInstance.decodeFromString<PlayTrackPayload>(message.payloadJson)
                _roomState.update { state ->
                    val selected = state.queue.find { it.id == playPayload.trackId }
                    if (selected != null) {
                        state.copy(
                            currentTrack = selected,
                            queue = state.queue.filterNot { it.id == selected.id },
                            playbackState = TrackPlaybackState(
                                isPlaying = true,
                                trackPositionMs = 0,
                                atMasterClockMs = SystemClock.elapsedRealtime()
                            )
                        )
                    } else state
                }
                broadcastRoomState()
                broadcastSyncState()
            }

            WsAction.PAUSE_TRACK -> {
                _roomState.update { state ->
                    val currentPos = state.playbackState.trackPositionMs
                    state.copy(
                        playbackState = state.playbackState.copy(
                            isPlaying = false,
                            trackPositionMs = currentPos,
                            atMasterClockMs = SystemClock.elapsedRealtime()
                        )
                    )
                }
                broadcastRoomState()
                broadcastSyncState()
            }

            WsAction.SEEK_TRACK -> {
                val seekPayload = WsMessage.jsonInstance.decodeFromString<SeekTrackPayload>(message.payloadJson)
                _roomState.update { state ->
                    state.copy(
                        playbackState = state.playbackState.copy(
                            trackPositionMs = seekPayload.positionMs,
                            atMasterClockMs = SystemClock.elapsedRealtime()
                        )
                    )
                }
                broadcastSyncState()
            }

            WsAction.SWITCH_MODE -> {
                val modePayload = WsMessage.jsonInstance.decodeFromString<SwitchModePayload>(message.payloadJson)
                _roomState.update { it.copy(activeMode = modePayload.targetMode) }
                broadcastRoomState()
            }

            WsAction.REQ_CHIRP -> {
                // Host schedules chirp at T0 = now + 500ms
                val t0Micros = (SystemClock.elapsedRealtimeNanos() / 1000) + 500_000
                val scheduleMsg = WsMessage(
                    action = WsAction.CHIRP_SCHEDULED,
                    senderPeerId = _roomState.value.hostPeerId,
                    payloadJson = WsMessage.jsonInstance.encodeToString(
                        ChirpSchedulePayload.serializer(),
                        ChirpSchedulePayload(t0Micros)
                    )
                )
                broadcastMessage(scheduleMsg)
            }

            else -> {}
        }
    }

    // Host local controls
    fun togglePlayPause() {
        val currentState = _roomState.value
        val nowPlaying = currentState.playbackState.isPlaying
        val nowMs = SystemClock.elapsedRealtime()
        val currentTrackPos = if (nowPlaying) {
            currentState.playbackState.trackPositionMs + (nowMs - currentState.playbackState.atMasterClockMs)
        } else {
            currentState.playbackState.trackPositionMs
        }

        _roomState.update {
            it.copy(
                playbackState = it.playbackState.copy(
                    isPlaying = !nowPlaying,
                    trackPositionMs = currentTrackPos,
                    atMasterClockMs = nowMs
                )
            )
        }
        broadcastRoomState()
        broadcastSyncState()
    }

    fun playNextTrack() {
        val currentState = _roomState.value
        val sorted = currentState.getSortedQueue()
        if (sorted.isNotEmpty()) {
            val nextTrack = sorted.first()
            _roomState.update {
                it.copy(
                    currentTrack = nextTrack,
                    queue = it.queue.filterNot { item -> item.id == nextTrack.id },
                    playbackState = TrackPlaybackState(
                        isPlaying = true,
                        trackPositionMs = 0,
                        atMasterClockMs = SystemClock.elapsedRealtime()
                    )
                )
            }
            broadcastRoomState()
            broadcastSyncState()
        }
    }

    fun switchPlaybackMode(mode: PlaybackMode) {
        _roomState.update { it.copy(activeMode = mode) }
        broadcastRoomState()
    }

    fun addTrackToQueue(videoId: String, title: String, artist: String) {
        val newItem = QueueItem(
            id = UUID.randomUUID().toString().take(8),
            videoId = videoId,
            title = title,
            artist = artist,
            addedByPeerId = _roomState.value.hostPeerId,
            addedByName = "Host",
            upvotes = 1,
            upvotedPeerIds = listOf(_roomState.value.hostPeerId)
        )

        _roomState.update { state ->
            if (state.currentTrack == null) {
                state.copy(
                    currentTrack = newItem,
                    playbackState = TrackPlaybackState(
                        isPlaying = true,
                        trackPositionMs = 0,
                        atMasterClockMs = SystemClock.elapsedRealtime()
                    )
                )
            } else {
                state.copy(queue = state.queue + newItem)
            }
        }
        broadcastRoomState()
        broadcastSyncState()
    }

    private fun startSyncStateBroadcaster() {
        syncBroadcastJob = coroutineScope.launch {
            while (isActive) {
                delay(3000) // Broadcast SYNC_STATE every 3.0 seconds as specified
                broadcastSyncState()
            }
        }
    }

    private fun broadcastSyncState() {
        val state = _roomState.value
        val track = state.currentTrack ?: return
        val currentMasterMs = SystemClock.elapsedRealtime()

        val syncPayload = SyncStatePayload(
            videoId = track.videoId,
            isPlaying = state.playbackState.isPlaying,
            trackPositionMs = state.playbackState.trackPositionMs,
            atMasterClockMs = state.playbackState.atMasterClockMs
        )

        val msg = WsMessage(
            action = WsAction.SYNC_STATE,
            senderPeerId = state.hostPeerId,
            payloadJson = WsMessage.jsonInstance.encodeToString(SyncStatePayload.serializer(), syncPayload)
        )
        broadcastMessage(msg)
    }

    private fun broadcastRoomState() {
        val msg = WsMessage(
            action = WsAction.ROOM_STATE_UPDATE,
            senderPeerId = _roomState.value.hostPeerId,
            payloadJson = WsMessage.jsonInstance.encodeToString(RoomState.serializer(), _roomState.value)
        )
        broadcastMessage(msg)
    }

    private fun broadcastMessage(msg: WsMessage) {
        val rawJson = msg.toJson()
        val frame = Frame.Text(rawJson)
        coroutineScope.launch {
            connectedSessions.values.forEach { session ->
                try {
                    session.send(frame)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending frame to session", e)
                }
            }
        }
    }
}
