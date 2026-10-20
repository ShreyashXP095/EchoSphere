package com.echosphere.app.network.udp

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ClockSample(
    val rttMicros: Long,
    val offsetMicros: Long
)

class ClockSyncEngine(
    private val isHost: Boolean,
    private val hostAddress: String? = null,
    private val udpPort: Int = 5005,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ClockSyncEngine"
        private const val PACKET_SIZE = 32 // 4 x int64 timestamps (t1, t2, t3, t4)
        private const val BURST_COUNT = 8
        private const val BURST_SPACING_MS = 25L
        private const val BURST_INTERVAL_MS = 2000L
        private const val EMA_ALPHA = 0.20 // As per specification Section 6
    }

    private var socket: DatagramSocket? = null
    private var engineJob: Job? = null

    // Filtered clock offset (theta) in microseconds
    private val _clockOffsetMicros = MutableStateFlow(0L)
    val clockOffsetMicros: StateFlow<Long> = _clockOffsetMicros.asStateFlow()

    private val _lastRttMicros = MutableStateFlow(0L)
    val lastRttMicros: StateFlow<Long> = _lastRttMicros.asStateFlow()

    private var manualOffsetMicros: Long = 0L

    fun setManualCalibrationOffset(offsetMs: Long) {
        manualOffsetMicros = offsetMs * 1000L
    }

    /**
     * Projects current Master Clock timestamp in microseconds
     */
    fun getMasterClockMicros(): Long {
        val clientNowMicros = SystemClock.elapsedRealtimeNanos() / 1000L
        return if (isHost) {
            clientNowMicros
        } else {
            clientNowMicros + _clockOffsetMicros.value + manualOffsetMicros
        }
    }

    /**
     * Projects current Master Clock timestamp in milliseconds
     */
    fun getMasterClockMs(): Long {
        return getMasterClockMicros() / 1000L
    }

    fun start() {
        if (engineJob != null) return

        engineJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                if (isHost) {
                    runHostClockServer()
                } else {
                    runClientClockLoop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "ClockSyncEngine error", e)
            }
        }
    }

    fun stop() {
        engineJob?.cancel()
        engineJob = null
        try {
            socket?.close()
        } catch (ignored: Exception) {}
        socket = null
    }

    /**
     * Host responds immediately with t2 (receive) and t3 (transmit)
     */
    private suspend fun runHostClockServer() = withContext(Dispatchers.IO) {
        socket = DatagramSocket(udpPort)
        val receiveBuffer = ByteArray(PACKET_SIZE)
        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

        Log.i(TAG, "Host Master Clock Authority listening on UDP :$udpPort")

        while (isActive) {
            try {
                socket?.receive(packet)
                val t2 = SystemClock.elapsedRealtimeNanos() / 1000L // Microseconds receive

                val buffer = ByteBuffer.wrap(packet.data, 0, packet.length).order(ByteOrder.BIG_ENDIAN)
                val t1 = buffer.long // Client send timestamp

                val t3 = SystemClock.elapsedRealtimeNanos() / 1000L // Microseconds transmit

                val replyBuffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
                replyBuffer.putLong(t1)
                replyBuffer.putLong(t2)
                replyBuffer.putLong(t3)
                replyBuffer.putLong(0L) // t4 will be filled by client

                val replyPacket = DatagramPacket(
                    replyBuffer.array(),
                    PACKET_SIZE,
                    packet.address,
                    packet.port
                )
                socket?.send(replyPacket)
            } catch (e: Exception) {
                if (!isActive) break
                Log.w(TAG, "Error in Host clock responder", e)
            }
        }
    }

    /**
     * Client dispatches an 8-packet burst spaced 25ms apart every 2.0s
     */
    private suspend fun runClientClockLoop() = withContext(Dispatchers.IO) {
        val targetHost = hostAddress ?: return@withContext
        val targetIp = InetAddress.getByName(targetHost)
        socket = DatagramSocket()
        socket?.soTimeout = 150 // 150ms timeout per probe

        Log.i(TAG, "Client SNTP ClockSyncEngine targeting Host at $targetHost:$udpPort")

        while (isActive) {
            val burstSamples = mutableListOf<ClockSample>()

            for (i in 0 until BURST_COUNT) {
                if (!isActive) break

                val t1 = SystemClock.elapsedRealtimeNanos() / 1000L
                val sendBuffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
                sendBuffer.putLong(t1)
                sendBuffer.putLong(0L)
                sendBuffer.putLong(0L)
                sendBuffer.putLong(0L)

                val sendPacket = DatagramPacket(sendBuffer.array(), PACKET_SIZE, targetIp, udpPort)
                try {
                    socket?.send(sendPacket)

                    val receiveBuffer = ByteArray(PACKET_SIZE)
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket?.receive(receivePacket)

                    val t4 = SystemClock.elapsedRealtimeNanos() / 1000L

                    val response = ByteBuffer.wrap(receivePacket.data, 0, receivePacket.length).order(ByteOrder.BIG_ENDIAN)
                    val recvT1 = response.long
                    val t2 = response.long
                    val t3 = response.long

                    if (recvT1 == t1) {
                        // Formula from Blueprint Section 6:
                        // Round-Trip Delay delta = (t4 - t1) - (t3 - t2)
                        // Clock Offset theta = [(t2 - t1) + (t3 - t4)] / 2
                        val delta = (t4 - t1) - (t3 - t2)
                        val theta = ((t2 - t1) + (t3 - t4)) / 2

                        burstSamples.add(ClockSample(delta, theta))
                    }
                } catch (e: Exception) {
                    // Packet loss on burst probe - ignored as outlier
                }

                delay(BURST_SPACING_MS)
            }

            // Statistical Outlier Rejection & EMA Smoothing:
            // 1. Sort by RTT (delta) ascending
            // 2. Discard top 4 samples with highest delay
            // 3. Compute median theta of remaining samples
            // 4. Update EMA with alpha = 0.20
            if (burstSamples.isNotEmpty()) {
                val sortedByRtt = burstSamples.sortedBy { it.rttMicros }
                val bestSamples = if (sortedByRtt.size > 4) {
                    sortedByRtt.take(sortedByRtt.size - 4) // Discard highest 4
                } else {
                    sortedByRtt.take(sortedByRtt.size / 2 + 1)
                }

                val sortedThetas = bestSamples.map { it.offsetMicros }.sorted()
                val medianTheta = sortedThetas[sortedThetas.size / 2]
                val avgRtt = bestSamples.map { it.rttMicros }.average().toLong()

                _lastRttMicros.value = avgRtt

                val currentEma = _clockOffsetMicros.value
                val newEma = if (currentEma == 0L) {
                    medianTheta
                } else {
                    (EMA_ALPHA * medianTheta + (1.0 - EMA_ALPHA) * currentEma).toLong()
                }

                _clockOffsetMicros.value = newEma
                Log.d(TAG, "Clock Sync update: ThetaEMA=${newEma}us, RTT=${avgRtt}us, Samples=${burstSamples.size}")
            }

            delay(BURST_INTERVAL_MS)
        }
    }
}
