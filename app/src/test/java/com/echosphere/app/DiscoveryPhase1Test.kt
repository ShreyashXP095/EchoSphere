package com.echosphere.app

import com.echosphere.app.data.model.DiscoveredRoom
import com.echosphere.app.data.model.DiscoveryMethod
import com.echosphere.app.data.model.RoomInfo
import com.echosphere.app.network.qr.QrCodeManager
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPhase1Test {

    @Test
    fun testRoomInfoQrSerializationAndParsing() {
        val original = RoomInfo(
            roomId = "sphere-test-1234",
            roomName = "Neon Rooftop Lounge",
            hostIp = "192.168.1.42",
            wsPort = 8080,
            udpClockPort = 5005,
            udpAudioPort = 5004,
            hostDeviceModel = "Pixel 8 Pro",
            protocolVersion = 1
        )

        // 1. Serialize to JSON
        val qrPayload = original.toQrString()
        assertTrue("QR payload must contain room name", qrPayload.contains("Neon Rooftop Lounge"))
        assertTrue("QR payload must contain host IP", qrPayload.contains("192.168.1.42"))

        // 2. Parse back
        val decoded = RoomInfo.fromQrString(qrPayload)
        assertNotNull("Decoded RoomInfo must not be null", decoded)
        assertEquals(original.roomId, decoded?.roomId)
        assertEquals(original.roomName, decoded?.roomName)
        assertEquals(original.hostIp, decoded?.hostIp)
        assertEquals(8080, decoded?.wsPort)
        assertEquals(5005, decoded?.udpClockPort)
        assertEquals(5004, decoded?.udpAudioPort)

        // 3. Test QrCodeManager wrapper
        val discoveredRoom = QrCodeManager.parseScannedRoom(qrPayload)
        assertNotNull(discoveredRoom)
        assertEquals(DiscoveryMethod.QR_CODE, discoveredRoom?.discoveryMethod)
        assertEquals("Pixel 8 Pro", discoveredRoom?.roomInfo?.hostDeviceModel)
    }

    @Test
    fun testMalformedQrPayloadHandling() {
        val invalidPayload1 = "{ this is not json }"
        assertNull(RoomInfo.fromQrString(invalidPayload1))
        assertNull(QrCodeManager.parseScannedRoom(invalidPayload1))

        val invalidPayload2 = ""
        assertNull(RoomInfo.fromQrString(invalidPayload2))
    }

    @Test
    fun testDefaultPortsComplianceWithBlueprint() {
        val defaultRoom = RoomInfo(
            roomId = "test-ports",
            roomName = "Party",
            hostIp = "192.168.43.1"
        )
        // Must match Figure 1 and Section 4 of the architectural specification:
        assertEquals("WebSocket server must default to port 8080", 8080, defaultRoom.wsPort)
        assertEquals("SNTP Clock server must default to UDP port 5005", 5005, defaultRoom.udpClockPort)
        assertEquals("Opus audio broadcast must default to UDP port 5004", 5004, defaultRoom.udpAudioPort)
    }
}
