package com.echosphere.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RoomInfo(
    val roomId: String,
    val roomName: String,
    val hostIp: String,
    val wsPort: Int = 8080,
    val udpClockPort: Int = 5005,
    val udpAudioPort: Int = 5004,
    val hostDeviceModel: String = "Android Device",
    val protocolVersion: Int = 1
) {
    fun toQrString(): String {
        return Json.encodeToString(this)
    }

    companion object {
        fun fromQrString(raw: String): RoomInfo? {
            return try {
                Json.decodeFromString<RoomInfo>(raw)
            } catch (e: Exception) {
                null
            }
        }
    }
}
