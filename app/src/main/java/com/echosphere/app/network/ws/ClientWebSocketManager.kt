package com.echosphere.app.network.ws

import android.os.Build
import android.util.Log
import com.echosphere.app.data.model.RoomInfo
import com.echosphere.app.data.model.RoomState
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

enum class ClientConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

class ClientWebSocketManager(
    private val clientPeerId: String = "peer-" + UUID.randomUUID().toString().take(6),
    private val displayName: String = "Peer Device",
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ClientWebSocketManager"
    }

    private val httpClient = HttpClient(CIO) {
        install(io.ktor.client.plugins.websocket.WebSockets) {
            pingInterval = 10_000
        }
    }

    private var activeSession: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null

    private val _connectionState = MutableStateFlow(ClientConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ClientConnectionState> = _connectionState.asStateFlow()

    private val _roomState = MutableStateFlow<RoomState?>(null)
    val roomState: StateFlow<RoomState?> = _roomState.asStateFlow()

    private val _syncStateFlow = MutableSharedFlow<SyncStatePayload>(extraBufferCapacity = 8)
    val syncStateFlow: SharedFlow<SyncStatePayload> = _syncStateFlow.asSharedFlow()

    private val _chirpScheduleFlow = MutableSharedFlow<Long>(extraBufferCapacity = 2)
    val chirpScheduleFlow: SharedFlow<Long> = _chirpScheduleFlow.asSharedFlow()

    fun connectToHost(roomInfo: RoomInfo) {
        disconnect()

        connectionJob = coroutineScope.launch {
            _connectionState.value = ClientConnectionState.CONNECTING
            val hostUrl = "ws://${roomInfo.hostIp}:${roomInfo.wsPort}/ws"
            Log.i(TAG, "Connecting to EchoSphere Host at $hostUrl")

            var attempt = 0
            while (isActive && _connectionState.value != ClientConnectionState.CONNECTED) {
                attempt++
                try {
                    httpClient.webSocket(
                        method = io.ktor.http.HttpMethod.Get,
                        host = roomInfo.hostIp,
                        port = roomInfo.wsPort,
                        path = "/ws"
                    ) {
                        activeSession = this
                        _connectionState.value = ClientConnectionState.CONNECTED
                        Log.i(TAG, "Connected to Host WebSocket successfully!")

                        // 1. Send JOIN_REQUEST
                        val joinMsg = WsMessage(
                            action = WsAction.JOIN_REQUEST,
                            senderPeerId = clientPeerId,
                            payloadJson = WsMessage.jsonInstance.encodeToString(
                                JoinPayload.serializer(),
                                JoinPayload(displayName, Build.MODEL)
                            )
                        )
                        send(Frame.Text(joinMsg.toJson()))

                        // 2. Listen to incoming events
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                handleServerMessage(text)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Connection attempt $attempt failed: ${e.message}")
                    _connectionState.value = ClientConnectionState.CONNECTING
                    delay((1000L * attempt).coerceAtMost(5000L)) // Exponential backoff
                }
            }
        }
    }

    private fun handleServerMessage(rawJson: String) {
        val msg = WsMessage.fromJson(rawJson) ?: return

        when (msg.action) {
            WsAction.JOIN_ACK, WsAction.ROOM_STATE_UPDATE -> {
                val state = try {
                    WsMessage.jsonInstance.decodeFromString<RoomState>(msg.payloadJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding RoomState", e)
                    null
                }
                state?.let { _roomState.value = it }
            }

            WsAction.SYNC_STATE -> {
                val sync = try {
                    WsMessage.jsonInstance.decodeFromString<SyncStatePayload>(msg.payloadJson)
                } catch (e: Exception) {
                    null
                }
                sync?.let { _syncStateFlow.tryEmit(it) }
            }

            WsAction.CHIRP_SCHEDULED -> {
                val schedule = try {
                    WsMessage.jsonInstance.decodeFromString<ChirpSchedulePayload>(msg.payloadJson)
                } catch (e: Exception) {
                    null
                }
                schedule?.let { _chirpScheduleFlow.tryEmit(it.t0MasterUs) }
            }

            else -> {}
        }
    }

    fun addTrack(videoId: String, title: String, artist: String = "YouTube Artist") {
        coroutineScope.launch {
            val payload = QueueAddPayload(videoId, title, artist)
            val msg = WsMessage(
                action = WsAction.QUEUE_ADD,
                senderPeerId = clientPeerId,
                payloadJson = WsMessage.jsonInstance.encodeToString(QueueAddPayload.serializer(), payload)
            )
            sendWsMessage(msg)
        }
    }

    fun voteTrack(itemId: String) {
        coroutineScope.launch {
            val payload = QueueVotePayload(itemId)
            val msg = WsMessage(
                action = WsAction.QUEUE_VOTE,
                senderPeerId = clientPeerId,
                payloadJson = WsMessage.jsonInstance.encodeToString(QueueVotePayload.serializer(), payload)
            )
            sendWsMessage(msg)
        }
    }

    fun requestChirpCalibration() {
        coroutineScope.launch {
            val msg = WsMessage(
                action = WsAction.REQ_CHIRP,
                senderPeerId = clientPeerId
            )
            sendWsMessage(msg)
        }
    }

    private suspend fun sendWsMessage(msg: WsMessage) {
        val session = activeSession
        if (session != null && session.isActive) {
            session.send(Frame.Text(msg.toJson()))
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        coroutineScope.launch {
            try {
                activeSession?.close()
            } catch (ignored: Exception) {}
            activeSession = null
            _connectionState.value = ClientConnectionState.DISCONNECTED
            _roomState.value = null
        }
    }
}
