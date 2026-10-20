package com.echosphere.app.network.udp

import android.os.SystemClock
import android.util.Log
import com.echosphere.app.audio.nativebridge.NativeAudioBridge
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class UdpAudioTransport(
    private val udpPort: Int = 5004,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "UdpAudioTransport"
        const val MAGIC_HEADER: Short = 0x4D53 // "MS" in ASCII
        const val HEADER_SIZE = 16
        const val BUFFER_PLAY_OFFSET_US = 120_000L // 120ms presentation buffer (Section 8)
    }

    private var socket: DatagramSocket? = null
    private var transportJob: Job? = null
    private val sequenceNumber = AtomicInteger(0)

    // Peer IP addresses for multi-unicast
    private val targetPeerIps = CopyOnWriteArrayList<InetAddress>()

    fun addPeerTarget(ipAddress: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val inet = InetAddress.getByName(ipAddress)
                if (!targetPeerIps.contains(inet)) {
                    targetPeerIps.add(inet)
                    Log.i(TAG, "Added multi-unicast peer target: $ipAddress")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed resolving peer IP $ipAddress", e)
            }
        }
    }

    fun removePeerTarget(ipAddress: String) {
        targetPeerIps.removeAll { it.hostAddress == ipAddress }
    }

    /**
     * Sync the peer target list to match the given list of IPs.
     * Adds new peers and removes stale ones.
     */
    fun syncPeerTargets(currentIps: List<String>) {
        coroutineScope.launch(Dispatchers.IO) {
            // Remove peers no longer in the list
            val currentSet = currentIps.toSet()
            targetPeerIps.removeAll { it.hostAddress !in currentSet }

            // Add new peers
            for (ip in currentIps) {
                try {
                    val inet = InetAddress.getByName(ip)
                    if (!targetPeerIps.contains(inet)) {
                        targetPeerIps.add(inet)
                        Log.i(TAG, "Synced new peer target: $ip")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed resolving peer IP $ip during sync", e)
                }
            }
        }
    }

    fun clearPeers() {
        targetPeerIps.clear()
    }

    /**
     * Start UDP Receiver on port 5004 for Client nodes (and Host loopback)
     */
    fun startReceiver() {
        if (transportJob != null) return

        transportJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                socket = DatagramSocket(udpPort)
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.i(TAG, "UDP Audio Receiver listening on port $udpPort")

                while (isActive) {
                    socket?.receive(packet)
                    if (packet.length < HEADER_SIZE) continue

                    val byteBuffer = ByteBuffer.wrap(packet.data, 0, packet.length).order(ByteOrder.BIG_ENDIAN)
                    val magic = byteBuffer.short
                    if (magic != MAGIC_HEADER) {
                        // Drop packet on magic mismatch
                        continue
                    }

                    val seq = byteBuffer.int
                    val playAtMasterUs = byteBuffer.long
                    val payloadLen = byteBuffer.short.toInt() and 0xFFFF

                    if (payloadLen > 0 && byteBuffer.remaining() >= payloadLen) {
                        val opusPayload = ByteArray(payloadLen)
                        byteBuffer.get(opusPayload)

                        // Push into native JitterBuffer
                        NativeAudioBridge.nativePushUdpPacket(seq, playAtMasterUs, opusPayload)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "UDP Audio Receiver error", e)
                }
            }
        }
    }

    /**
     * Broadcast an encoded Opus audio frame to all connected peers + local Host loopback
     */
    fun broadcastOpusFrame(opusData: ByteArray, opusLength: Int, captureTimestampUs: Long) {
        val seq = sequenceNumber.incrementAndGet()
        val playAtMasterUs = captureTimestampUs + BUFFER_PLAY_OFFSET_US // Tplay = Tcapture + 120,000 us

        val packetBytes = ByteArray(HEADER_SIZE + opusLength)
        val buffer = ByteBuffer.wrap(packetBytes).order(ByteOrder.BIG_ENDIAN)

        // 16-byte Header (Section 8)
        buffer.putShort(MAGIC_HEADER)
        buffer.putInt(seq)
        buffer.putLong(playAtMasterUs)
        buffer.putShort(opusLength.toShort())
        buffer.put(opusData, 0, opusLength)

        // 1. Loopback to Host's own local Oboe DAC so Host speaker matches clients
        val localPayload = ByteArray(opusLength)
        System.arraycopy(opusData, 0, localPayload, 0, opusLength)
        NativeAudioBridge.nativePushUdpPacket(seq, playAtMasterUs, localPayload)

        // 2. Multi-unicast to connected peers over UDP port 5004
        coroutineScope.launch(Dispatchers.IO) {
            val sock = socket ?: DatagramSocket().also { socket = it }

            // Send to registered peer IPs
            for (peer in targetPeerIps) {
                try {
                    val dgram = DatagramPacket(packetBytes, packetBytes.size, peer, udpPort)
                    sock.send(dgram)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed unicast to ${peer.hostAddress}", e)
                }
            }

            // Fallback: Also broadcast to standard Android hotspot subnet if no specific peers
            if (targetPeerIps.isEmpty()) {
                try {
                    val broadcastAddr = InetAddress.getByName("192.168.43.255")
                    val dgram = DatagramPacket(packetBytes, packetBytes.size, broadcastAddr, udpPort)
                    sock.send(dgram)
                } catch (e: Exception) {
                    Log.w(TAG, "Subnet broadcast fallback failed", e)
                }
            }
        }
    }

    fun stop() {
        transportJob?.cancel()
        transportJob = null
        try {
            socket?.close()
        } catch (ignored: Exception) {}
        socket = null
        targetPeerIps.clear()
    }
}
