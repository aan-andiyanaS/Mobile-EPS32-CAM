package com.example.phase4_camera_eps_s3_mobile.util

import android.content.Context
import android.content.SharedPreferences

/**
 * SessionManager — menyimpan state sesi koneksi ESP32 secara persisten.
 *
 * Dua lapis data:
 *  1. "Sesi Aktif" (KEY_ESP32_IP): IP yang menyebabkan auto-redirect ke kamera saat buka app.
 *     Dihapus saat user tekan "Akhiri" → app buka kembali → BLE scan dulu.
 *
 *  2. "Last Device" (KEY_LAST_MAC + KEY_LAST_IP): MAC address dan IP terakhir ESP32 yang
 *     pernah terhubung. TIDAK dihapus saat "Akhiri" agar BLE scan bisa auto-reconnect
 *     jika menemukan device dengan MAC yang sama.
 */
class SessionManager(context: Context) {

    companion object {
        private const val PREF_NAME     = "esp32_session"
        private const val KEY_ESP32_IP  = "esp32_ip"   // sesi aktif (auto-redirect)
        private const val KEY_LAST_MAC  = "last_mac"   // MAC ESP32 terakhir
        private const val KEY_LAST_IP   = "last_ip"    // IP ESP32 terakhir (untuk reconnect)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Sesi Aktif ──────────────────────────────────────────────────────────

    /**
     * Simpan IP ESP32 sebagai sesi aktif DAN sebagai last device.
     * Dipanggil setelah provisioning BLE berhasil dan IP diterima.
     */
    fun saveEsp32Ip(ip: String) {
        prefs.edit()
            .putString(KEY_ESP32_IP, ip)
            .putString(KEY_LAST_IP, ip)
            .apply()
    }

    /** Ambil IP sesi aktif. Mengembalikan null jika tidak ada sesi aktif. */
    fun getSavedEsp32Ip(): String? {
        val ip = prefs.getString(KEY_ESP32_IP, null)
        return if (ip.isNullOrEmpty()) null else ip
    }

    // ── Last Device (untuk BLE scan auto-reconnect) ──────────────────────

    /**
     * Simpan MAC address ESP32 terakhir yang berhasil terhubung.
     * Dipanggil bersamaan dengan saveEsp32Ip() setelah provisioning.
     */
    fun saveLastDeviceMac(mac: String) {
        prefs.edit().putString(KEY_LAST_MAC, mac).apply()
    }

    /** Ambil MAC address ESP32 terakhir. Null jika belum pernah terhubung. */
    fun getLastDeviceMac(): String? {
        val mac = prefs.getString(KEY_LAST_MAC, null)
        return if (mac.isNullOrEmpty()) null else mac
    }

    /** Ambil IP ESP32 terakhir (bisa ada meski sesi aktif sudah dihapus). */
    fun getLastDeviceIp(): String? {
        val ip = prefs.getString(KEY_LAST_IP, null)
        return if (ip.isNullOrEmpty()) null else ip
    }

    // ── Clear helpers ────────────────────────────────────────────────────

    /**
     * Hapus HANYA sesi aktif (KEY_ESP32_IP).
     * Data "last device" (MAC + last IP) tetap ada untuk BLE auto-reconnect.
     * Dipanggil dari tombol "Akhiri" di CameraStreamActivity.
     */
    fun clearActiveSession() {
        prefs.edit().remove(KEY_ESP32_IP).apply()
    }

    /**
     * Hapus semua data sesi termasuk last device.
     * Dipanggil saat factory reset / ESP32 dikonfigurasi ulang ke WiFi berbeda.
     */
    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
