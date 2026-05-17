package com.example.phase4_camera_eps_s3_mobile.model

data class TofGrid(
    val timestampUs: Long,
    val distances: ShortArray  // 64 nilai (8x8), mm; -1 = invalid (0xFFFF)
) {
    /** Jarak minimum dari seluruh 64 zona (mm), -1 jika semua invalid */
    fun minDistance(): Int {
        val valid = distances.filter { it.toInt() and 0xFFFF != 0xFFFF }
        return if (valid.isEmpty()) -1 else valid.minOf { it.toInt() and 0xFFFF }
    }
}
