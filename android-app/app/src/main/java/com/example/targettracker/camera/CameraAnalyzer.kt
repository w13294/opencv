package com.example.targettracker.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/**
 * CameraX ImageAnalyzer — 将 YUV 帧转为 OpenCV Mat (灰度)
 * 并通过回调将帧数据传递给检测流水线
 */
class CameraAnalyzer(
    private val onFrameAvailable: (Mat, Long) -> Unit
) : ImageAnalysis.Analyzer {

    // 预分配灰度 Mat，避免每帧重新分配
    private var grayMat: Mat? = null
    private val rotationMatrix = Mat()

    override fun analyze(image: ImageProxy) {
        val startNs = System.nanoTime()

        try {
            val mat = imageToMat(image) ?: return
            onFrameAvailable(mat, startNs)
        } finally {
            image.close()
        }
    }

    /**
     * ImageProxy (YUV_420_888) → 灰度 Mat
     */
    private fun imageToMat(image: ImageProxy): Mat? {
        val width = image.width
        val height = image.height

        // 确保 Mat 大小正确
        if (grayMat == null || grayMat!!.rows() != height || grayMat!!.cols() != width) {
            grayMat?.release()
            grayMat = Mat(height, width, CvType.CV_8UC1)
        }

        // YUV → RGB → Gray
        val planes = image.planes
        if (planes.isEmpty()) return null

        val yBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride

        if (pixelStride == 1) {
            // Y平面连续 (常见情况)
            val data = yuvTo1DArray(yBuffer, rowStride, height, width)
            grayMat!!.put(0, 0, data)
        } else {
            // 需要逐行拷贝
            yBuffer.position(0)
            val rowData = ByteArray(width)
            for (row in 0 until height) {
                yBuffer.position(row * rowStride)
                yBuffer.get(rowData, 0, width)
                grayMat!!.put(row, 0, rowData)
            }
        }

        // 转置/镜像 (适配 CameraX 坐标系, landscape 通常不需要旋转)
        // 如果需要旋转: Imgproc.rotate(gray!, gray!, rotateCode)

        return grayMat
    }

    private fun yuvTo1DArray(buffer: ByteBuffer, rowStride: Int, height: Int, width: Int): ByteArray {
        val result = ByteArray(height * width)
        buffer.position(0)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(result, row * width, width)
        }
        return result
    }

    fun release() {
        grayMat?.release()
        rotationMatrix.release()
    }
}
