package com.echosphere.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class QueueItem(
    val id: String,
    val videoId: String,
    val title: String,
    val artist: String = "YouTube Artist",
    val durationSeconds: Long = 210,
    val addedByPeerId: String,
    val addedByName: String,
    val upvotes: Int = 1,
    val upvotedPeerIds: List<String> = emptyList(),
    val addedAtTimestampMs: Long = System.currentTimeMillis()
)
