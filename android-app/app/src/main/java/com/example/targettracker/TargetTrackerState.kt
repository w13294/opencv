package com.example.targettracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.targettracker.config.CalibrationData
import com.example.targettracker.detector.DetectionResult
import com.example.targettracker.engine.DisplacementResult

/**
 * 应用全局状态 (ViewModel-free, 简单 mutableState 方案)
 */
class TargetTrackerState {
    // 位移数据
    var dispResults: Map<Int, DisplacementResult> by mutableStateOf(emptyMap())
    var detectResults: Map<Int, DetectionResult> by mutableStateOf(emptyMap())

    // 当前图像尺寸 (imageWidth, imageHeight), 用于标注层缩放计算
    var imageSize: Pair<Int, Int> by mutableStateOf(0 to 0)

    // 帧统计
    var fps: Double by mutableStateOf(0.0)
    var frameNum: Long by mutableStateOf(0)
    var stats: Map<String, Any> by mutableStateOf(emptyMap())

    // 归零状态
    var zeroed: Boolean by mutableStateOf(false)

    // 标定
    var calibrationData: CalibrationData by mutableStateOf(CalibrationData())

    // 告警
    var warningMessage: String? by mutableStateOf(null)

    /** 主靶标 (用于单靶标显示) */
    val primaryDisplacement: DisplacementResult?
        get() {
            val ids = dispResults.keys.sorted()
            return if (ids.isNotEmpty()) dispResults[ids.first()] else null
        }

    val primaryDetection: DetectionResult?
        get() {
            val ids = detectResults.keys.sorted()
            return if (ids.isNotEmpty()) detectResults[ids.first()] else null
        }
}
