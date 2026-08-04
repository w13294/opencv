package com.example.targettracker.detector

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.RotatedRect

/**
 * 单个靶标的检测结果
 */
data class DetectionResult(
    val success: Boolean = false,
    val targetId: Int = -1,
    val center: Point = Point(0.0, 0.0),
    val ellipse: RotatedRect? = null,
    val corners: List<Point>? = null,
    val rvec: Mat = Mat.zeros(3, 1, org.opencv.core.CvType.CV_64F),
    val tvec: Mat = Mat.zeros(3, 1, org.opencv.core.CvType.CV_64F),
    val quality: Double = 0.0,
    /** 该靶标使用的物理尺寸 (mm), 用于标注层显示 */
    val sizeMm: Double = 200.0
) {
    val cx: Double get() = center.x
    val cy: Double get() = center.y

    /** 从 3x1 tvec 取出 X/Y/Z (mm) */
    val xMm: Double get() = if (tvec.rows() >= 3) tvec.get(0, 0)[0] else 0.0
    val yMm: Double get() = if (tvec.rows() >= 3) tvec.get(1, 0)[0] else 0.0
    val zMm: Double get() = if (tvec.rows() >= 3) tvec.get(2, 0)[0] else 0.0
}
