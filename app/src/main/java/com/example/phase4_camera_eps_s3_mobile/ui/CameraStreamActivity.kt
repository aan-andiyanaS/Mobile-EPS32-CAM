package com.example.phase4_camera_eps_s3_mobile.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import com.example.phase4_camera_eps_s3_mobile.databinding.ActivityCameraStreamBinding
import com.example.phase4_camera_eps_s3_mobile.service.CameraStreamService
import com.example.phase4_camera_eps_s3_mobile.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraStreamActivity v5 — Simplified
 *
 * Flow:
 *  - onStart(): bind ke service (service sudah berjalan dari onCreate pertama)
 *  - Tombol "Akhiri": stop service + tutup SEMUA activity (finishAffinity)
 *    IP tetap tersimpan → buka app lagi langsung ke kamera
 *  - Back button: moveTaskToBack (app minimize, service tetap jalan)
 *  - Saat ESP32 mati: service auto-reconnect (exponential backoff)
 *  - Saat ESP32 nyala lagi: service reconnect otomatis, kamera hidup kembali
 */
class CameraStreamActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IP = "esp32_ip"

        fun createIntent(context: Context, ipAddress: String): Intent =
            Intent(context, CameraStreamActivity::class.java).apply {
                putExtra(EXTRA_IP, ipAddress)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }

    private lateinit var binding:        ActivityCameraStreamBinding
    private lateinit var sessionManager: SessionManager

    private var streamService:   CameraStreamService? = null
    private var isBound          = false
    private var frameCollectJob: Job? = null
    private var stateCollectJob: Job? = null
    private var ipAddress:       String = ""

    // FPS counter
    private var frameCount     = 0
    private var fpsWindowStart = 0L

    // Swipe gesture untuk badge koneksi
    private var badgeSwipeRevealed = false

    // Guard: cegah double-execute akhiriProses
    private var isAkhiring = false

    private val exitReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CameraStreamService.ACTION_EXIT_APP) {
                android.util.Log.d("CameraStreamActivity", "Received exit broadcast from service, closing app")
                finishAffinity()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (isDestroyed || isFinishing) return
            val binder = service as? CameraStreamService.LocalBinder ?: return
            streamService = binder.getService()
            isBound       = true
            startCollectingFrames()
            startObservingConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Service crash/killed — bukan stop normal
            streamService = null
            isBound       = false
            runOnUiThread {
                if (!isDestroyed && !isFinishing && !isAkhiring) {
                    showStreamStateSafe(StreamState.ERROR("Koneksi service terputus. Tekan Reconnect."))
                    hideBadgeSafe()
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ipAddress = intent.getStringExtra(EXTRA_IP) ?: run {
            Toast.makeText(this, "IP address tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        sessionManager = SessionManager(this)

        supportActionBar?.title = "Live Camera — $ipAddress"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupBadgeSwipeGesture()
        setupClickListeners()
        showStreamStateSafe(StreamState.CONNECTING)

        requestNotificationPermission()
        requestBatteryOptimizationBypass()
    }

    override fun onStart() {
        super.onStart()
        // Daftarkan receiver untuk keluar aplikasi
        val filter = android.content.IntentFilter(CameraStreamService.ACTION_EXIT_APP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exitReceiver, filter)
        }

        if (ipAddress.isEmpty()) return
        val serviceIntent = CameraStreamService.createStartIntent(this, ipAddress)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(exitReceiver) }
        cancelAllJobs()
        if (isBound) {
            runCatching { unbindService(serviceConnection) }
            isBound       = false
            streamService = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val newIp = intent?.getStringExtra(EXTRA_IP)
        if (!newIp.isNullOrEmpty() && newIp != ipAddress) {
            ipAddress = newIp
            supportActionBar?.title = "Live Camera — $ipAddress"
            val si = CameraStreamService.createStartIntent(this, ipAddress)
            stopService(si); startService(si)
        }
    }

    // Back → minimize, service tetap jalan
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onSupportNavigateUp(): Boolean { moveTaskToBack(true); return true }

    // ──────────────────────────────────────────────────────────────────────────
    // Runtime permissions
    // ──────────────────────────────────────────────────────────────────────────

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* tidak masalah jika ditolak, streaming tetap jalan */ }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBatteryOptimizationBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Click listeners
    // ──────────────────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        // Tombol Reconnect (muncul saat error)
        binding.btnReconnect.setOnClickListener {
            if (isDestroyed || isFinishing) return@setOnClickListener
            showStreamStateSafe(StreamState.CONNECTING)
            hideBadgeSafe()
            val si = CameraStreamService.createStartIntent(this, ipAddress)
            stopService(si)
            startService(si)
            if (!isBound) bindService(si, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Tombol Akhiri di panel bawah
        binding.btnAkhiri.setOnClickListener {
            if (!isDestroyed && !isFinishing) konfirmasiAkhiriProses()
        }

        // Tombol Akhiri yang muncul saat badge di-swipe
        binding.btnAkhiriBadge.setOnClickListener {
            if (!isDestroyed && !isFinishing) konfirmasiAkhiriProses()
        }

        binding.ivCameraFrame.setOnClickListener { toggleFullscreen() }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Swipe gesture badge
    // ──────────────────────────────────────────────────────────────────────────

    @Suppress("ClickableViewAccessibility")
    private fun setupBadgeSwipeGesture() {
        val detector = GestureDetectorCompat(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    val diffX = e2.x - (e1?.x ?: e2.x)
                    return if (Math.abs(diffX) > 80f && Math.abs(velocityX) > 100f) {
                        badgeSwipeRevealed = !badgeSwipeRevealed
                        if (!isDestroyed && !isFinishing) {
                            binding.btnAkhiriBadge.visibility =
                                if (badgeSwipeRevealed) View.VISIBLE else View.GONE
                            binding.tvConnectedBadge.text =
                                if (badgeSwipeRevealed) "● Terhubung  ✕ tutup"
                                else "● Menerima data dari ESP32-S3  ‹ geser"
                        }
                        true
                    } else false
                }
                override fun onDown(e: MotionEvent): Boolean = true
            }
        )

        binding.badgeSwipeContainer.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
        binding.tvConnectedBadge.setOnTouchListener  { _, event -> detector.onTouchEvent(event) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Observer connectionState dari service
    // ──────────────────────────────────────────────────────────────────────────

    private fun startObservingConnectionState() {
        stateCollectJob?.cancel()
        stateCollectJob = lifecycleScope.launch {
            streamService?.connectionState?.collect { state ->
                if (isDestroyed || isFinishing || isAkhiring) return@collect
                when (state) {
                    CameraStreamService.ConnectionState.CONNECTED    -> showBadgeSafe()
                    CameraStreamService.ConnectionState.CONNECTING   -> {
                        hideBadgeSafe()
                        showStreamStateSafe(StreamState.CONNECTING)
                    }
                    CameraStreamService.ConnectionState.DISCONNECTED -> hideBadgeSafe()
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Frame collection
    // ──────────────────────────────────────────────────────────────────────────

    private fun startCollectingFrames() {
        frameCollectJob?.cancel()
        frameCount     = 0
        fpsWindowStart = System.currentTimeMillis()

        frameCollectJob = lifecycleScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                if (!isDestroyed && !isFinishing) showStreamStateSafe(StreamState.STREAMING)
            }

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable         = true
            }

            try {
                streamService?.frameFlow?.collect { jpegBytes ->
                    if (isDestroyed || isFinishing || isAkhiring) return@collect
                    val bitmap = runCatching {
                        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                    }.getOrNull() ?: return@collect

                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && !isAkhiring) {
                            binding.ivCameraFrame.setImageBitmap(bitmap)
                            updateFpsCounter(jpegBytes.size)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && !isFinishing && !isAkhiring)
                        showStreamStateSafe(StreamState.ERROR("Stream berakhir."))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && !isFinishing && !isAkhiring)
                        showStreamStateSafe(StreamState.ERROR("Error: ${e.message}"))
                }
            }
        }
    }

    private fun updateFpsCounter(frameBytes: Int) {
        if (isDestroyed || isFinishing) return
        frameCount++
        val now     = System.currentTimeMillis()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            runCatching {
                binding.tvStreamStatus.text =
                    "%.1f FPS  •  %d KB/frame".format(frameCount * 1000f / elapsed, frameBytes / 1024)
            }
            frameCount     = 0
            fpsWindowStart = now
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Akhiri Proses
    // ──────────────────────────────────────────────────────────────────────────

    private fun konfirmasiAkhiriProses() {
        if (isDestroyed || isFinishing || isAkhiring) return
        AlertDialog.Builder(this)
            .setTitle("Akhiri Proses")
            .setMessage("Yakin ingin menghentikan streaming dan menutup aplikasi?\n\nSaat dibuka kembali, aplikasi akan otomatis terhubung ke ESP32-S3.")
            .setPositiveButton("Akhiri") { _, _ -> akhiriProses() }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Akhiri proses:
     * 1. Hentikan service (WebSocket, notifikasi)
     * 2. IP TETAP tersimpan → buka app lagi langsung ke kamera
     * 3. finishAffinity() → tutup SEMUA activity (keluar dari app)
     *
     * Saat app dibuka lagi → MainActivity cek IP → langsung ke CameraStreamActivity.
     */
    private fun akhiriProses() {
        if (isAkhiring) return
        isAkhiring = true

        cancelAllJobs()

        // Unbind dulu
        if (isBound) {
            runCatching { unbindService(serviceConnection) }
            isBound       = false
            streamService = null
        }

        // Kirim ACTION_STOP ke service: stop WS, hapus notifikasi, stopSelf()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(CameraStreamService.createStopIntent(this))
            } else {
                startService(CameraStreamService.createStopIntent(this))
            }
        }

        // TIDAK hapus savedIp → buka app lagi otomatis ke kamera
        Toast.makeText(this, "Aplikasi dihentikan. Buka kembali untuk terhubung.", Toast.LENGTH_SHORT).show()

        // finishAffinity(): tutup SEMUA activity di stack (keluar app)
        finishAffinity()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun showBadgeSafe() {
        if (isDestroyed || isFinishing) return
        badgeSwipeRevealed = false
        runCatching {
            binding.btnAkhiriBadge.visibility   = View.GONE
            binding.tvConnectedBadge.text       = "● Menerima data dari ESP32-S3  ‹ geser"
            binding.tvConnectedBadge.visibility = View.VISIBLE
        }
    }

    private fun hideBadgeSafe() {
        if (isDestroyed || isFinishing) return
        badgeSwipeRevealed = false
        runCatching {
            binding.btnAkhiriBadge.visibility   = View.GONE
            binding.tvConnectedBadge.visibility = View.GONE
        }
    }

    private fun showStreamStateSafe(state: StreamState) {
        if (isDestroyed || isFinishing) return
        runCatching {
            when (state) {
                StreamState.CONNECTING -> {
                    binding.progressStream.visibility = View.VISIBLE
                    binding.tvStreamStatus.text       = "Menghubungkan ke kamera ESP32..."
                    binding.btnReconnect.visibility   = View.GONE
                    binding.tvError.visibility        = View.GONE
                }
                StreamState.STREAMING -> {
                    binding.progressStream.visibility = View.GONE
                    binding.tvError.visibility        = View.GONE
                    binding.btnReconnect.visibility   = View.GONE
                }
                is StreamState.ERROR -> {
                    binding.progressStream.visibility = View.GONE
                    binding.tvError.text              = state.message
                    binding.tvError.visibility        = View.VISIBLE
                    binding.btnReconnect.visibility   = View.VISIBLE
                    binding.tvStreamStatus.text       = "Offline — menunggu ESP32..."
                    hideBadgeSafe()
                }
            }
        }
    }

    private fun cancelAllJobs() {
        runCatching { frameCollectJob?.cancel() }; frameCollectJob = null
        runCatching { stateCollectJob?.cancel() }; stateCollectJob = null
    }

    private var isFullscreen = false
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) supportActionBar?.hide() else supportActionBar?.show()
    }

    private sealed class StreamState {
        object CONNECTING                      : StreamState()
        object STREAMING                       : StreamState()
        data class ERROR(val message: String) : StreamState()
    }
}
