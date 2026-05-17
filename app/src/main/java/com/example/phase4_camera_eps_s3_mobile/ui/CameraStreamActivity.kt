package com.example.phase4_camera_eps_s3_mobile.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.phase4_camera_eps_s3_mobile.camera.CameraManager
import com.example.phase4_camera_eps_s3_mobile.databinding.ActivityCameraStreamBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CameraStreamActivity — menampilkan live MJPEG stream dari ESP32-S3 OV2640
 *
 * Flow:
 *   1. Terima IP ESP32 dari intent (dikirim oleh DeviceConfigActivity)
 *   2. CameraManager.streamFrames() menghasilkan ByteArray JPEG via Flow
 *   3. Setiap frame di-decode ke Bitmap dan ditampilkan di ImageView
 *   4. FPS dihitung dan ditampilkan sebagai indikator kualitas stream
 *
 * Thread model:
 *   - Network I/O : Dispatchers.IO (di dalam CameraManager)
 *   - UI update   : Main thread via lifecycleScope
 */
class CameraStreamActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IP = "esp32_ip"

        /** Buat intent dengan IP yang sudah divalidasi */
        fun createIntent(context: Context, ipAddress: String): Intent {
            return Intent(context, CameraStreamActivity::class.java).apply {
                putExtra(EXTRA_IP, ipAddress)
            }
        }
    }

    private lateinit var binding: ActivityCameraStreamBinding
    private lateinit var cameraManager: CameraManager
    private var streamJob: Job? = null

    // FPS counter
    private var frameCount = 0
    private var fpsWindowStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Layar tetap menyala selama streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ipAddress = intent.getStringExtra(EXTRA_IP) ?: run {
            android.widget.Toast.makeText(this, "IP address tidak ditemukan", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        supportActionBar?.title = "Live Camera — $ipAddress"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        cameraManager = CameraManager()

        setupClickListeners(ipAddress)
        startStream(ipAddress)
    }

    private fun setupClickListeners(ipAddress: String) {
        // Tombol reconnect jika stream terputus
        binding.btnReconnect.setOnClickListener {
            binding.btnReconnect.visibility = View.GONE
            binding.tvStreamStatus.text = "Reconnecting..."
            startStream(ipAddress)
        }

        // Fullscreen toggle
        binding.ivCameraFrame.setOnClickListener {
            toggleFullscreen()
        }
    }

    private fun startStream(ipAddress: String) {
        streamJob?.cancel()

        showStreamState(StreamState.CONNECTING)
        frameCount = 0
        fpsWindowStart = System.currentTimeMillis()

        streamJob = lifecycleScope.launch {
            // Cek reachability dengan RETRY — ESP32 butuh ~2-3 detik untuk
            // memulai WebSocket server setelah BLE shutdown selesai.
            // Tanpa retry: check langsung gagal → ERROR padahal server belum siap.
            showStreamState(StreamState.CHECKING)

            val maxRetries = 8
            val retryDelayMs = 1500L
            var reachable = false

            for (attempt in 1..maxRetries) {
                android.util.Log.d("CameraStream", "Reachability check attempt $attempt/$maxRetries")
                showStreamState(StreamState.CHECKING_RETRY(attempt, maxRetries))

                reachable = cameraManager.isStreamReachable(ipAddress)
                if (reachable) break

                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(retryDelayMs)
                }
            }

            if (!reachable) {
                showStreamState(StreamState.ERROR(
                    "Tidak dapat terhubung ke $ipAddress\n" +
                    "Pastikan ESP32 sudah terhubung ke WiFi yang sama\n" +
                    "dan WebSocket server sudah berjalan."
                ))
                return@launch
            }

            showStreamState(StreamState.STREAMING)

            try {
                cameraManager.streamFrames(ipAddress).collect { jpegBytes ->
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                        ?: return@collect

                    binding.ivCameraFrame.setImageBitmap(bitmap)
                    updateFpsCounter(jpegBytes.size)
                }
            } catch (e: Exception) {
                showStreamState(StreamState.ERROR("Stream terputus: ${e.message}"))
            }

            // Flow selesai tanpa exception = ESP32 menutup koneksi
            showStreamState(StreamState.ERROR("Stream berakhir. Tekan Reconnect untuk memulai ulang."))
        }
    }

    private fun updateFpsCounter(frameBytes: Int) {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - fpsWindowStart

        // Update FPS setiap 1 detik
        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            val kb  = frameBytes / 1024
            binding.tvStreamStatus.text = "%.1f FPS  •  %d KB/frame".format(fps, kb)
            frameCount = 0
            fpsWindowStart = now
        }
    }

    private fun showStreamState(state: StreamState) {
        when (state) {
            StreamState.CHECKING -> {
                binding.progressStream.visibility = View.VISIBLE
                binding.tvStreamStatus.text = "Memeriksa koneksi..."
                binding.btnReconnect.visibility = View.GONE
                binding.tvError.visibility = View.GONE
            }
            is StreamState.CHECKING_RETRY -> {
                binding.progressStream.visibility = View.VISIBLE
                binding.tvStreamStatus.text = "Menunggu server ESP32... (${state.attempt}/${state.max})"
                binding.btnReconnect.visibility = View.GONE
                binding.tvError.visibility = View.GONE
            }
            StreamState.CONNECTING -> {
                binding.progressStream.visibility = View.VISIBLE
                binding.tvStreamStatus.text = "Menghubungkan ke kamera..."
                binding.btnReconnect.visibility = View.GONE
                binding.tvError.visibility = View.GONE
            }
            StreamState.STREAMING -> {
                binding.progressStream.visibility = View.GONE
                binding.tvError.visibility = View.GONE
                binding.btnReconnect.visibility = View.GONE
            }
            is StreamState.ERROR -> {
                binding.progressStream.visibility = View.GONE
                binding.tvError.text = state.message
                binding.tvError.visibility = View.VISIBLE
                binding.btnReconnect.visibility = View.VISIBLE
                binding.tvStreamStatus.text = "Offline"
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

    override fun onSupportNavigateUp(): Boolean {
        @Suppress("DEPRECATION")
        onBackPressed()
        return true
    }

    override fun onStop() {
        super.onStop()
        // Hentikan stream saat activity tidak terlihat — hemat baterai & bandwidth
        streamJob?.cancel()
        streamJob = null
    }

    override fun onStart() {
        super.onStart()
        // Resume stream saat activity kembali terlihat
        val ipAddress = intent.getStringExtra(EXTRA_IP) ?: return
        if (streamJob == null || streamJob?.isActive == false) {
            // Delay singkat agar lifecycle fully resumed sebelum network call
            lifecycleScope.launch {
                delay(300)
                startStream(ipAddress)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        streamJob?.cancel()
    }

    // State sealed class untuk UI — menghindari boolean flag yang tersebar
    private sealed class StreamState {
        object CHECKING   : StreamState()
        data class CHECKING_RETRY(val attempt: Int, val max: Int) : StreamState()
        object CONNECTING : StreamState()
        object STREAMING  : StreamState()
        data class ERROR(val message: String) : StreamState()
    }
}
