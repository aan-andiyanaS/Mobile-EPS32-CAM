package com.example.phase4_camera_eps_s3_mobile.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * BLE Manager untuk handle koneksi dan komunikasi dengan ESP32
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"

        // UUID harus sama dengan yang di ESP32
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHAR_COMMAND_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val CHAR_RESPONSE_UUID: UUID = UUID.fromString("cba1d466-344c-4be3-ab3f-189f80dd7518")

        // UUID untuk Client Characteristic Configuration Descriptor (notification)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    // Bluetooth adapter
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // BLE Scanner
    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    // GATT connection
    private var bluetoothGatt: BluetoothGatt? = null

    // Characteristics
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null

    // State flows
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // FIX #3: Gunakan SupervisorJob agar scope bisa di-cancel saat close() dipanggil
    // SupervisorJob memastikan satu child coroutine gagal tidak membatalkan yang lain
    private val bleJob = SupervisorJob()
    private val bleScope = CoroutineScope(Dispatchers.Main + bleJob)

    // PENTING: Gunakan SharedFlow dengan buffer besar untuk received data
    // replay = 10 untuk menyimpan beberapa data terakhir jika collector lambat
    private val _receivedData = MutableSharedFlow<String>(
        replay = 10,
        extraBufferCapacity = 100
    )
    val receivedData: SharedFlow<String> = _receivedData

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCOVERING_SERVICES,
        READY
    }

    // Scan callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val currentList = _scanResults.value.toMutableList()
            val existingIndex = currentList.indexOfFirst {
                it.device.address == result.device.address
            }
            if (existingIndex >= 0) {
                currentList[existingIndex] = result
            } else {
                currentList.add(result)
            }
            _scanResults.value = currentList
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _isScanning.value = false
        }
    }

    // GATT callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    _connectionState.value = ConnectionState.CONNECTED

                    // Request larger MTU for bigger messages (max 512)
                    Log.d(TAG, "Requesting MTU 512...")
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to: $mtu (status: $status)")
            _connectionState.value = ConnectionState.DISCOVERING_SERVICES
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")

                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    commandCharacteristic = service.getCharacteristic(CHAR_COMMAND_UUID)
                    responseCharacteristic = service.getCharacteristic(CHAR_RESPONSE_UUID)

                    // FIX #1: Jangan set READY di sini — tunggu onDescriptorWrite callback
                    // State READY hanya di-set setelah CCCD berhasil ditulis (notify aktif)
                    responseCharacteristic?.let { char ->
                        gatt.setCharacteristicNotification(char, true)
                        val descriptor = char.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        } else {
                            Log.e(TAG, "CCCD descriptor not found — notify may not work")
                            // Fallback: set READY meski notify mungkin tidak aktif
                            _connectionState.value = ConnectionState.READY
                        }
                    }
                } else {
                    Log.e(TAG, "Service not found")
                }
            }
        }

        // FIX #1: Set READY hanya setelah CCCD descriptor berhasil ditulis
        // Ini menjamin tombol "Scan WiFi" hanya aktif saat notify sudah benar-benar aktif
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "CCCD written — notify enabled, state: READY")
                    _connectionState.value = ConnectionState.READY
                } else {
                    Log.e(TAG, "CCCD write failed with status: $status, retrying...")
                    // Retry sekali jika gagal (misal device butuh delay)
                    responseCharacteristic?.getDescriptor(CCCD_UUID)?.let { desc ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(desc)
                        }
                    }
                }
            }
        }

        // Handler untuk notify dari ESP32 — support API lama dan baru
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_RESPONSE_UUID) {
                val data = characteristic.getStringValue(0) ?: return
                Log.d(TAG, "BLE Received [${data.length} chars]: $data")
                bleScope.launch {
                    _receivedData.emit(data)
                    Log.d(TAG, "Emitted successfully: $data")
                }
            }
        }

        // FIX #2 (tambahan): Override API baru untuk Android 13+ agar tidak ada data hilang
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHAR_RESPONSE_UUID) {
                val data = value.toString(Charsets.UTF_8)
                Log.d(TAG, "BLE Received (API33+) [${data.length} chars]: $data")
                bleScope.launch {
                    _receivedData.emit(data)
                    Log.d(TAG, "Emitted successfully: $data")
                }
            }
        }
    }

    /**
     * Check if Bluetooth is available and enabled
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Start scanning for ESP32 devices
     */
    fun startScan() {
        if (_isScanning.value) return

        _scanResults.value = emptyList()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
        _isScanning.value = true
        Log.d(TAG, "Scan started")
    }

    /**
     * Stop scanning
     */
    fun stopScan() {
        if (!_isScanning.value) return

        bleScanner?.stopScan(scanCallback)
        _isScanning.value = false
        Log.d(TAG, "Scan stopped")
    }

    /**
     * Connect to a device
     */
    fun connect(device: BluetoothDevice) {
        stopScan()
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    /**
     * Disconnect from device
     */
    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    /**
     * FIX #2: Send command ke ESP32 — support API baru (Android 13+) dan lama
     * API lama: setValue() + writeCharacteristic(characteristic)
     * API baru: writeCharacteristic(characteristic, value, writeType) — return code eksplisit
     */
    fun sendCommand(command: String): Boolean {
        val characteristic = commandCharacteristic ?: return false
        val gatt = bluetoothGatt ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(
                characteristic,
                command.toByteArray(Charsets.UTF_8),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            (result == BluetoothStatusCodes.SUCCESS).also {
                if (!it) Log.e(TAG, "writeCharacteristic failed, code: $result")
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.setValue(command)
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    /**
     * Send SCAN command to get WiFi list
     */
    fun scanWifi(): Boolean {
        return sendCommand("SCAN")
    }

    /**
     * Send CONNECT command with WiFi credentials to ESP32
     * Format: CONNECT:ssid|password
     */
    fun connectWifi(ssid: String, password: String): Boolean {
        val command = "CONNECT:$ssid|$password"
        Log.d(TAG, "Sending connect command for SSID: $ssid")
        return sendCommand(command)
    }

    /**
     * Cleanup — FIX #3: cancel bleJob agar tidak ada coroutine zombie setelah destroy
     */
    fun close() {
        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        bleJob.cancel()
    }
}
