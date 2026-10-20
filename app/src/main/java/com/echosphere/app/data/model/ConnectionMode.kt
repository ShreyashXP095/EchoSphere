package com.echosphere.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackMode {
    MODE_A_YOUTUBE,      // Bandwidth-efficient HTML5 WebView PLL sync
    MODE_B_SYSTEM_AUDIO  // External system capture via MediaProjection + Opus UDP
}

@Serializable
enum class PeerRole {
    HOST,
    CLIENT,
    DISCONNECTED
}
