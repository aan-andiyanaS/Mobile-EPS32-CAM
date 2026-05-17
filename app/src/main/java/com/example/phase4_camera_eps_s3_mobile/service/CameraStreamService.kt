package com.example.phase4_camera_eps_s3_mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.phase4_camera_eps_s3_mobile.R
import com.example.phase4_camera_eps_s3_mobile.ui.CameraStreamActivity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * CameraStreamService v3 — Koneksi stabil untuk sistem safety-critical.
 *
 * Fitur anti-putus:
 *  1. PARTIAL_WAKE_LOCK   — CPU tidak tidur selama streaming
 *  2. WIFI_MODE_*_PERF    — WiFi radio tidak masuk power-saving mode
 *  3. NetworkCallback     — deteksi perubahan jaringan & trigger reconnect
 *  4. Exponential backoff — 1s→2s→4s→8s (max) reconnect delay
 *  5. pingInterval 5s     — WebSocket ping setiap 5 detik menjaga koneksi hidup
 *
 * Notifikasi:
 *  - Foreground (IMPORTANCE_LOW, ongoing) — selalu ada di status bar
 *  - Alert (IMPORTANCE_HIGH, heads-up)    — pop-up saat ESP32 berhasil terkoneksi
 */
class CameraStreamService : Service() {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        private const val TAG                    = "CameraStreamService"
        private const val NOTIF_CH_FG            = "camera_stream_channel"
        private const val NOTIF_CH_ALERT         = "esp32_connected_alert"
        private const val NOTIF_ID_FG            = 1001
        private const val NOTIF_ID_ALERT         = 1002
        private const val FRAME_TYPE_JPEG        = 0x01.toByte()
        private const val FRAME_HEADER_SZ        = 9
        private const val RECONNECT_BASE_MS      = 1_000L
        private const val RECONNECT_MAX_MS       = 8_000L
        const val EXTRA_IP = "esp32_ip"

        fun createStartIntent(ctx: Context, ip: String) =
            Intent(ctx, CameraStreamService::class.java).apply { putExtra(EXTRA_IP, ip) }
    }

    inner class LocalBinder : Binder() {
        fun getService(): CameraStreamService = this@CameraStreamService
    }

    private val binder       = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job?             = null
    private var activeWebSocket: WebSocket? = null
    private var reconnectAttempts           = 0
    private var ipAddress                   = ""

    // Anti-putus locks
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock?  = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // OkHttp — pingInterval 15 detik agar koneksi tidak dianggap idle oleh OS
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // KRITIS: DROP_OLDEST memastikan Android SELALU menampilkan frame TERBARU.
    // replay=1 + extraBufferCapacity=8 = 9 frame antri × 66ms = 594ms lag tersembunyi!
    // Dengan DROP_OLDEST: frame lama langsung dibuang saat buffer penuh.
    private val _frameFlow = MutableSharedFlow<ByteArray>(
        replay              = 0,
        extraBufferCapacity = 2,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val frameFlow: SharedFlow<ByteArray> = _frameFlow

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = intent?.getStringExtra(EXTRA_IP)
        val ipChanged = !ip.isNullOrEmpty() && ip != ipAddress
        if (!ip.isNullOrEmpty()) ipAddress = ip

        if (ipAddress.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        createNotificationChannels()

        // Jangan acquire ulang jika lock sudah dipegang (cegah resource leak)
        if (wakeLock?.isHeld != true) acquireWakeLock()
        if (wifiLock?.isHeld != true) acquireWifiLock()
        if (networkCallback == null) registerNetworkCallback()

        startForeground(NOTIF_ID_FG, buildForegroundNotif())

        // CRITICAL FIX: Hanya start/restart streaming jika:
        //   1. IP berubah (device baru / provisioning ulang), ATAU
        //   2. Stream job belum pernah jalan / sudah selesai (bukan sedang aktif)
        // Tanpa guard ini: setiap Activity.onStart() → startService() → onStartCommand()
        // akan membunuh WebSocket yang sedang aktif dan memulai ulang (loop putus-nyambung).
        val streamIsActive = streamJob?.isActive == true
        if (ipChanged || !streamIsActive) {
            Log.d(TAG, "Starting stream — ipChanged=$ipChanged, wasActive=$streamIsActive")
            startStreaming(ipAddress)
        } else {
            Log.d(TAG, "Stream already active — skipping restart")
        }

        return START_STICKY
    }


    // ── Streaming loop ──────────────────────────────────────────────────────

    private fun startStreaming(ip: String) {
        streamJob?.cancel()
        activeWebSocket?.cancel()
        activeWebSocket   = null
        reconnectAttempts = 0

        streamJob = serviceScope.launch {
            while (isActive) {
                val done = CompletableDeferred<Unit>()
                setConnectionState(ConnectionState.CONNECTING)
                Log.d(TAG, "Connecting ws://$ip/ws (attempt #${reconnectAttempts + 1})")

                client.newWebSocket(Request.Builder().url("ws://$ip/ws").build(),
                    object : WebSocketListener() {

                    override fun onOpen(ws: WebSocket, r: Response) {
                        activeWebSocket   = ws
                        reconnectAttempts = 0
                        setConnectionState(ConnectionState.CONNECTED)
                        sendConnectedHeadsUp()
                    }

                    override fun onMessage(ws: WebSocket, bytes: ByteString) {
                        val raw = bytes.toByteArray()
                        if (raw.size < FRAME_HEADER_SZ + 1 || raw[0] != FRAME_TYPE_JPEG) return
                        serviceScope.launch { _frameFlow.emit(raw.copyOfRange(FRAME_HEADER_SZ, raw.size)) }
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                        Log.e(TAG, "WS failure: ${t.message}")
                        activeWebSocket = null
                        setConnectionState(ConnectionState.DISCONNECTED)
                        done.complete(Unit)
                    }

                    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                        ws.close(1000, null)
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        activeWebSocket = null
                        setConnectionState(ConnectionState.DISCONNECTED)
                        done.complete(Unit)
                    }
                })

                try { done.await() }
                catch (e: CancellationException) { activeWebSocket?.close(1000, "stopped"); break }

                if (isActive) {
                    // Exponential backoff: 1s → 2s → 4s → 8s → 8s → ...
                    val wait = minOf(RECONNECT_BASE_MS * (1L shl reconnectAttempts.coerceAtMost(3)),
                        RECONNECT_MAX_MS)
                    reconnectAttempts++
                    Log.d(TAG, "Reconnect in ${wait}ms...")
                    delay(wait)
                }
            }
        }
    }

    // ── WakeLock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        runCatching {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraStream::WakeLock")
                .apply { acquire(12 * 60 * 60 * 1000L) } // max 12 jam
            Log.d(TAG, "WakeLock acquired")
        }.onFailure { Log.e(TAG, "WakeLock failed: ${it.message}") }
    }

    // ── WifiLock ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "CameraStream::WifiLock")
                .apply { setReferenceCounted(false); acquire() }
            Log.d(TAG, "WifiLock acquired")
        }.onFailure { Log.e(TAG, "WifiLock failed: ${it.message}") }
    }

    // ── NetworkCallback ───────────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        runCatching {
            val cm  = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available")
                    if (_connectionState.value == ConnectionState.DISCONNECTED && ipAddress.isNotEmpty()) {
                        serviceScope.launch { delay(300); startStreaming(ipAddress) }
                    }
                }
                override fun onLost(network: Network) {
                    Log.w(TAG, "Network lost — force-close WS")
                    activeWebSocket?.cancel()
                    activeWebSocket = null
                    setConnectionState(ConnectionState.DISCONNECTED)
                }
            }
            cm.registerNetworkCallback(req, networkCallback!!)
        }.onFailure { Log.e(TAG, "NetworkCallback failed: ${it.message}") }
    }

    // ── State + Notification helpers ──────────────────────────────────────────

    private fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_FG, buildForegroundNotif())
    }

    /** Heads-up pop-up notification (seperti notifikasi WhatsApp) */
    private fun sendConnectedHeadsUp() {
        runCatching {
            val pi = PendingIntent.getActivity(this, 0,
                CameraStreamActivity.createIntent(this, ipAddress),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notif = NotificationCompat.Builder(this, NOTIF_CH_ALERT)
                .setContentTitle("ESP32-S3 Terkoneksi \uD83D\uDFE2")
                .setContentText("Menerima data dari ESP32-S3 ($ipAddress)")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID_ALERT, notif)
        }
    }

    /** Foreground notification (ongoing, tidak bisa di-dismiss) */
    private fun buildForegroundNotif(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            CameraStreamActivity.createIntent(this, ipAddress),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val (title, text) = when (_connectionState.value) {
            ConnectionState.CONNECTED    -> "ESP32-S3 Terkoneksi" to "Menerima data dari ESP32-S3 ($ipAddress)"
            ConnectionState.CONNECTING   -> "ESP32-S3 Camera"     to "Menghubungkan ke $ipAddress..."
            ConnectionState.DISCONNECTED -> "ESP32-S3 Camera"     to "Terputus — mencoba reconnect..."
        }
        return NotificationCompat.Builder(this, NOTIF_CH_FG)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi).setOngoing(true).setShowWhen(false)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            NOTIF_CH_FG, "Camera Stream Status", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(
            NOTIF_CH_ALERT, "ESP32 Connection Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply { enableVibration(true) })
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed — service tetap jalan")
    }

    override fun onDestroy() {
        super.onDestroy()
        activeWebSocket?.close(1000, "Service destroyed")
        serviceScope.cancel()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        networkCallback?.let { cb ->
            runCatching {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            }
        }
    }
}
