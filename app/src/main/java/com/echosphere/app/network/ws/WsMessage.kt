package com.echosphere.app.network.ws

import com.echosphere.app.data.model.PlaybackMode
import com.echosphere.app.data.model.QueueItem
import com.echosphere.app.data.model.RoomState
import com.echosphere.app.data.model.TrackPlaybackState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class WsAction {
    JOIN_REQUEST,
    JOIN_ACK,
    ROOM_STATE_UPDATE,
    QUEUE_ADD,
    QUEUE_VOTE,
    QUEUE_REMOVE,
    PLAY_TRACK,
    PAUSE_TRACK,
    SEEK_TRACK,
    SWITCH_MODE,
    SYNC_STATE,
    REQ_CHIRP,
    CHIRP_SCHEDULED,
    HEARTBEAT
}

@Serializable
data class WsMessage(
    val action: WsAction,
    val senderPeerId: String,
    val payloadJson: String = ""
) {
    fun toJson(): String = jsonInstance.encodeToString(serializer(), this)

    companion object {
        val jsonInstance = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(raw: String): WsMessage? {
            return try {
                jsonInstance.decodeFromString(serializer(), raw)
            } catch (e: Exception) {
                null
            }
        }
    }
}

// Payload DTOs
@Serializable
data class JoinPayload(
    val displayName: String,
    val deviceModel: String,
    val clientIp: String = ""
)

@Serializable
data class QueueAddPayload(
    val videoId: String,
    val title: String,
    val artist: String = "YouTube Artist",
    val durationSeconds: Long = 210
)

@Serializable
data class QueueVotePayload(
    val itemId: String
)

@Serializable
data class PlayTrackPayload(
    val trackId: String
)

@Serializable
data class SeekTrackPayload(
    val positionMs: Long
)

@Serializable
data class SwitchModePayload(
    val targetMode: PlaybackMode
)

@Serializable
data class SyncStatePayload(
    val videoId: String,
    val isPlaying: Boolean,
    val trackPositionMs: Long,
    val atMasterClockMs: Long
)

@Serializable
data class ChirpSchedulePayload(
    val t0MasterUs: Long
)
