package com.example.targettracker.config

import android.util.Log
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicInteger

/**
 * 棋盘格相机标定器
 *
 * 使用标准棋盘格（chessboard / checkerboard）进行完整的相机内参标定，
 * 输出 3x3 相机矩阵 + 5 个畸变系数，替代旧版单帧 pinhole 近似。
 *
 * 用法：
 *   1. 打印一张棋盘格（默认 9x6 内角点，每格 25mm）
 *   2. 从多个角度/距离拍摄棋盘格（建议 20~30 张）
 *   3. 每帧调用 feed(frame, w, h)，自动收集角点
 *   4. 收集足够样本后调用 finish() 完成标定
 */
class CheckerboardCalibrator(
    private val patternSize: Size = Size(9.0, 6.0),   // 内角点数量（列 x 行）
    private val squareSizeMm: Double = 25.0,           // 每格边长（毫米）
    val requiredSamples: Int = 20                      // 最少需要的棋盤帧数
) {
    private val sampleCount = AtomicInteger(0)
    private val _progress = AtomicInteger(0)

    val sampleCountValue: Int get() = sampleCount.get()
    val progress: Int get() = _progress.get()
    val isComplete: Boolean get() = sampleCount.get() >= requiredSamples

    // 收集到的角点（图像坐标）
    private val imagePointsList = mutableListOf<MatOfPoint2f>()
    // 对应的 3D 物点（棋盘格在世界坐标系的坐标，Z=0）
    private val objectPointsList = mutableListOf<MatOfPoint3f>()

    private var rejectReason: String? = null

    /**
     * 生成棋盘格固定不变的 3D 物点
     */
    private fun createObjectPoints(): MatOfPoint3f {
        val pts = mutableListOf<Point3>()
        for (row in 0 until patternSize.height.toInt()) {
            for (col in 0 until patternSize.width.toInt()) {
                pts.add(Point3(col * squareSizeMm, row * squareSizeMm, 0.0))
            }
        }
        val mat = MatOfPoint3f()
        mat.fromList(pts)
        return mat
    }

    /** 物点模板（所有帧共用） */
    private val objectPointsTemplate: MatOfPoint3f = createObjectPoints()

    /**
     * 喂入一帧图像进行角点检测
     * @param gray 灰度图
     * @return true 如果成功检测到全量角点
     */
    fun feed(gray: Mat, width: Int, height: Int): Boolean {
        val corners = MatOfPoint2f()
        val found = Calib3d.findChessboardCorners(
            gray, patternSize, corners,
            Calib3d.CALIB_CB_ADAPTIVE_THRESH or Calib3d.CALIB_CB_NORMALIZE_IMAGE or Calib3d.CALIB_CB_FAST_CHECK
        )

        if (!found || corners.toArray().size != patternSize.area().toInt()) {
            corners.release()
            rejectReason = "未检测到棋盘格角点，请确保整张棋盘格清晰可见"
            return false
        }

        // 亚像素精细化
        val criteria = TermCriteria(
            TermCriteria.EPS or TermCriteria.COUNT,
            30, 0.001
        )
        Imgproc.cornerSubPix(gray, corners, Size(11.0, 11.0), Size(-1.0, -1.0), criteria)

        // 验证角点是否覆盖足够区域（避免只在画面一小块）
        val arr = corners.toArray()
        val minX = arr.minOf { it.x }
        val maxX = arr.maxOf { it.x }
        val minY = arr.minOf { it.y }
        val maxY = arr.maxOf { it.y }
        val spanX = maxX - minX
        val spanY = maxY - minY

        if (spanX < width * 0.3 || spanY < height * 0.3) {
            rejectReason = "棋盘格在画面中占比过小，请靠近或放大"
            return false
        }

        // 去重：检查是否与已有样本过于相似（避免重复角度）
        if (imagePointsList.isNotEmpty()) {
            val lastCorners = imagePointsList.last().toArray()
            val avgDist = lastCorners.zip(arr).map { (a, b) ->
                val dx = a.x - b.x
                val dy = a.y - b.y
                kotlin.math.sqrt(dx * dx + dy * dy)
            }.average()
            if (avgDist < 15.0) {
                rejectReason = "画面与上一帧几乎相同，请改变角度或距离"
                return false
            }
        }

        synchronized(this) {
            imagePointsList.add(corners.clone() as MatOfPoint2f)
            objectPointsList.add(objectPointsTemplate.clone() as MatOfPoint3f)
        }
        rejectReason = null
        sampleCount.incrementAndGet()
        _progress.set(sampleCount.get())
        return true
    }

    /** 完成标定，返回 CalibrationData */
    fun finish(width: Int, height: Int): CalibrationData? {
        if (sampleCount.get() < 5) {
            Log.e("CheckerboardCal", "样本不足: ${sampleCount.get()}")
            return null
        }

        val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        val distCoeffs = Mat.zeros(5, 1, CvType.CV_64F)
        val rvecs = mutableListOf<Mat>()
        val tvecs = mutableListOf<Mat>()

        val imgSize = Size(width.toDouble(), height.toDouble())

        val objList: List<Mat> = objectPointsList.toList()
        val imgList: List<Mat> = imagePointsList.toList()

        val rms = Calib3d.calibrateCamera(
            objList, imgList, imgSize,
            cameraMatrix, distCoeffs, rvecs, tvecs
        )

        // 清理
        rvecs.forEach { it.release() }
        tvecs.forEach { it.release() }
        objectPointsList.forEach { it.release() }
        imagePointsList.forEach { it.release() }

        val cm = DoubleArray(9) { cameraMatrix.get(it / 3, it % 3)[0] }
        val dc = DoubleArray(5) { distCoeffs.get(it, 0)[0] }

        cameraMatrix.release()
        distCoeffs.release()

        val calibData = CalibrationData(
            imageWidth = width,
            imageHeight = height,
            cameraMatrix = cm,
            distCoeffs = dc,
            reprojectionError = rms,
            timestamp = System.currentTimeMillis()
        )

        Log.i("CheckerboardCal", "标定完成: RMS=$rms, fx=${cm[0]}, 样本数=${sampleCount.get()}")
        return calibData
    }

    /** 最近一次拒绝原因 */
    fun lastRejectReason(): String? = rejectReason

    /** 获取角点用于可视化 */
    fun getLatestCorners(): MatOfPoint2f? {
        synchronized(this) {
            return if (imagePointsList.isNotEmpty()) imagePointsList.last().clone() else null
        }
    }
}
