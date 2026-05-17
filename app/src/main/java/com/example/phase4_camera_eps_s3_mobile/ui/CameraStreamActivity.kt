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
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.phase4_camera_eps_s3_mobile.databinding.ActivityCameraStreamBinding
import com.example.phase4_camera_eps_s3_mobile.service.CameraStreamService
import com.example.phase4_camera_eps_s3_mobile.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraStreamActivity — menampilkan frame kamera dari WebSocket.
 * Koneksi WebSocket dikelola oleh CameraStreamService (Foreground Service).
 *
 * Fitur:
 *  - onBackPressed     → moveTaskToBack(true): app minimize, Service TETAP jalan.
 *  - singleTop         → notifikasi ditekan saat Activity sudah ada di top → onNewIntent().
 *  - Badge koneksi     → muncul di pojok kanan atas saat WebSocket berhasil terbuka.
 *  - Notifikasi sistem → update otomatis sesuai state: CONNECTING / CONNECTED / DISCONNECTED.
 *
 * Auto-redirect ke halaman ini:
 *  - Saat app dibuka ulang dari launcher (bukan swipe dari recent), MainActivity
 *    membaca IP dari SessionManager dan langsung startActivity ke sini.
 *  - Saat app di-minimize (moveTaskToBack), launcher membawa task yang sudah ada
 *    ke foreground (CameraStreamActivity tetap di atas stack).
 */
class CameraStreamActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IP = "esp32_ip"

        fun createIntent(context: Context, ipAddress: String): Intent {
            // FLAG_ACTIVITY_SINGLE_TOP mencegah instance baru jika activity sudah ada di top
            return Intent(context, CameraStreamActivity::class.java).apply {
                putExtra(EXTRA_IP, ipAddress)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraStreamService.LocalBinder
            streamService = binder.getService()
            isBound       = true
            startCollectingFrames()
            startObservingConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamService = null
            isBound       = false
            showStreamState(StreamState.ERROR("Koneksi ke service terputus. Tekan Reconnect."))
            hideBadge()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Layar tetap menyala selama streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ipAddress = intent.getStringExtra(EXTRA_IP) ?: run {
            Toast.makeText(this, "IP address tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        sessionManager = SessionManager(this)

        supportActionBar?.title = "Live Camera — $ipAddress"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupClickListeners()
        showStreamState(StreamState.CONNECTING)

        // Request izin notifikasi (Android 13+) agar heads-up notification muncul
        requestNotificationPermission()
        // Minta user kecualikan app dari battery optimization (penting untuk sistem safety)
        requestBatteryOptimizationBypass()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Runtime permissions
    // ─────────────────────────────────────────────────────────────────────────

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Izin notifikasi ditolak — heads-up alert tidak akan muncul",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Minta user mengecualikan app dari battery optimization.
     * Tanpa ini, Android Doze mode bisa membatasi akses network saat layar mati,
     * yang dapat memutus koneksi WebSocket ke ESP32-S3 secara tiba-tiba.
     */
    private fun requestBatteryOptimizationBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    // Beberapa device tidak mendukung intent ini — abaikan
                }
            }
        }
    }

    /**
     * Dipanggil saat notifikasi ditekan dan activity sudah ada di back stack (singleTop).
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val newIp = intent?.getStringExtra(EXTRA_IP)
        if (!newIp.isNullOrEmpty() && newIp != ipAddress) {
            ipAddress = newIp
            supportActionBar?.title = "Live Camera — $ipAddress"
            // Restart service dengan IP baru
            val serviceIntent = CameraStreamService.createStartIntent(this, ipAddress)
            stopService(serviceIntent)
            startService(serviceIntent)
        }
    }

    override fun onStart() {
        super.onStart()
        val serviceIntent = CameraStreamService.createStartIntent(this, ipAddress)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Unbind TANPA stop service — service tetap jalan di background
        frameCollectJob?.cancel()
        frameCollectJob = null
        stateCollectJob?.cancel()
        stateCollectJob = null
        if (isBound) {
            unbindService(serviceConnection)
            isBound       = false
            streamService = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Tidak stop service di onDestroy — service tetap hidup untuk background streaming
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Back button: minimize app, BUKAN finish Activity
    // Service tetap hidup → WebSocket tetap terkoneksi ke ESP32-S3
    // ──────────────────────────────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        moveTaskToBack(true)
        return true
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Click listeners
    // ──────────────────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnReconnect.setOnClickListener {
            binding.btnReconnect.visibility = View.GONE
            showStreamState(StreamState.CONNECTING)
            hideBadge()
            val serviceIntent = CameraStreamService.createStartIntent(this, ipAddress)
            stopService(serviceIntent)
            startService(serviceIntent)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        binding.ivCameraFrame.setOnClickListener {
            toggleFullscreen()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Connection state observer — kontrol badge + StreamState UI
    // ──────────────────────────────────────────────────────────────────────────

    private fun startObservingConnectionState() {
        stateCollectJob?.cancel()
        stateCollectJob = lifecycleScope.launch {
            streamService?.connectionState?.collect { state ->
                when (state) {
                    CameraStreamService.ConnectionState.CONNECTED -> {
                        showBadge()
                    }
                    CameraStreamService.ConnectionState.CONNECTING -> {
                        hideBadge()
                        showStreamState(StreamState.CONNECTING)
                    }
                    CameraStreamService.ConnectionState.DISCONNECTED -> {
                        hideBadge()
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Frame collection dari Service
    // ──────────────────────────────────────────────────────────────────────────

    private fun startCollectingFrames() {
        frameCollectJob?.cancel()
        frameCount     = 0
        fpsWindowStart = System.currentTimeMillis()

        // FIX LATENSI KRITIS: Jalankan di Dispatchers.Default (thread pool CPU)
        // BUKAN di Dispatchers.Main. Sebelumnya decode JPEG (10-30ms) memblokir
        // UI thread sehingga frame-frame berikutnya tertunda dan menyebabkan lag.
        frameCollectJob = lifecycleScope.launch(Dispatchers.Default) {
            // Update UI dari thread lain harus via withContext(Main)
            withContext(Dispatchers.Main) { showStreamState(StreamState.STREAMING) }

            // Reuse Options di luar loop — hindari alokasi objek per frame
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565  // 50% hemat memori vs ARGB_8888
                inMutable         = true                   // Enable bitmap reuse di masa depan
            }

            try {
                streamService?.frameFlow?.collect { jpegBytes ->
                    // Decode JPEG di background thread (Default dispatcher)
                    val bitmap = BitmapFactory.decodeByteArray(
                        jpegBytes, 0, jpegBytes.size, options
                    ) ?: return@collect

                    // Render HANYA di Main thread — minimal, sesegera mungkin
                    withContext(Dispatchers.Main) {
                        binding.ivCameraFrame.setImageBitmap(bitmap)
                        updateFpsCounter(jpegBytes.size)
                    }
                }
                withContext(Dispatchers.Main) {
                    showStreamState(StreamState.ERROR("Stream berakhir. Tekan Reconnect."))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showStreamState(StreamState.ERROR("Error: ${e.message}"))
                }
            }
        }
    }

    private fun updateFpsCounter(frameBytes: Int) {
        frameCount++
        val now     = System.currentTimeMillis()
        val elapsed = now - fpsWindowStart

        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            val kb  = frameBytes / 1024
            binding.tvStreamStatus.text = "%.1f FPS  •  %d KB/frame".format(fps, kb)
            frameCount     = 0
            fpsWindowStart = now
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Session reset
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Hapus sesi tersimpan dan kembali ke MainActivity (scan BLE).
     * Gunakan ini hanya jika ESP32 perlu dikonfigurasi ulang.
     */
    @Suppress("unused")
    private fun clearSessionAndGoHome() {
        sessionManager.clearSession()
        stopService(Intent(this, CameraStreamService::class.java))
        startActivity(
            Intent(this, com.example.phase4_camera_eps_s3_mobile.MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        )
        finish()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Badge helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun showBadge() {
        binding.tvConnectedBadge.visibility = View.VISIBLE
    }

    private fun hideBadge() {
        binding.tvConnectedBadge.visibility = View.GONE
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Stream state UI
    // ──────────────────────────────────────────────────────────────────────────

    private fun showStreamState(state: StreamState) {
        when (state) {
            StreamState.CONNECTING -> {
                binding.progressStream.visibility = View.VISIBLE
                binding.tvStreamStatus.text       = "Menghubungkan ke kamera..."
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
                binding.tvStreamStatus.text       = "Offline"
                hideBadge()
            }
        }
    }

    private var isFullscreen = false
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        supportActionBar?.let {
            if (isFullscreen) it.hide() else it.show()
        }
    }

    private sealed class StreamState {
        object CONNECTING                      : StreamState()
        object STREAMING                       : StreamState()
        data class ERROR(val message: String) : StreamState()
    }
}
