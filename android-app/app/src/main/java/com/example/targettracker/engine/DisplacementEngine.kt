package com.example.targettracker.engine

import com.example.targettracker.config.Config
import com.example.targettracker.detector.DetectionResult
import kotlin.math.abs

/**
 * 多靶标位移测量引擎
 *
 * 每靶标独立:
 *   - 三轴卡尔曼滤波 (SimpleKalmanFilter x X/Y/Z)
 *   - 滑动平均 (5帧窗口)
 *   - 自适应死区 (innov < 0.3mm 冻结)
 *   - 异常值检测 (帧间跳 > 50mm)
 *   - 连续异常恢复 (10帧后强制信任)
 *   - 检测失败 → 仅预测不更新
 */
class DisplacementEngine(private val measureConfig: Config.Measure = Config.measure) {

    data class TargetState(
        var kfX: SimpleKalmanFilter = SimpleKalmanFilter(),
        var kfY: SimpleKalmanFilter = SimpleKalmanFilter(),
        var kfZ: SimpleKalmanFilter = SimpleKalmanFilter(),
        // 滑动平均
        var maWindowX: MutableList<Double> = mutableListOf(),
        var maWindowY: MutableList<Double> = mutableListOf(),
        var maWindowZ: MutableList<Double> = mutableListOf(),
        // 零位
        var zeroX: Double = 0.0,
        var zeroY: Double = 0.0,
        var zeroZ: Double = 0.0,
        // 异常值状态
        var consecutiveOutliers: Int = 0,
        var prevXMm: Double = 0.0,
        var prevYMm: Double = 0.0,
        var prevZMm: Double = 0.0,
        var globalZeroed: Boolean = false
    )

    private val states = mutableMapOf<Int, TargetState>()
    private val windowSize = measureConfig.slidingWindowSize
    private val outlierThreshold = measureConfig.outlierThresholdMm
    private val maxOutliers = measureConfig.maxConsecutiveOutliers
    private val deadZone = measureConfig.deadZoneMm

    private var lastTimestamp: Long = 0L
    private var frameCount: Int = 0
    private var outlierCount: Int = 0

    /**
     * 处理所有靶标的检测结果
     */
    fun measureAll(
        detectResults: Map<Int, DetectionResult>,
        timestamp: Long
    ): Map<Int, DisplacementResult> {
        val dt = if (lastTimestamp > 0) {
            (timestamp - lastTimestamp) / 1000.0
        } else {
            1.0 / 30.0
        }
        lastTimestamp = timestamp
        frameCount++

        val results = mutableMapOf<Int, DisplacementResult>()

        for ((tid, det) in detectResults) {
            if (!det.success) continue

            val state = states.getOrPut(tid) { TargetState() }
            state.kfX.setDt(dt); state.kfY.setDt(dt); state.kfZ.setDt(dt)

            // 原始测量值
            val rawX = det.xMm; val rawY = det.yMm; val rawZ = det.zMm

            // ──── 异常值检测 ────
            var isOutlierNow = false
            if (state.globalZeroed) {
                val dx = abs(rawX - state.prevXMm)
                val dy = abs(rawY - state.prevYMm)
                val dz = abs(rawZ - state.prevZMm)

                if (dx > outlierThreshold || dy > outlierThreshold || dz > outlierThreshold) {
                    state.consecutiveOutliers++
                    isOutlierNow = true
                } else {
                    state.consecutiveOutliers = 0
                }
            }

            // 连续异常恢复: 10帧后强制信任
            if (state.consecutiveOutliers >= maxOutliers) {
                state.consecutiveOutliers = 0
                isOutlierNow = false
                resetKalman(state, rawX, rawY, rawZ)
            }

            // ──── 卡尔曼滤波 ────
            val filteredX: Double
            val filteredY: Double
            val filteredZ: Double

            if (isOutlierNow) {
                outlierCount++
                // 仅预测
                filteredX = state.kfX.update(Double.NaN)
                filteredY = state.kfY.update(Double.NaN)
                filteredZ = state.kfZ.update(Double.NaN)
            } else {
                // 动态R: 大创新减小R快速跟踪
                val innovX = abs(rawX - state.kfX.position)
                if (innovX > outlierThreshold * 0.5) {
                    state.kfX.setR(0.1)
                } else {
                    state.kfX.setR(0.5)
                }
                filteredX = state.kfX.update(rawX)
                filteredY = state.kfY.update(rawY)
                filteredZ = state.kfZ.update(rawZ)
            }

            // ──── 自适应死区 ────
            val kfPos = state.kfX.position; val kfPrev = state.prevXMm
            val finalX = if (state.globalZeroed && abs(kfPos - kfPrev) < deadZone) kfPrev else filteredX
            val kfPosY = state.kfY.position; val kfPrevY = state.prevYMm
            val finalY = if (state.globalZeroed && abs(kfPosY - kfPrevY) < deadZone) kfPrevY else filteredY
            val kfPosZ = state.kfZ.position; val kfPrevZ = state.prevZMm
            val finalZ = if (state.globalZeroed && abs(kfPosZ - kfPrevZ) < deadZone) kfPrevZ else filteredZ

            // ──── 更新滑动平均 ────
            state.maWindowX.add(finalX); if (state.maWindowX.size > windowSize) state.maWindowX.removeAt(0)
            state.maWindowY.add(finalY); if (state.maWindowY.size > windowSize) state.maWindowY.removeAt(0)
            state.maWindowZ.add(finalZ); if (state.maWindowZ.size > windowSize) state.maWindowZ.removeAt(0)

            val maX = state.maWindowX.average()
            val maY = state.maWindowY.average()
            val maZ = state.maWindowZ.average()

            // ──── 零位校正 ────
            val outX = maX - state.zeroX
            val outY = maY - state.zeroY
            val outZ = maZ - state.zeroZ

            // 更新前一帧值
            state.prevXMm = finalX; state.prevYMm = finalY; state.prevZMm = finalZ

            results[tid] = DisplacementResult(
                targetId = tid,
                xMm = outX, yMm = outY, zMm = outZ,
                maXMm = maX - state.zeroX, maYMm = maY - state.zeroY, maZMm = maZ - state.zeroZ,
                rawXMm = rawX - state.zeroX, rawYMm = rawY - state.zeroY, rawZMm = rawZ - state.zeroZ,
                detectionQuality = det.quality,
                isOutlier = isOutlierNow,
                isStale = false
            )
        }

        // ──── 已丢失的靶标: 仅预测 ────
        val detectedIds = detectResults.keys
        for ((tid, state) in states) {
            if (tid !in detectedIds && state.globalZeroed) {
                state.kfX.setDt(dt); state.kfY.setDt(dt); state.kfZ.setDt(dt)
                state.kfX.update(Double.NaN)
                state.kfY.update(Double.NaN)
                state.kfZ.update(Double.NaN)

                val outX = state.kfX.position - state.zeroX
                val outY = state.kfY.position - state.zeroY
                val outZ = state.kfZ.position - state.zeroZ

                results[tid] = DisplacementResult(
                    targetId = tid,
                    xMm = outX, yMm = outY, zMm = outZ,
                    rawXMm = outX, rawYMm = outY, rawZMm = outZ,
                    isStale = true
                )
            }
        }

        return results
    }

    /** 设置全局零位 */
    fun setZero() {
        for ((_, state) in states) {
            state.zeroX = state.kfX.position
            state.zeroY = state.kfY.position
            state.zeroZ = state.kfZ.position
            state.globalZeroed = true
            state.consecutiveOutliers = 0
        }
    }

    /** 重置所有状态 */
    fun reset() {
        states.values.forEach { state ->
            state.kfX.reset(); state.kfY.reset(); state.kfZ.reset()
            state.maWindowX.clear(); state.maWindowY.clear(); state.maWindowZ.clear()
            state.zeroX = 0.0; state.zeroY = 0.0; state.zeroZ = 0.0
            state.globalZeroed = false
            state.consecutiveOutliers = 0
        }
        states.clear()
        frameCount = 0; outlierCount = 0
    }

    /** 全局统计 */
    fun getStats(): Map<String, Any> = mapOf(
        "totalFrames" to frameCount,
        "outlierCount" to outlierCount,
        "activeTargets" to states.count { it.value.globalZeroed },
        "outlierRate" to if (frameCount > 0) outlierCount.toDouble() / frameCount else 0.0
    )

    private fun resetKalman(state: TargetState, x: Double, y: Double, z: Double) {
        state.kfX.reset(); state.kfX.update(x)
        state.kfY.reset(); state.kfY.update(y)
        state.kfZ.reset(); state.kfZ.update(z)
    }

    companion object {
        private var outageCount = 0 // static counter shared across engines
    }
}
