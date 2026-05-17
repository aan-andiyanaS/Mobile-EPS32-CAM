package com.example.phase4_camera_eps_s3_mobile.model

data class ImuData(
    val timestampUs: Long,
    val pitch: Float,
    val roll: Float,
    val yawRate: Float,   // rad/s
    val aLinMag: Float    // m/s²
)
