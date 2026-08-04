package com.example.targettracker.config

import android.util.Log
import com.example.targettracker.detector.DetectionResult

/**
 * 已知距离标定器。
 *
 * 原理 (针孔模型):
 *   已知靶标真实直径 W(mm), 靶标到相机距离 D(mm), 检测得到像素直径 d(px)
 *   则焦距 f(px) = d * D / W
 *
 * 相比默认的 "假设 60° FOV" 猜测值, 这是用真实测量反推的焦距,
 * 能直接消除距离估算的系统性偏差。
 *
 * 采样策略: 连续采集 N 帧, 取 f 的中位数 (抗抖动/抗偶发误检),
 * 并用 MAD (中位绝对偏差) 评估稳定性作为置信度。
 */
class DistanceCalibrator(
    /** 靶标真实直径 mm */
    val targetSizeMm: Double,
    /** 靶标到相机的已知距离 mm */
    val knownDistanceMm: Double,
    /** 需要采集的有效样本数 */
    val requiredSamples: Int = 30,
    /** 只接受质量高于该值的检测结果 */
    val minQuality: Double = 0.35
) {
    companion object {
        private const val TAG = "DistCalib"
    }

    private val fxSamples = mutableListOf<Double>()
    private val fySamples = mutableListOf<Double>()
    private val cxSamples = mutableListOf<Double>()
    private val cySamples = mutableListOf<Double>()

    @Volatile var imageWidth = 0; private set
    @Volatile var imageHeight = 0; private set

    /** 已采集样本数 */
    val sampleCount: Int get() = fxSamples.size

    /** 采集进度 0..1 */
    val progress: Float
        get() = (fxSamples.size.toFloat() / requiredSamples).coerceIn(0f, 1f)

    val isComplete: Boolean get() = fxSamples.size >= requiredSamples

    /** 最近一次被拒绝的原因 (供 UI 提示用户如何调整) */
    @Volatile var lastReject: String? = null
        private set

    /**
     * 投喂一帧检测结果。返回 true 表示本帧被采纳为有效样本。
     *
     * 只采纳画面中"尺寸最接近标定靶标"的那个目标, 避免多靶标场景下混入错误尺寸。
     */
    @Synchronized
    fun feed(results: Map<Int, DetectionResult>, width: Int, height: Int): Boolean {
        imageWidth = width
        imageHeight = height

        if (isComplete) return false

        val candidates = results.values.filter {
            it.success && it.ellipse != null && it.quality >= minQuality
        }
        if (candidates.isEmpty()) {
            lastReject = if (results.isEmpty()) "未检测到靶标" else "靶标质量偏低, 请稳定持机"
            return false
        }

        // 多靶标时优先取声明尺寸与标定尺寸一致的; 否则取像素最大的 (通常是主靶标)
        val det = candidates.firstOrNull {
            Math.abs(it.sizeMm - targetSizeMm) < 1.0
        } ?: candidates.maxByOrNull {
            val e = it.ellipse!!
            e.size.width + e.size.height
        }!!

        val e = det.ellipse!!
        val dw = e.size.width
        val dh = e.size.height
        if (dw < 8.0 || dh < 8.0) {
            lastReject = "靶标过小, 请靠近或放大"
            return false
        }

        // 圆度检查: 标定要求正对靶标, 椭圆长短轴比过大说明有倾斜, 会让焦距偏小
        val ratio = if (dw > dh) dw / dh else dh / dw
        if (ratio > 1.15) {
            lastReject = "请正对靶标 (当前倾斜 %.0f%%)".format((ratio - 1) * 100)
            return false
        }

        // f = d * D / W, 长短轴分别对应 fx / fy
        val fx = dw * knownDistanceMm / targetSizeMm
        val fy = dh * knownDistanceMm / targetSizeMm
        if (!fx.isFinite() || !fy.isFinite() || fx <= 0 || fy <= 0) {
            lastReject = "数值异常"
            return false
        }

        fxSamples += fx
        fySamples += fy
        cxSamples += det.center.x
        cySamples += det.center.y
        lastReject = null
        return true
    }

    /** 丢弃已采集的样本, 重新开始 */
    @Synchronized
    fun reset() {
        fxSamples.clear(); fySamples.clear()
        cxSamples.clear(); cySamples.clear()
        lastReject = null
    }

    /**
     * 结算标定结果。样本不足时返回 null。
     *
     * 主点 (cx,cy) 不用靶标中心 (那只反映用户摆放位置),
     * 仍取图像中心 —— 单靶标单距离无法可靠解算主点偏移。
     */
    @Synchronized
    fun finish(): CalibrationData? {
        if (fxSamples.size < 3) return null

        val fx = median(fxSamples)
        val fy = median(fySamples)
        val cx = imageWidth / 2.0
        val cy = imageHeight / 2.0

        // 用 MAD 折算成"等效重投影误差 (px)": 焦距抖动越小越可信
        val mad = median(fxSamples.map { Math.abs(it - fx) })
        val relErr = if (fx > 0) mad / fx else 1.0
        // 换算到靶标像素尺度上的偏差量, 便于用户理解
        val err = relErr * targetSizeMm * fx / knownDistanceMm

        Log.i(
            TAG,
            "calib done n=${fxSamples.size} fx=%.1f fy=%.1f cx=%.1f cy=%.1f mad=%.2f relErr=%.3f%%"
                .format(fx, fy, cx, cy, mad, relErr * 100)
        )

        return CalibrationData(
            cameraMatrix = doubleArrayOf(
                fx, 0.0, cx,
                0.0, fy, cy,
                0.0, 0.0, 1.0
            ),
            // 单距离标定无法解算畸变, 保持为 0 (中心区域畸变影响很小)
            distCoeffs = DoubleArray(5) { 0.0 },
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            reprojectionError = err,
            isValid = true
        )
    }

    private fun median(list: List<Double>): Double {
        if (list.isEmpty()) return 0.0
        val s = list.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
