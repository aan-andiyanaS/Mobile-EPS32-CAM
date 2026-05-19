package com.example.phase4_camera_eps_s3_mobile.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.phase4_camera_eps_s3_mobile.R
import com.example.phase4_camera_eps_s3_mobile.ble.BleManager
import com.example.phase4_camera_eps_s3_mobile.databinding.ActivityDeviceConfigBinding
import com.example.phase4_camera_eps_s3_mobile.databinding.DialogWifiPasswordBinding
import com.example.phase4_camera_eps_s3_mobile.databinding.ItemWifiBinding
import com.example.phase4_camera_eps_s3_mobile.model.WifiInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.phase4_camera_eps_s3_mobile.util.SessionManager

/**
 * DeviceConfigActivity - WiFi Configuration screen
 * Connect ke ESP32, scan WiFi, dan kirim credentials
 */
@SuppressLint("MissingPermission")
class DeviceConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceConfigBinding
    private lateinit var bleManager: BleManager
    private lateinit var wifiAdapter: WifiAdapter

    private var deviceAddress: String = ""
    private val wifiList = mutableListOf<WifiInfo>()

    // IP ESP32 yang didapat setelah koneksi WiFi berhasil
    // Dikirim via intent dari MainActivity atau diparse dari response ESP32
    private var esp32IpAddress: String = ""
    private lateinit var sessionManager: SessionManager

    // Dialog untuk input password
    private var passwordDialog: AlertDialog? = null
    private var dialogBinding: DialogWifiPasswordBinding? = null
    private var isConnecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val deviceName = intent.getStringExtra("device_name") ?: "Unknown"
        deviceAddress  = intent.getStringExtra("device_address") ?: ""
        // IP bisa dikirim dari MainActivity jika ESP32 sudah pernah terhubung
        esp32IpAddress = intent.getStringExtra("esp32_ip") ?: ""

        supportActionBar?.title = deviceName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        sessionManager = SessionManager(this)

        bleManager = BleManager(this)
        setupRecyclerView()
        setupClickListeners()
        observeState()
        connectToDevice()

        // Tampilkan tombol kamera jika IP sudah diketahui sebelum BLE provisioning
        updateCameraButtonVisibility()
    }

    private fun setupRecyclerView() {
        wifiAdapter = WifiAdapter { wifiInfo ->
            showPasswordDialog(wifiInfo)
        }

        binding.recyclerWifi.apply {
            layoutManager = LinearLayoutManager(this@DeviceConfigActivity)
            adapter = wifiAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnScanWifi.setOnClickListener {
            scanWifi()
        }

        // Tombol buka live camera stream — hanya aktif jika IP sudah diketahui
        binding.btnViewCamera.setOnClickListener {
            if (esp32IpAddress.isNotEmpty()) {
                startActivity(CameraStreamActivity.createIntent(this, esp32IpAddress))
            } else {
                Toast.makeText(this, "IP ESP32 belum diketahui. Lakukan koneksi WiFi terlebih dahulu.", Toast.LENGTH_LONG).show()
            }
        }
    }

private fun observeState() {
    lifecycleScope.launch {
        bleManager.connectionState.collectLatest { state ->
            updateConnectionUI(state)
        }
    }

    // FIX: Karena bleScope sekarang Dispatchers.IO, emit dari BleManager
    // terjadi di IO thread. collectLatest/collect di lifecycleScope (Main)
    // sudah otomatis switch ke Main — tidak perlu runOnUiThread manual.
    // Ganti collect → collectLatest TIDAK boleh — karena data WiFi harus
    // semua diterima (BATCH 1, BATCH 2, dst). Tetap pakai collect.
    lifecycleScope.launch(Dispatchers.Main.immediate) {
        bleManager.receivedData.collect { data ->
            processReceivedData(data)
        }
    }
}

    private fun updateConnectionUI(state: BleManager.ConnectionState) {
        when (state) {
            BleManager.ConnectionState.DISCONNECTED -> {
                binding.tvStatus.text = "Disconnected"
                binding.progressConnection.visibility = View.GONE
                binding.btnScanWifi.isEnabled = false
            }
            BleManager.ConnectionState.CONNECTING -> {
                binding.tvStatus.text = "Connecting..."
                binding.progressConnection.visibility = View.VISIBLE
                binding.btnScanWifi.isEnabled = false
            }
            BleManager.ConnectionState.CONNECTED,
            BleManager.ConnectionState.DISCOVERING_SERVICES -> {
                binding.tvStatus.text = "Discovering services..."
                binding.progressConnection.visibility = View.VISIBLE
                binding.btnScanWifi.isEnabled = false
            }
            BleManager.ConnectionState.READY -> {
                binding.tvStatus.text = "Connected ✓"
                binding.progressConnection.visibility = View.GONE
                binding.btnScanWifi.isEnabled = true
            }
        }
    }

    private fun processReceivedData(data: String) {
        android.util.Log.d("DeviceConfig", "Processing: $data")

        when {
            data.startsWith("STATUS:") -> {
                val status = data.removePrefix("STATUS:")
                binding.tvScanStatus.text = status
                binding.progressWifi.visibility = if (status == "Done") View.GONE else View.VISIBLE

                if (status == "Scanning...") {
                    wifiList.clear()
                    wifiAdapter.submitList(emptyList())
                    android.util.Log.d("DeviceConfig", "WiFi list cleared for new scan")
                }

                if (status == "Done") {
                    binding.tvScanStatus.text = "Done - ${wifiList.size} networks"
                }
            }
            data.startsWith("COUNT:") -> {
                val count = data.removePrefix("COUNT:").toIntOrNull() ?: 0
                binding.tvScanStatus.text = "Found $count networks, receiving..."
                android.util.Log.d("DeviceConfig", "Expected count: $count")
            }
            data.startsWith("BATCH:") -> {
                val batchContent = data.removePrefix("BATCH:")
                val wifiEntries  = batchContent.split(";")
                android.util.Log.d("DeviceConfig", "Received BATCH with ${wifiEntries.size} entries")

                for (entry in wifiEntries) {
                    if (entry.isBlank()) continue
                    val parts = entry.split("|")
                    if (parts.size == 4) {
                        try {
                            val encFull = when (parts[3].trim()) {
                                "O" -> "Open"
                                "S" -> "Secured"
                                else -> parts[3]
                            }
                            val wifi = WifiInfo(
                                index      = parts[0].toInt(),
                                ssid       = parts[1],
                                rssi       = parts[2].toInt(),
                                encryption = encFull
                            )
                            wifiList.add(wifi)
                        } catch (e: Exception) {
                            android.util.Log.e("DeviceConfig", "Parse error: $entry - ${e.message}")
                        }
                    }
                }
                wifiAdapter.submitList(wifiList.toList())
                android.util.Log.d("DeviceConfig", "WiFi list updated, total: ${wifiList.size}")
            }
            data.startsWith("CONNECT:") -> {
                handleConnectResponse(data.removePrefix("CONNECT:"))
            }
            // Format: IP:192.168.1.100 — dikirim ESP32 setelah terhubung WiFi
            data.startsWith("IP:") -> {
                val ip = data.removePrefix("IP:").trim()
                if (ip.isNotEmpty()) {
                    esp32IpAddress = ip
                    android.util.Log.d("DeviceConfig", "ESP32 IP received: $esp32IpAddress")
                    // Simpan IP ke SharedPreferences agar saat app dibuka ulang
                    // tidak perlu scan BLE lagi
                    sessionManager.saveEsp32Ip(ip)
                    // Simpan juga MAC address untuk BLE auto-reconnect di masa depan
                    if (deviceAddress.isNotEmpty()) {
                        sessionManager.saveLastDeviceMac(deviceAddress)
                    }
                    updateCameraButtonVisibility()
                }
            }
            data.startsWith("BLE:") -> {
                val bleStatus = data.removePrefix("BLE:")
                if (bleStatus == "DISCONNECT") {
                    android.util.Log.d("DeviceConfig", "ESP32 notifying BLE will disconnect")
                    runOnUiThread {
                        passwordDialog?.dismiss()

                        // Update status UI — JANGAN panggil finish() di sini!
                        // Jika finish() dipanggil, DeviceConfigActivity hilang dari back stack
                        // sehingga ketika user kembali dari CameraStreamActivity
                        // langsung ke MainActivity (bukan DeviceConfigActivity)
                        binding.tvStatus.text = "ESP32 ready ✓"
                        binding.tvScanStatus.text = "WiFi connected! Camera server starting..."
                        binding.progressWifi.visibility = View.GONE
                        binding.btnScanWifi.isEnabled = false

                        Toast.makeText(
                            this,
                            "✓ WiFi terhubung! Tekan 'View Camera' untuk streaming.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * Tampilkan atau sembunyikan tombol "View Camera" berdasarkan ketersediaan IP
     */
    private fun updateCameraButtonVisibility() {
        binding.btnViewCamera.visibility =
            if (esp32IpAddress.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Tampilkan dialog untuk input password WiFi
     */
    private fun showPasswordDialog(wifiInfo: WifiInfo) {
        dialogBinding = DialogWifiPasswordBinding.inflate(layoutInflater)
        val dialogView = dialogBinding!!

        dialogView.tvWifiName.text = wifiInfo.ssid
        dialogView.tvWifiInfo.text = "Signal: ${wifiInfo.rssi} dBm • ${wifiInfo.encryption}"

        dialogView.layoutStatus.visibility = View.GONE
        dialogView.etPassword.text?.clear()
        dialogView.etPassword.isEnabled = true
        isConnecting = false

        passwordDialog = AlertDialog.Builder(this)
            .setTitle("Connect to WiFi")
            .setView(dialogView.root)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()

        passwordDialog?.setOnShowListener { dialog ->
            val positiveButton = (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val password = dialogView.etPassword.text.toString()

                if (password.length < 8 && wifiInfo.encryption != "Open") {
                    dialogView.tilPassword.error = "Password minimal 8 karakter"
                    return@setOnClickListener
                }

                connectToWifi(wifiInfo.ssid, password)
            }
        }

        passwordDialog?.show()
    }

    /**
     * Kirim credentials ke ESP32 dan tampilkan status
     */
    private fun connectToWifi(ssid: String, password: String) {
        if (isConnecting) return
        isConnecting = true

        dialogBinding?.let { b ->
            b.etPassword.isEnabled = false
            b.layoutStatus.visibility = View.VISIBLE
            b.tvStatus.text = "Connecting to $ssid..."
            b.progressConnect.visibility = View.VISIBLE
        }

        val success = bleManager.connectWifi(ssid, password)

        if (!success) {
            dialogBinding?.let { b ->
                b.tvStatus.text = "Failed to send command"
                b.progressConnect.visibility = View.GONE
                b.etPassword.isEnabled = true
            }
            isConnecting = false
        }
    }

    /**
     * Handle response dari ESP32 (CONNECT:SUCCESS atau CONNECT:FAILED:reason)
     * Setelah SUCCESS, tampilkan opsi untuk membuka live camera stream
     */
    private fun handleConnectResponse(response: String) {
        android.util.Log.d("DeviceConfig", "Connect response: $response")

        runOnUiThread {
            dialogBinding?.let { b ->
                b.progressConnect.visibility = View.GONE

                when {
                    response == "SUCCESS" -> {
                        b.tvStatus.text = "✓ Connected successfully!"
                        b.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))

                        b.root.postDelayed({
                            passwordDialog?.dismiss()
                            Toast.makeText(this, "WiFi connected!", Toast.LENGTH_SHORT).show()

                            // Tampilkan tombol kamera dengan animasi fade-in
                            // IP mungkin belum tersedia jika ESP32 belum kirim "IP:" notif
                            // Tombol tetap tampil — user bisa input manual jika perlu
                            if (esp32IpAddress.isNotEmpty()) {
                                updateCameraButtonVisibility()
                                Toast.makeText(
                                    this,
                                    "Tekan 'View Camera' untuk melihat live stream",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }, 1500)
                    }
                    response.startsWith("FAILED:") -> {
                        val reason = response.removePrefix("FAILED:")
                        b.tvStatus.text = "✗ Failed: $reason"
                        b.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                        b.etPassword.isEnabled = true
                        isConnecting = false
                    }
                    else -> {
                        b.tvStatus.text = response
                    }
                }
            }
        }
    }

    private fun connectToDevice() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter          = bluetoothManager.adapter
        val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)
        bleManager.connect(device)
    }

    private fun scanWifi() {
        wifiList.clear()
        wifiAdapter.submitList(emptyList())
        binding.progressWifi.visibility = View.VISIBLE
        binding.tvScanStatus.text = "Scanning..."

        val success = bleManager.scanWifi()
        if (!success) {
            Toast.makeText(this, "Failed to send scan command", Toast.LENGTH_SHORT).show()
            binding.progressWifi.visibility = View.GONE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        @Suppress("DEPRECATION")
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        passwordDialog?.dismiss()
        bleManager.close()
    }

    /**
     * Adapter for WiFi list
     */
    inner class WifiAdapter(
        private val onClick: (WifiInfo) -> Unit
    ) : RecyclerView.Adapter<WifiAdapter.ViewHolder>() {

        private var items: List<WifiInfo> = emptyList()

        fun submitList(newItems: List<WifiInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemWifiBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(
            private val binding: ItemWifiBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(wifi: WifiInfo) {
                binding.tvSsid.text       = wifi.ssid
                binding.tvRssi.text       = "${wifi.rssi} dBm"
                binding.tvEncryption.text = wifi.encryption

                val iconRes = when (wifi.getSignalStrength()) {
                    WifiInfo.SignalStrength.EXCELLENT -> R.drawable.ic_wifi_4
                    WifiInfo.SignalStrength.GOOD      -> R.drawable.ic_wifi_3
                    WifiInfo.SignalStrength.FAIR      -> R.drawable.ic_wifi_2
                    WifiInfo.SignalStrength.WEAK      -> R.drawable.ic_wifi_1
                }
                binding.ivSignal.setImageResource(iconRes)

                binding.root.setOnClickListener { onClick(wifi) }
            }
        }
    }
}
