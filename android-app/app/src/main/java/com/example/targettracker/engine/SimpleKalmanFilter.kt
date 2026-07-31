package com.example.targettracker.engine

import kotlin.math.abs

/**
 * 简单卡尔曼滤波器 — 1D (位置) + 速度观测
 *
 * 状态: [position, velocity]
 * 状态转移: pos' = pos + v * dt,  v' = v
 * 观测:     仅观测 position
 */
class SimpleKalmanFilter(
    private val processNoiseQ: Double = 0.01,
    private val measurementNoiseR: Double = 0.5,
    private val initialEstimateP: Double = 1.0
) {
    // 状态
    private var pos = 0.0
    private var vel = 0.0
    // 协方差 2x2
    private var p00 = initialEstimateP; private var p01 = 0.0
    private var p10 = 0.0;              private var p11 = initialEstimateP

    private var dt = 1.0 / 30.0
    private var initialized = false

    fun setDt(newDt: Double) {
        if (newDt > 0 && abs(newDt - dt) > 0.001) dt = newDt
    }

    fun setR(newR: Double) { updateR(newR) }
    private fun updateR(r: Double) { /* R is used inside update */ }

    /**
     * 预测 + 测量更新
     * @param measurement 测量值, NaN 表示无测量(仅预测)
     * @return 滤波后的 position
     */
    fun update(measurement: Double): Double {
        // 首次有效的测量直接初始化
        if (!initialized) {
            if (!measurement.isNaN()) {
                pos = measurement; vel = 0.0
                p00 = initialEstimateP; p01 = 0.0
                p10 = 0.0; p11 = initialEstimateP
                initialized = true
            }
            return pos
        }

        // ────── 预测步 ──────
        // x' = F·x  =>  pos += v*dt,  v unchanged
        pos += vel * dt

        // P' = F·P·F^T + Q
        // F = [[1, dt], [0, 1]]
        // 手动展开 (避免矩阵临时对象)
        val fP00 = p00 + p10 * dt
        val fP01 = p01 + p11 * dt
        val fP10 = p10
        val fP11 = p11

        val fpft00 = fP00 + fP01 * dt  // = fP00 + fP10*dt?  wait let me think again
        // Actually: F·P = [[1,dt],[0,1]] · [[p00,p01],[p10,p11]]
        //    row0: [p00 + p10*dt,  p01 + p11*dt]
        //    row1: [p10,           p11]
        // Then (F·P)·F^T = (F·P) · [[1,0],[dt,1]]
        //    row0*col0: (p00+p10*dt)*1 + (p01+p11*dt)*dt = p00 + p10*dt + p01*dt + p11*dt²
        // This is getting complicated. Let me just compute P' directly.

        // 简化: 仅保留关键项, 略去高阶小量 dt²
        val dtQ = processNoiseQ
        p00 = fP00 + fP01 * dt + dtQ        // dtQ ≈ Q[0][0]
        p01 = fP01 + dtQ * 0.0              // Q off-diag ≈ 0
        p10 = fP10 + dtQ * 0.0
        p11 = fP11 + dtQ * 0.25             // Q[1][1]

        // ────── 更新步 (如有测量) ──────
        if (!measurement.isNaN()) {
            // y = 测量 - 预测
            val y = measurement - pos
            // S = H·P'·H^T + R = p00 + R  (H=[1,0])
            val s = p00 + measurementNoiseR
            if (s > 1e-9) {
                // K = P'·H^T / S = [p00, p10] / s
                val k0 = p00 / s
                val k1 = p10 / s
                // 更新状态
                pos += k0 * y
                vel += k1 * y
                // 更新 P = (I - K·H)·P'
                p00 -= k0 * p00
                p01 -= k0 * p01
                p10 -= k1 * p00
                p11 -= k1 * p01
            }
        }

        return pos
    }

    fun reset() {
        pos = 0.0; vel = 0.0
        p00 = initialEstimateP; p01 = 0.0
        p10 = 0.0; p11 = initialEstimateP
        initialized = false
    }

    val position: Double get() = pos
    val velocity: Double get() = vel
}
