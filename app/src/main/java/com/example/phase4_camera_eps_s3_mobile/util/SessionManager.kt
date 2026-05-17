package com.example.phase4_camera_eps_s3_mobile.util

import android.content.Context
import android.content.SharedPreferences

/**
 * SessionManager — menyimpan state sesi koneksi ESP32 secara persisten.
 * Digunakan untuk mendeteksi apakah ESP32 sudah pernah dikonfigurasi
 * sehingga aplikasi bisa langsung lompat ke CameraStreamActivity.
 */
class SessionManager(context: Context) {

    companion object {
        private const val PREF_NAME    = "esp32_session"
        private const val KEY_ESP32_IP = "esp32_ip"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Simpan IP ESP32 setelah berhasil dapat dari BLE provisioning */
    fun saveEsp32Ip(ip: String) {
        prefs.edit().putString(KEY_ESP32_IP, ip).apply()
    }

    /** Ambil IP yang tersimpan. Mengembalikan null jika belum pernah disimpan. */
    fun getSavedEsp32Ip(): String? {
        val ip = prefs.getString(KEY_ESP32_IP, null)
        return if (ip.isNullOrEmpty()) null else ip
    }

    /** Hapus semua data sesi (dipanggil saat reset WiFi di ESP32) */
    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
