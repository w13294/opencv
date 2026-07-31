package com.example.targettracker.config

/**
 * 相机标定数据
 */
data class CalibrationData(
    val cameraMatrix: DoubleArray = doubleArrayOf(
        800.0, 0.0, 640.0,
        0.0, 800.0, 360.0,
        0.0, 0.0, 1.0
    ),
    val distCoeffs: DoubleArray = DoubleArray(5) { 0.0 },
    val imageWidth: Int = 1280,
    val imageHeight: Int = 720,
    val reprojectionError: Double = 0.0,
    val isValid: Boolean = false
)
