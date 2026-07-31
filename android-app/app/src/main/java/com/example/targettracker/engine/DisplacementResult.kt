package com.example.targettracker.engine

/**
 * 单个靶标的位移计算结果
 */
data class DisplacementResult(
    val targetId: Int = -1,
    // 滤波后的位移 (mm)
    val xMm: Double = 0.0,
    val yMm: Double = 0.0,
    val zMm: Double = 0.0,
    // 滑动平均 (mm)
    val maXMm: Double = 0.0,
    val maYMm: Double = 0.0,
    val maZMm: Double = 0.0,
    // 滤波前原始值
    val rawXMm: Double = 0.0,
    val rawYMm: Double = 0.0,
    val rawZMm: Double = 0.0,
    // 质量
    val detectionQuality: Double = 0.0,
    val isOutlier: Boolean = false,
    val isStale: Boolean = false,
    // 位移
    val displacement2d: Double = Math.sqrt(xMm * xMm + yMm * yMm),
    val displacement3d: Double = Math.sqrt(xMm * xMm + yMm * yMm + zMm * zMm)
) {
    companion object {
        fun stale(targetId: Int) = DisplacementResult(
            targetId = targetId, isStale = true
        )
    }
}
