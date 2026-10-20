package com.echosphere.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PeerInfo(
    val peerId: String,
    val displayName: String,
    val deviceModel: String,
    val ipAddress: String,
    val isHost: Boolean = false,
    val latencyMs: Long = 0
)

@Serializable
data class TrackPlaybackState(
    val isPlaying: Boolean = false,
    val trackPositionMs: Long = 0,
    val atMasterClockMs: Long = 0,
    val lastUpdateEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class RoomState(
    val roomId: String,
    val roomName: String,
    val hostPeerId: String,
    val activeMode: PlaybackMode = PlaybackMode.MODE_A_YOUTUBE,
    val currentTrack: QueueItem? = null,
    val queue: List<QueueItem> = emptyList(),
    val connectedPeers: List<PeerInfo> = emptyList(),
    val playbackState: TrackPlaybackState = TrackPlaybackState()
) {
    /**
     * Re-sort queue by upvotes descending, then chronologically
     */
    fun getSortedQueue(): List<QueueItem> {
        return queue.sortedWith(
            compareByDescending<QueueItem> { it.upvotes }
                .thenBy { it.addedAtTimestampMs }
        )
    }
}
