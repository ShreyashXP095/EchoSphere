package com.echosphere.app.network.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.echosphere.app.data.model.DiscoveredRoom
import com.echosphere.app.data.model.DiscoveryMethod
import com.echosphere.app.data.model.RoomInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress
import java.nio.charset.StandardCharsets

class NsdDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "NsdDiscoveryManager"
        const val SERVICE_TYPE = "_echosphere._tcp."
        const val KEY_ROOM_ID = "rid"
        const val KEY_ROOM_NAME = "rname"
        const val KEY_MODEL = "model"
        const val KEY_VERSION = "v"
        const val KEY_CLOCK_PORT = "clk"
        const val KEY_AUDIO_PORT = "aud"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredRooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    val discoveredRooms: StateFlow<List<DiscoveredRoom>> = _discoveredRooms.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Advertise the Host room via mDNS DNS-SD
     */
    fun startAdvertising(roomInfo: RoomInfo) {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager is unavailable on this device")
            return
        }
        if (_isAdvertising.value) {
            stopAdvertising()
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "EchoSphere-${roomInfo.roomId.take(6)}"
            serviceType = SERVICE_TYPE
            port = roomInfo.wsPort

            // Attributes
            setAttribute(KEY_ROOM_ID, roomInfo.roomId)
            setAttribute(KEY_ROOM_NAME, roomInfo.roomName)
            setAttribute(KEY_MODEL, Build.MODEL)
            setAttribute(KEY_VERSION, roomInfo.protocolVersion.toString())
            setAttribute(KEY_CLOCK_PORT, roomInfo.udpClockPort.toString())
            setAttribute(KEY_AUDIO_PORT, roomInfo.udpAudioPort.toString())
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                Log.i(TAG, "mDNS Service registered successfully: ${registeredService.serviceName}")
                _isAdvertising.value = true
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS Service registration failed with errorCode: $errorCode")
                _isAdvertising.value = false
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS Service unregistered: ${serviceInfo.serviceName}")
                _isAdvertising.value = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS Service unregistration failed with errorCode: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during registerService", e)
        }
    }

    fun stopAdvertising() {
        registrationListener?.let { listener ->
            try {
                nsdManager?.unregisterService(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering service", e)
            }
            registrationListener = null
        }
        _isAdvertising.value = false
    }

    /**
     * Start discovering available EchoSphere Hosts on the LAN / Hotspot
     */
    fun startDiscovery() {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager is unavailable")
            return
        }
        if (_isDiscovering.value) return

        acquireMulticastLock()
        _discoveredRooms.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }

            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "Discovery started for $regType")
                _isDiscovering.value = true
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped for $serviceType")
                _isDiscovering.value = false
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "Service found: ${service.serviceName}, type: ${service.serviceType}")
                resolveFoundService(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.i(TAG, "Service lost: ${service.serviceName}")
                _discoveredRooms.update { list ->
                    list.filterNot { it.roomInfo.roomName == service.serviceName }
                }
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovering services", e)
        }
    }

    private fun resolveFoundService(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val hostAddress = serviceInfo.host?.hostAddress
                if (hostAddress.isNullOrBlank()) {
                    Log.w(TAG, "Resolved service has empty host address")
                    return
                }

                val attributes = serviceInfo.attributes
                val roomId = attributes[KEY_ROOM_ID]?.let { String(it, StandardCharsets.UTF_8) } ?: serviceInfo.serviceName
                val roomName = attributes[KEY_ROOM_NAME]?.let { String(it, StandardCharsets.UTF_8) } ?: serviceInfo.serviceName
                val model = attributes[KEY_MODEL]?.let { String(it, StandardCharsets.UTF_8) } ?: "Peer Device"
                val version = attributes[KEY_VERSION]?.let { String(it, StandardCharsets.UTF_8).toIntOrNull() } ?: 1
                val clockPort = attributes[KEY_CLOCK_PORT]?.let { String(it, StandardCharsets.UTF_8).toIntOrNull() } ?: 5005
                val audioPort = attributes[KEY_AUDIO_PORT]?.let { String(it, StandardCharsets.UTF_8).toIntOrNull() } ?: 5004

                val roomInfo = RoomInfo(
                    roomId = roomId,
                    roomName = roomName,
                    hostIp = hostAddress,
                    wsPort = serviceInfo.port,
                    udpClockPort = clockPort,
                    udpAudioPort = audioPort,
                    hostDeviceModel = model,
                    protocolVersion = version
                )

                val discovered = DiscoveredRoom(
                    roomInfo = roomInfo,
                    discoveryMethod = DiscoveryMethod.MDNS
                )

                _discoveredRooms.update { currentList ->
                    val filtered = currentList.filterNot { it.roomInfo.roomId == roomInfo.roomId || it.roomInfo.hostIp == roomInfo.hostIp }
                    filtered + discovered
                }
            }
        }

        try {
            nsdManager?.resolveService(service, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to call resolveService", e)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping service discovery", e)
            }
            discoveryListener = null
        }
        releaseMulticastLock()
        _isDiscovering.value = false
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager?.createMulticastLock("EchoSphereMulticastLock")?.apply {
                setReferenceCounted(true)
            }
        }
        multicastLock?.let {
            if (!it.isHeld) {
                it.acquire()
                Log.d(TAG, "Wifi MulticastLock acquired")
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wifi MulticastLock released")
            }
        }
    }
}
