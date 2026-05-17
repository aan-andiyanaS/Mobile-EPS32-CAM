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
import com.example.phase4_camera_eps_s3_mobile.model.ImuData
import com.example.phase4_camera_eps_s3_mobile.model.TofGrid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CameraStreamActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IP = "esp32_ip"
        fun createIntent(context: Context, ip: String): Intent =
            Intent(context, CameraStreamActivity::class.java).putExtra(EXTRA_IP, ip)
    }

    private lateinit var binding: ActivityCameraStreamBinding
    private lateinit var cameraManager: CameraManager
    private var streamJob: Job? = null

    private var frameCount = 0
    private var fpsWindowStart = 0L
    private val sensorAlerts = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ip = intent.getStringExtra(EXTRA_IP) ?: run {
            android.widget.Toast.makeText(this, "IP tidak ditemukan", android.widget.Toast.LENGTH_SHORT).show()
            finish(); return
        }

        supportActionBar?.title = "Live — $ip"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        cameraManager = CameraManager()

        binding.btnReconnect.setOnClickListener {
            binding.btnReconnect.visibility = View.GONE
            startStream(ip)
        }
        binding.ivCameraFrame.setOnClickListener {
            val v = binding.layoutSensorData
            v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        observeSensorFlows()
        startStream(ip)
    }

    // ── Observe IMU, ToF, Alert flows ────────────────────────────────────────
    private fun observeSensorFlows() {
        // IMU
        lifecycleScope.launch {
            cameraManager.imuFlow.collect { imu -> updateImuUI(imu) }
        }
        // ToF
        lifecycleScope.launch {
            cameraManager.tofFlow.collect { tof -> updateTofUI(tof) }
        }
        // Alert (sensor missing)
        lifecycleScope.launch {
            cameraManager.alertFlow.collect { alert -> addSensorAlert(alert) }
        }
    }

    private fun updateImuUI(imu: ImuData) {
        binding.layoutSensorData.visibility = View.VISIBLE
        binding.tvPitch.text   = "Pitch: %+6.1f°".format(imu.pitch)
        binding.tvRoll.text    = "Roll : %+6.1f°".format(imu.roll)
        binding.tvYawRate.text = "ω z  : %+5.3f rad/s".format(imu.yawRate)
        binding.tvALin.text    = "Alin : %5.3f m/s²".format(imu.aLinMag)
    }

    private fun updateTofUI(tof: TofGrid) {
        binding.layoutSensorData.visibility = View.VISIBLE
        val minDist = tof.minDistance()
        binding.tvTofMin.text = if (minDist == -1) "Min : -- mm" else "Min : $minDist mm"

        // Tampilkan grid 8x8 sebagai teks (zona terdekat = *)
        val sb = StringBuilder()
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val d = tof.distances[row * 8 + col].toInt() and 0xFFFF
                sb.append(when {
                    d == 0xFFFF -> "  -- "
                    d < 500     -> " <!> "   // < 50cm: bahaya
                    d < 1000    -> " [${(d/100)}] "  // 50–100cm
                    else        -> " ... "
                })
            }
            sb.append("\n")
        }
        binding.tvTofGrid.text = sb.toString()
    }

    private fun addSensorAlert(msg: String) {
        // msg contoh: "SENSOR_MISSING:MPU6050"
        val label = when {
            msg.contains("CAMERA")   -> "⚠ Kamera tidak terdeteksi"
            msg.contains("MPU6050")  -> "⚠ MPU6050 tidak terdeteksi (SDA/SCL?)"
            msg.contains("VL53L5CX")-> "⚠ VL53L5CX tidak terdeteksi (SDA/SCL?)"
            else -> "⚠ $msg"
        }
        if (!sensorAlerts.contains(label)) {
            sensorAlerts.add(label)
            binding.layoutAlerts.visibility = View.VISIBLE
            binding.tvAlertMessage.text = sensorAlerts.joinToString("\n")
            android.util.Log.w("CameraStream", "Sensor alert: $label")
        }
    }

    // ── Camera Stream ─────────────────────────────────────────────────────────
    private fun startStream(ip: String) {
        streamJob?.cancel()
        showState(StreamState.CONNECTING)
        frameCount = 0; fpsWindowStart = System.currentTimeMillis()

        streamJob = lifecycleScope.launch {
            showState(StreamState.CHECKING)
            val maxRetry = 8; var reachable = false
            for (i in 1..maxRetry) {
                showState(StreamState.CHECKING_RETRY(i, maxRetry))
                reachable = cameraManager.isStreamReachable(ip)
                if (reachable) break
                if (i < maxRetry) delay(1500)
            }
            if (!reachable) {
                showState(StreamState.ERROR("Tidak dapat terhubung ke $ip\nPastikan ESP32 di jaringan WiFi yang sama."))
                return@launch
            }

            showState(StreamState.STREAMING)
            try {
                cameraManager.streamFrames(ip).collect { jpeg ->
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return@collect
                    binding.ivCameraFrame.setImageBitmap(bmp)
                    updateFps(jpeg.size)
                }
            } catch (e: Exception) {
                showState(StreamState.ERROR("Stream terputus: ${e.message}"))
                return@launch
            }
            showState(StreamState.ERROR("Stream berakhir. Tekan Reconnect."))
        }
    }

    private fun updateFps(frameBytes: Int) {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            binding.tvStreamStatus.text = "%.1f FPS  •  %d KB".format(fps, frameBytes / 1024)
            frameCount = 0; fpsWindowStart = now
        }
    }

    private fun showState(state: StreamState) {
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

    private sealed class StreamState {
        object CHECKING   : StreamState()
        data class CHECKING_RETRY(val attempt: Int, val max: Int) : StreamState()
        object CONNECTING : StreamState()
        object STREAMING  : StreamState()
        data class ERROR(val message: String) : StreamState()
    }

    override fun onSupportNavigateUp(): Boolean { @Suppress("DEPRECATION") onBackPressed(); return true }
    override fun onStop()    { super.onStop();    streamJob?.cancel(); streamJob = null }
    override fun onStart()   {
        super.onStart()
        val ip = intent.getStringExtra(EXTRA_IP) ?: return
        if (streamJob == null || streamJob?.isActive == false) {
            lifecycleScope.launch { delay(300); startStream(ip) }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        streamJob?.cancel()
    }
}
