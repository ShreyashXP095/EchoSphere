package com.echosphere.app.data.model

data class DiscoveredRoom(
    val roomInfo: RoomInfo,
    val discoveryMethod: DiscoveryMethod,
    val lastSeenTimestampMs: Long = System.currentTimeMillis()
)

enum class DiscoveryMethod {
    MDNS,
    QR_CODE,
    MANUAL_IP
}
