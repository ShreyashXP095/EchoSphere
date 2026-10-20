package com.echosphere.app.audio.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.*
import com.echosphere.app.network.udp.ClockSyncEngine
import com.echosphere.app.network.ws.SyncStatePayload
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class PllTelemetry(
    val driftErrorMs: Long = 0,
    val currentRate: Double = 1.0,
    val tier: Int = 3,
    val isPhaseLocked: Boolean = true
)

class YouTubeSyncPlayer(
    private val context: Context,
    private val clockSyncEngine: ClockSyncEngine,
    private val isHost: Boolean,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "YouTubeSyncPlayer"
        private const val PLL_LOOP_INTERVAL_MS = 250L // Evaluated every 250ms as per specification
        private const val TIER1_HARD_SEEK_THRESHOLD_MS = 150L
        private const val TIER2_PLL_PULL_IN_THRESHOLD_MS = 8L
    }

    private var webView: WebView? = null
    private var isPlayerReady = false
    private var currentVideoId: String? = null
    private var lastReportedTrackSec: Double = 0.0

    private var targetSyncState: SyncStatePayload? = null
    private var pllJob: Job? = null

    private val _telemetry = MutableStateFlow(PllTelemetry())
    val telemetry: StateFlow<PllTelemetry> = _telemetry.asStateFlow()

    @SuppressLint("SetJavaScriptEnabled")
    fun attachWebView(view: WebView) {
        this.webView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        view.addJavascriptInterface(PllJsBridge(), "EchoSphereBridge")
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "WebView container loaded successfully")
            }
        }

        view.loadDataWithBaseURL("https://www.youtube.com", getPlayerHtml(), "text/html", "UTF-8", null)
    }

    fun onSyncStateReceived(syncState: SyncStatePayload) {
        this.targetSyncState = syncState

        if (syncState.videoId != currentVideoId) {
            currentVideoId = syncState.videoId
            loadVideo(syncState.videoId)
        }

        if (syncState.isPlaying) {
            playVideo()
        } else {
            pauseVideo()
        }

        // Start PLL loop on clients
        if (!isHost && pllJob == null) {
            startPllEvaluationLoop()
        }
    }

    private fun startPllEvaluationLoop() {
        pllJob = coroutineScope.launch {
            while (isActive) {
                evaluatePllStep()
                delay(PLL_LOOP_INTERVAL_MS)
            }
        }
    }

    /**
     * Executes the 3-Tier Phase-Locked Loop (PLL) drift calculation
     */
    private fun evaluatePllStep() {
        val sync = targetSyncState ?: return
        if (!isPlayerReady || !sync.isPlaying) return

        // Instantaneous master clock projection
        val currentMasterMs = clockSyncEngine.getMasterClockMs()
        val elapsedSinceSyncMs = (currentMasterMs - sync.atMasterClockMs).coerceAtLeast(0)
        val expectedTrackPosMs = sync.trackPositionMs + elapsedSinceSyncMs

        val actualTrackPosMs = (lastReportedTrackSec * 1000.0).toLong()

        // Edrift = Pactual - [Phost + (Tmaster - Tat_master)]
        val driftErrorMs = actualTrackPosMs - expectedTrackPosMs
        val absDrift = abs(driftErrorMs)

        when {
            // Tier 1 - Hard Seek (|Edrift| > 150 ms)
            absDrift > TIER1_HARD_SEEK_THRESHOLD_MS -> {
                val seekSec = expectedTrackPosMs / 1000.0
                executeJs("player.seekTo($seekSec, true); player.setPlaybackRate(1.0);")
                _telemetry.value = PllTelemetry(
                    driftErrorMs = driftErrorMs,
                    currentRate = 1.0,
                    tier = 1,
                    isPhaseLocked = false
                )
                Log.d(TAG, "Tier 1 Hard Seek engaged: drift=${driftErrorMs}ms, seekTo=${seekSec}s")
            }

            // Tier 2 - Proportional Pitch-Safe Pull-In (8 ms < |Edrift| <= 150 ms)
            absDrift > TIER2_PLL_PULL_IN_THRESHOLD_MS -> {
                val targetRate = if (driftErrorMs < -TIER2_PLL_PULL_IN_THRESHOLD_MS) {
                    1.03 // Lagging behind -> Speed up 3%
                } else {
                    0.97 // Ahead -> Slow down 3%
                }
                executeJs("player.setPlaybackRate($targetRate);")
                _telemetry.value = PllTelemetry(
                    driftErrorMs = driftErrorMs,
                    currentRate = targetRate,
                    tier = 2,
                    isPhaseLocked = false
                )
            }

            // Tier 3 - Phase Lock (|Edrift| <= 8 ms)
            else -> {
                executeJs("player.setPlaybackRate(1.0);")
                _telemetry.value = PllTelemetry(
                    driftErrorMs = driftErrorMs,
                    currentRate = 1.0,
                    tier = 3,
                    isPhaseLocked = true
                )
            }
        }

        // Query current track time for the next step
        executeJs("if (player && player.getCurrentTime) { EchoSphereBridge.onTimeUpdate(player.getCurrentTime()); }")
    }

    private fun loadVideo(videoId: String) {
        executeJs("if (player && player.loadVideoById) { player.loadVideoById('$videoId'); }")
    }

    fun playVideo() {
        executeJs("if (player && player.playVideo) { player.playVideo(); }")
    }

    fun pauseVideo() {
        executeJs("if (player && player.pauseVideo) { player.pauseVideo(); }")
    }

    fun seekTo(seconds: Double) {
        executeJs("if (player && player.seekTo) { player.seekTo($seconds, true); }")
    }

    private fun executeJs(script: String) {
        coroutineScope.launch(Dispatchers.Main) {
            webView?.evaluateJavascript(script, null)
        }
    }

    fun destroy() {
        pllJob?.cancel()
        pllJob = null
        webView?.destroy()
        webView = null
    }

    inner class PllJsBridge {
        @JavascriptInterface
        fun onReady() {
            isPlayerReady = true
            Log.i(TAG, "YouTube IFrame player ready")
        }

        @JavascriptInterface
        fun onTimeUpdate(seconds: Double) {
            lastReportedTrackSec = seconds
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            Log.d(TAG, "YouTube player state change: $state")
        }
    }

    private fun getPlayerHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                body, html { margin: 0; padding: 0; width: 100%; height: 100%; background-color: #0A0C14; overflow: hidden; }
                #player { width: 100%; height: 100%; }
            </style>
        </head>
        <body>
            <div id="player"></div>
            <script>
                var tag = document.createElement('script');
                tag.src = "https://www.youtube.com/iframe_api";
                var firstScriptTag = document.getElementsByTagName('script')[0];
                firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                var player;
                function onYouTubeIframeAPIReady() {
                    player = new YT.Player('player', {
                        height: '100%',
                        width: '100%',
                        playerVars: {
                            'autoplay': 0,
                            'controls': 0,
                            'rel': 0,
                            'playsinline': 1,
                            'enablejsapi': 1,
                            'origin': 'https://www.youtube.com'
                        },
                        events: {
                            'onReady': function(event) {
                                if (window.EchoSphereBridge) {
                                    window.EchoSphereBridge.onReady();
                                }
                            },
                            'onStateChange': function(event) {
                                if (window.EchoSphereBridge) {
                                    window.EchoSphereBridge.onStateChange(event.data);
                                }
                            }
                        }
                    });
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}
