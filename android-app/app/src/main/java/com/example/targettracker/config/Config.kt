package com.example.targettracker.config

/**
 * 全局配置 — 与 Python 版 src/config.py 对应
 */
object Config {
    // ---------- 靶标 ----------
    data class Target(
        val mode: String = "quadrant",
        val defaultSizesMm: List<Double> = listOf(200.0, 100.0, 50.0),
        val sizeToleranceMm: Double = 20.0,
        val lostTimeoutFrames: Int = 30,
        val minDistancePx: Double = 150.0
    )

    // ---------- 测量 ----------
    data class Measure(
        val deadZoneMm: Double = 0.3,
        val outlierThresholdMm: Double = 50.0,
        val maxConsecutiveOutliers: Int = 10,
        val slidingWindowSize: Int = 5,
        val dynamicRFactor: Double = 0.5
    )

    // ---------- 卡尔曼滤波器 ----------
    data class Kalman(
        val processNoiseQ: Double = 0.01,
        val measurementNoiseR: Double = 0.5,
        val initialEstimateP: Double = 1.0
    )

    // ---------- 显示 ----------
    data class Display(
        val width: Int = 1280,
        val height: Int = 720,
        val trajectoryLength: Int = 300,
        val showTrajectory: Boolean = true,
        val showHud: Boolean = true
    )

    // ---------- 相机 ----------
    data class Camera(
        val cameraId: Int = 0,
        val targetFps: Int = 30,
        val enableAutoFocus: Boolean = true,
        val enableAutoExposure: Boolean = true
    )

    val target = Target()
    val measure = Measure()
    val kalman = Kalman()
    val display = Display()
    val camera = Camera()
}
