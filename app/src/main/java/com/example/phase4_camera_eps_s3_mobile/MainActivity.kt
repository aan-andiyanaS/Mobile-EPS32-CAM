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
import com.example.phase4_camera_eps_s3_mobile.ui.CameraStreamActivity
import com.example.phase4_camera_eps_s3_mobile.ui.DeviceConfigActivity
import com.example.phase4_camera_eps_s3_mobile.util.SessionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity — BLE Scanner / Landing screen
 *
 * Flow:
 *  1. Jika ada IP tersimpan (sesi aktif) → langsung ke CameraStreamActivity.
 *     Ini terjadi setelah provisioning pertama, dan TETAP terjadi setelah "Akhiri"
 *     (karena "Akhiri" tidak menghapus IP).
 *
 *  2. Jika tidak ada IP (pertama kali / setelah reset) → tampil BLE scan untuk provisioning.
 *
 * Halaman ini HANYA muncul saat belum ada perangkat ESP32 yang pernah dikonfigurasi.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding:        ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    // bleManager hanya diinit jika perlu BLE scan (tidak ada IP tersimpan)
    private var bleManager: BleManager? = null
    private var deviceAdapter: DeviceAdapter? = null

    private val stopScanRunnable = Runnable {
        bleManager?.takeIf { it.isScanning.value }?.stopScan()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            if (bleManager?.isBluetoothEnabled() == false) {
                @Suppress("DEPRECATION")
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                startScan()
            }
        } else {
            Toast.makeText(this, "Izin Bluetooth diperlukan untuk scan perangkat", Toast.LENGTH_SHORT).show()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) checkPermissionsAndScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        // ── Prioritas 1: Ada IP tersimpan → langsung ke kamera ─────────────
        val savedIp = sessionManager.getSavedEsp32Ip()
        if (savedIp != null) {
            startActivity(CameraStreamActivity.createIntent(this, savedIp))
            finish()
            return  // bleManager TIDAK diinit di sini
        }

        // ── Prioritas 2: Tidak ada IP → perlu provisioning via BLE ─────────
        bleManager = BleManager(this)
        val adapter = DeviceAdapter { scanResult ->
            startActivity(Intent(this, DeviceConfigActivity::class.java).apply {
                putExtra("device_name",    scanResult.device.name ?: "Unknown")
                putExtra("device_address", scanResult.device.address)
            })
        }
        deviceAdapter = adapter

        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter  = adapter
        }

        // Sembunyikan elemen banner (tidak digunakan)
        binding.layoutBanner.visibility = View.GONE

        setupClickListeners()
        observeState()

        // Mulai scan otomatis saat halaman pertama dibuka
        checkBluetoothAndScan()
    }

    private fun setupClickListeners() {
        binding.btnScan.setOnClickListener {
            val bm = bleManager ?: return@setOnClickListener
            if (bm.isScanning.value) bm.stopScan()
            else checkBluetoothAndScan()
        }
    }

    private fun observeState() {
        val bm = bleManager ?: return

        lifecycleScope.launch {
            bm.isScanning.collectLatest { isScanning ->
                binding.btnScan.text           = if (isScanning) "Stop Scan" else "Scan ESP32"
                binding.progressBar.visibility = if (isScanning) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            bm.scanResults.collectLatest { results ->
                deviceAdapter?.submitList(results)
                binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                if (results.isEmpty()) {
                    binding.tvEmpty.text = "Tidak ada perangkat ESP32 ditemukan.\nPastikan ESP32 menyala dan dalam mode BLE."
                }
            }
        }
    }

    private fun checkBluetoothAndScan() {
        val bm = bleManager ?: return
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
        if (!bm.isBluetoothEnabled()) {
            @Suppress("DEPRECATION")
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            checkPermissionsAndScan()
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) startScan()
        else permissionLauncher.launch(notGranted.toTypedArray())
    }

    private fun startScan() {
        bleManager?.startScan()
        binding.root.removeCallbacks(stopScanRunnable)
        binding.root.postDelayed(stopScanRunnable, 15_000)  // auto-stop 15 detik
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.root.removeCallbacks(stopScanRunnable)
        bleManager?.close()  // null-safe karena var nullable
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    inner class DeviceAdapter(
        private val onClick: (ScanResult) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        private var items: List<ScanResult> = emptyList()

        fun submitList(newItems: List<ScanResult>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class ViewHolder(private val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(sr: ScanResult) {
                b.tvDeviceName.text    = sr.device.name ?: "Unknown Device"
                b.tvDeviceAddress.text = sr.device.address
                b.tvRssi.text          = "${sr.rssi} dBm"
                b.root.setOnClickListener { onClick(sr) }
            }
        }
    }
}
