package com.echosphere.app.audio.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echosphere.app.R
import com.echosphere.app.audio.capture.SystemAudioCapturer
import com.echosphere.app.network.udp.UdpAudioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MediaCaptureService : Service() {

    companion object {
        private const val TAG = "MediaCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "echosphere_media_stream"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START = "com.echosphere.app.action.START_MEDIA_CAPTURE"
        const val ACTION_STOP = "com.echosphere.app.action.STOP_MEDIA_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var mediaProjection: MediaProjection? = null
    private var capturer: SystemAudioCapturer? = null
    private var udpTransport: UdpAudioTransport? = null

    inner class LocalBinder : Binder() {
        fun getService(): MediaCaptureService = this@MediaCaptureService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundWithProjection(resultCode, resultData)
                }
            }
            ACTION_STOP -> {
                stopCaptureInternal()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithProjection(resultCode: Int, data: Intent) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EchoSphere Multi-Speaker Broadcast")
            .setContentText("Capturing and broadcasting system audio to connected peers")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.let { projection ->
            val transport = UdpAudioTransport(coroutineScope = serviceScope)
            this.udpTransport = transport

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                capturer = SystemAudioCapturer(projection, transport, serviceScope).apply {
                    startCapture()
                }
            }
        }
        Log.i(TAG, "MediaCaptureService started in foreground")
    }

    private fun stopCaptureInternal() {
        capturer?.stopCapture()
        capturer = null

        udpTransport?.stop()
        udpTransport = null

        mediaProjection?.stop()
        mediaProjection = null
        Log.i(TAG, "MediaCaptureService capture stopped")
    }

    override fun onDestroy() {
        stopCaptureInternal()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "EchoSphere Audio Broadcast",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notification while broadcasting system audio"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
