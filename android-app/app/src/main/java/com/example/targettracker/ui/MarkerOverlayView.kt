package com.example.targettracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.targettracker.detector.DetectionResult
import com.example.targettracker.TargetTrackerState

/**
 * 在相机预览上叠绘制的靶标标注 (椭圆外框 + 4 个象限质心 + ID + 中心十字).
 *
 * 坐标系: image (gray) -> previewView (FILL_CENTER), 计算居中裁剪缩放矩阵.
 *   scale = max(viewW / imgW, viewH / imgH)
 *   dx = (viewW - imgW*scale) / 2, dy = (viewH - imgH*scale) / 2
 */
class MarkerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var state: TargetTrackerState? = null

    /** 由主线程/Compose update 调用: 传入最新快照值并触发重绘 */
    fun updateState(
        s: TargetTrackerState,
        detectResults: Map<Int, DetectionResult>,
        imageSize: Pair<Int, Int>
    ) {
        state = s
        invalidate()
    }

    private val ellipsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#FF00E676") // 绿色
    }
    private val quadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFF5252") // 红色
    }
    private val centerCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FF40C4FF") // 青色
    }
    private val idPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFFFF")
        textSize = 38f
        isFakeBoldText = true
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#88000000")
    }
    private val refRect = RectF()

    /** 由外部 (MainScreen) 在 update 块中调用, 触发重绘 */
    fun refresh() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state ?: return
        val results = s.detectResults
        if (results.isEmpty()) return
        val (imgW, imgH) = s.imageSize
        if (imgW <= 0 || imgH <= 0) return

        // 计算 FILL_CENTER 缩放矩阵 (全部用 Double, 最后 .toFloat())
        val viewW = width.toDouble()
        val viewH = height.toDouble()
        val imgWd: Double = imgW.toDouble()
        val imgHd: Double = imgH.toDouble()
        val scale = kotlin.math.max(viewW / imgWd, viewH / imgHd)
        val offsetX = (viewW - imgWd * scale) / 2.0
        val offsetY = (viewH - imgHd * scale) / 2.0

        // 像素偏移(去除坐标原点偏差, 让 View 坐标系与图像坐标系对齐)
        fun mapX(x: Double): Float = (offsetX + x * scale).toFloat()
        fun mapY(y: Double): Float = (offsetY + y * scale).toFloat()

        for ((tid, det) in results) {
            val e = det.ellipse ?: continue
            // 用 Java getter 显式访问, 避免 Kotlin 与 stdlib.size/center 冲突
            val eCenter = e.center
            val eSize = e.size
            val cx: Float = mapX(eCenter.x)
            val cy: Float = mapY(eCenter.y)
            val halfW: Float = ((eSize.width / 2.0) * scale).toFloat()
            val halfH: Float = ((eSize.height / 2.0) * scale).toFloat()
            // RotatedRect -> 旋转矩形 (注意 OpenCV 角度方向)
            refRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            canvas.save()
            canvas.rotate(e.angle.toFloat(), cx, cy)
            canvas.drawOval(refRect, ellipsePaint)
            canvas.restore()

            // 4 个象限质心点
            val corners: List<org.opencv.core.Point>? = det.corners
            corners?.forEach { p ->
                canvas.drawCircle(mapX(p.x), mapY(p.y), 8f, quadPaint)
            }

            // 中心十字
            val cs = 22f
            canvas.drawLine(cx - cs, cy, cx + cs, cy, centerCrossPaint)
            canvas.drawLine(cx, cy - cs, cx, cy + cs, centerCrossPaint)

            // ID 标签
            val label = "T$tid"
            val pad = 10f
            val tw = idPaint.measureText(label)
            val labelY = cy - halfH - 22f
            canvas.drawRoundRect(
                cx - tw / 2f - pad, cy - halfH - 50f,
                cx + tw / 2f + pad, cy - halfH - 12f,
                8f, 8f, labelBgPaint
            )
            canvas.drawText(label, cx - tw / 2f, labelY, idPaint)
        }
    }
}