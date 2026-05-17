package com.example.phase4_camera_eps_s3_mobile.model

/**
 * Data class untuk menyimpan info WiFi dari ESP32
 */
data class WifiInfo(
    val index: Int,
    val ssid: String,
    val rssi: Int,
    val encryption: String
) {
    /**
     * Parse dari string format: "WIFI:0|MyWiFi|-45|Secured"
     * Dipertahankan untuk kompatibilitas — meski firmware saat ini hanya mengirim BATCH:
     */
    companion object {
        fun fromString(data: String): WifiInfo? {
            if (!data.startsWith("WIFI:")) return null

            val parts = data.removePrefix("WIFI:").split("|")
            if (parts.size != 4) return null

            return try {
                WifiInfo(
                    index = parts[0].toInt(),
                    ssid = parts[1],
                    rssi = parts[2].toInt(),
                    encryption = parts[3]
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Get signal strength icon based on RSSI
     */
    fun getSignalStrength(): SignalStrength {
        return when {
            rssi >= -50 -> SignalStrength.EXCELLENT
            rssi >= -60 -> SignalStrength.GOOD
            rssi >= -70 -> SignalStrength.FAIR
            else        -> SignalStrength.WEAK
        }
    }

    enum class SignalStrength {
        EXCELLENT, GOOD, FAIR, WEAK
    }
}
