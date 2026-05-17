package com.example.phase4_camera_eps_s3_mobile

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.phase4_camera_eps_s3_mobile.ble.BleManager
import com.example.phase4_camera_eps_s3_mobile.databinding.ActivityMainBinding
import com.example.phase4_camera_eps_s3_mobile.databinding.ItemDeviceBinding
import com.example.phase4_camera_eps_s3_mobile.ui.DeviceConfigActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.phase4_camera_eps_s3_mobile.ui.CameraStreamActivity
import com.example.phase4_camera_eps_s3_mobile.util.SessionManager

/**
 * MainActivity - BLE Scanner screen
 * Menampilkan list ESP32 devices yang ditemukan
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var sessionManager: SessionManager

    // Permission launcher: dipanggil setelah user menjawab dialog permission
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            // Permission baru saja di-grant — sekarang cek apakah BT perlu di-enable
            if (!bleManager.isBluetoothEnabled()) {
                @Suppress("DEPRECATION")
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                startScan()
            }
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    // Bluetooth enable launcher
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkPermissionsAndScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        // Jika ESP32 sudah pernah dikonfigurasi dan IP tersimpan,
        // langsung buka CameraStreamActivity — bypass scan BLE.
        val savedIp = sessionManager.getSavedEsp32Ip()
        if (savedIp != null) {
            startActivity(CameraStreamActivity.createIntent(this, savedIp))
            finish() // Tutup MainActivity agar user tidak bisa back ke sini
            return
        }

        bleManager = BleManager(this)
        setupRecyclerView()
        setupClickListeners()
        observeState()
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { scanResult ->
            val intent = Intent(this, DeviceConfigActivity::class.java).apply {
                putExtra("device_name", scanResult.device.name ?: "Unknown")
                putExtra("device_address", scanResult.device.address)
            }
            startActivity(intent)
        }

        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnScan.setOnClickListener {
            if (bleManager.isScanning.value) {
                bleManager.stopScan()
            } else {
                checkBluetoothAndScan()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            bleManager.isScanning.collectLatest { isScanning ->
                binding.btnScan.text = if (isScanning) "Stop Scan" else "Scan ESP32"
                binding.progressBar.visibility = if (isScanning) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            bleManager.scanResults.collectLatest { results ->
                deviceAdapter.submitList(results)
                binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun checkBluetoothAndScan() {
        // FIX: Di Android 12+ (API 31+), BLUETOOTH_CONNECT wajib di-grant sebelum
        // meluncurkan ACTION_REQUEST_ENABLE. Jika belum granted, minta permission dulu.
        // Setelah granted, permissionLauncher callback akan handle enable BT & scan.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
            return
        }

        if (!bleManager.isBluetoothEnabled()) {
            @Suppress("DEPRECATION")
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            checkPermissionsAndScan()
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startScan()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startScan() {
        bleManager.startScan()

        // Auto stop after 10 seconds
        binding.root.postDelayed({
            if (bleManager.isScanning.value) {
                bleManager.stopScan()
            }
        }, 10000)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.close()
    }

    /**
     * Adapter for device list
     */
    inner class DeviceAdapter(
        private val onClick: (ScanResult) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        private var items: List<ScanResult> = emptyList()

        fun submitList(newItems: List<ScanResult>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemDeviceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(
            private val binding: ItemDeviceBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(scanResult: ScanResult) {
                binding.tvDeviceName.text = scanResult.device.name ?: "Unknown Device"
                binding.tvDeviceAddress.text = scanResult.device.address
                binding.tvRssi.text = "${scanResult.rssi} dBm"

                binding.root.setOnClickListener {
                    onClick(scanResult)
                }
            }
        }
    }
}
