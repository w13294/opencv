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
        val lostTimeoutFrames: Int = 5,  // 脱靶后 ≤5帧 (~170ms) 移除标注，快速响应
        val minDistancePx: Double = 150.0
    )

    // ---------- 测量 ----------
    data class Measure(
        // 死区: 仅用于抑制零位附近的静止抖动, 基准改为"相对零位"而非"相对前一帧",
        // 否则持续小位移会被反复冻结。调小以提高小位移灵敏度。
        val deadZoneMm: Double = 0.1,
        val outlierThresholdMm: Double = 50.0,
        val maxConsecutiveOutliers: Int = 10,
        // 滑动窗口减小, 降低迟滞
        val slidingWindowSize: Int = 3,
        val dynamicRFactor: Double = 0.15
    )

    // ---------- 卡尔曼滤波器 ----------
    data class Kalman(
        // 过程噪声增大 + 测量噪声减小 => 滤波器更"信任测量", 小位移更跟手
        val processNoiseQ: Double = 0.05,
        val measurementNoiseR: Double = 0.15,
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
