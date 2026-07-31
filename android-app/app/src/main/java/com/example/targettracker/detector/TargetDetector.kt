package com.example.targettracker.detector

import android.util.Log
import com.example.targettracker.config.Config
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * 四象限双黑靶标检测器
 *
 * 层次结构:
 *   外黑环 (lvl0) -> 白色象限A/B (lvl1) -> 黑色象限A/B (lvl2)
 *
 * 检测策略:
 *   1. 自适应二值化 + 轮廓查找
 *   2. 对每个外轮廓递归收集后代
 *   3. 验证对角线分布 (两象限夹角 100-260 度)
 *   4. PnP 求解6DOF位姿
 *   5. 按像素面积排序分配ID (大面积=小ID)
 */
class TargetDetector(private val targetConfig: Config.Target = Config.target) {

    companion object {
        private const val TAG = "TargetDetector"
    }

    // 追踪状态: targetId -> { center, lostFrames, sizeMm }
    data class TrackedTarget(
        val center: Point,
        val lostFrames: Int = 0,
        val sizeMm: Double = 200.0
    )

    // 已追踪的靶标
    private val trackedTargets = mutableMapOf<Int, TrackedTarget>()

    // 靶标物理尺寸
    private val defaultSizes = targetConfig.defaultSizesMm
    private val targetSizes = mutableMapOf<Int, Double>()
    private val lostTimeout = targetConfig.lostTimeoutFrames

    /** 切换检测模式 (当前仅支持 quadrant) */
    fun setMode(mode: String) { /* 单模式，保留接口兼容 */ }

    /**
     * 主检测入口
     * @param gray 灰度图像
     * @param cameraMatrix 3x3 相机内参矩阵
     * @param distCoeffs 1x5 畸变系数
     * @return targetId -> DetectionResult 映射
     */
    fun detect(
        gray: Mat,
        cameraMatrix: Mat?,
        distCoeffs: Mat?
    ): Map<Int, DetectionResult> {
        // ──── 预处理 ────
        val blurred = Mat()
        Imgproc.medianBlur(gray, blurred, 5)

        val thresh = Mat()
        Imgproc.adaptiveThreshold(
            blurred, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, 25, 5.0
        )
        blurred.release()

        // ──── 轮廓查找 ────
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            thresh, contours, hierarchy,
            Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE
        )
        thresh.release()

        val candidates = findQuadrantCandidates(gray, contours, hierarchy)
        hierarchy.release()

        // ──── ID 分配 + PnP 求解 ────
        val results = assignAndSolve(candidates, cameraMatrix, distCoeffs)

        // 清理
        contours.forEach { it.release() }

        // ──── 更新追踪缓存 ────
        updateTracking(results)

        return results
    }

    /**
     * 递归收集某个轮廓的所有后代
     */
    private fun collectDescendants(
        contourIdx: Int,
        hierarchy: Mat,
        contours: List<MatOfPoint>,
        depth: Int = 0
    ): List<DescendantInfo> {
        if (depth > 5 || contourIdx < 0 || contourIdx >= hierarchy.rows()) return emptyList()
        val result = mutableListOf<DescendantInfo>()
        var childIdx = hierarchy.get(contourIdx, 2)[0].toInt() // hierarchy[i][2] = first child

        while (childIdx != -1) {
            val childContour = contours[childIdx]
            val area = Imgproc.contourArea(childContour)
            if (area > 20.0) {
                val moments = Imgproc.moments(childContour)
                val cx = if (moments.m00 != 0.0) moments.m10 / moments.m00 else 0.0
                val cy = if (moments.m00 != 0.0) moments.m01 / moments.m00 else 0.0
                result.add(DescendantInfo(childIdx, area, cx, cy, depth))
                result.addAll(collectDescendants(childIdx, hierarchy, contours, depth + 1))
            }
            childIdx = hierarchy.get(childIdx, 0)[0].toInt() // hierarchy[i][0] = next sibling
        }
        return result
    }

    /**
     * 寻找符合四象限特征的候选轮廓
     */
    private fun findQuadrantCandidates(
        gray: Mat,
        contours: List<MatOfPoint>,
        hierarchy: Mat
    ): List<Candidate> {
        if (contours.isEmpty() || hierarchy.empty()) return emptyList()
        val candidates = mutableListOf<Candidate>()
        val imgArea = gray.rows() * gray.cols()

        for (i in contours.indices) {
            val contour = contours[i]
            val area = Imgproc.contourArea(contour)

            // 面积过滤
            if (area < 500.0 || area > imgArea * 0.25) continue

            // 必须至少有一个后代
            val descendants = collectDescendants(i, hierarchy, contours)
            if (descendants.isEmpty()) continue

            // 后代总面积比例
            val totalInner = descendants.sumOf { it.area }
            if (totalInner < area * 0.1 || totalInner > area * 0.95) continue

            // 象限候选 (面积在 1%-45% 外环面积之间)
            var quadrants = descendants.filter { d ->
                d.area > area * 0.01 && d.area < area * 0.45
            }
            var fallbackMode = false

            if (quadrants.size < 2) {
                // 回退: 取面积最大的2个后代
                val sorted = descendants.sortedByDescending { it.area }
                quadrants = sorted.take(2)
                if (quadrants.size < 2) continue
                fallbackMode = true
            } else if (quadrants.size > 2) {
                quadrants = quadrants.sortedByDescending { it.area }.take(2)
            }

            // ──── 对角验证 ────
            val q0 = quadrants[0]
            val q1 = quadrants[1]
            val ocenterX = (q0.cx + q1.cx) / 2.0
            val ocenterY = (q0.cy + q1.cy) / 2.0

            val v1x = q0.cx - ocenterX
            val v1y = q0.cy - ocenterY
            val v2x = q1.cx - ocenterX
            val v2y = q1.cy - ocenterY

            val dist1 = Math.sqrt(v1x * v1x + v1y * v1y)
            val dist2 = Math.sqrt(v2x * v2x + v2y * v2y)
            if (dist1 < 3.0 || dist2 < 3.0) continue

            val cosAngle = ((v1x * v2x + v1y * v2y) / (dist1 * dist2)).coerceIn(-1.0, 1.0)
            val angleDeg = Math.toDegrees(Math.acos(cosAngle))

            val minAngle = if (fallbackMode) 90.0 else 100.0
            if (angleDeg < minAngle) continue

            // 面积比验证
            val areaRatio = (Math.max(q0.area, q1.area) + 1.0) / (Math.min(q0.area, q1.area) + 1.0)
            val maxRatio = if (fallbackMode) 5.0 else 3.5
            if (areaRatio > maxRatio) continue

            // ──── 椭圆拟合 ────
            if (contour.total() < 5) continue
            val contour2f = MatOfPoint2f(*contour.toArray())
            val ellipse = Imgproc.fitEllipse(contour2f)
            contour2f.release()

            val ratio = Math.min(ellipse.size.width, ellipse.size.height) /
                    Math.max(ellipse.size.width, ellipse.size.height)
            val minRatio = if (fallbackMode) 0.5 else 0.7
            if (ratio < minRatio) continue

            // 边缘排除
            val margin = 20.0
            val ex = ellipse.center.x
            val ey = ellipse.center.y
            if (ex < margin || ex > gray.cols() - margin ||
                ey < margin || ey > gray.rows() - margin) continue

            // ──── 质量评分 ────
            val quality = if (fallbackMode) {
                0.3
            } else {
                val angleScore = ((angleDeg - 100.0) / 80.0).coerceIn(0.0, 1.0)
                val consistency = (1.0 - (areaRatio - 1.0) / 2.5).coerceIn(0.0, 1.0)
                (0.4 * ((ratio - 0.7) / 0.3) + 0.3 * angleScore + 0.3 * consistency).coerceIn(0.3, 1.0)
            }

            candidates.add(
                Candidate(
                    contour = contour,
                    ellipse = ellipse,
                    quadCenters = arrayOf(Point(q0.cx, q0.cy), Point(q1.cx, q1.cy)),
                    area = area,
                    quality = quality,
                    ratio = ratio
                )
            )
        }

        return candidates
    }

    /**
     * 按面积排序分配ID + PnP 位姿求解
     */
    private fun assignAndSolve(
        candidates: List<Candidate>,
        cameraMatrix: Mat?,
        distCoeffs: Mat?
    ): Map<Int, DetectionResult> {
        if (candidates.isEmpty()) {
            // 所有已追踪靶标标记为丢失
            trackedTargets.forEach { (id, t) ->
                val newLost = t.lostFrames + 1
                if (newLost < lostTimeout) {
                    trackedTargets[id] = t.copy(lostFrames = newLost)
                }
            }
            trackedTargets.entries.removeAll { it.value.lostFrames >= lostTimeout }
            return emptyMap()
        }

        // ──── 按面积从大到小排序 ────
        val sorted = candidates.sortedByDescending { it.area }

        // ──── 最近邻匹配 ────
        val assignedIds = mutableMapOf<Int, Int>() // sortedIdx -> targetId
        val usedTids = mutableSetOf<Int>()

        for ((si, cand) in sorted.withIndex()) {
            val cc = Point(
                (cand.quadCenters[0].x + cand.quadCenters[1].x) / 2.0,
                (cand.quadCenters[0].y + cand.quadCenters[1].y) / 2.0
            )

            var bestTid: Int? = null
            var bestDist = Double.MAX_VALUE
            for ((tid, t) in trackedTargets) {
                if (tid in usedTids) continue
                val dist = Math.sqrt(
                    (cc.x - t.center.x) * (cc.x - t.center.x) +
                    (cc.y - t.center.y) * (cc.y - t.center.y)
                )
                if (dist < bestDist && dist < targetConfig.minDistancePx) {
                    bestDist = dist
                    bestTid = tid
                }
            }
            if (bestTid != null) {
                assignedIds[si] = bestTid
                usedTids.add(bestTid)
            }
        }

        // ──── 未匹配的按面积排名分配新ID ────
        for ((si, _) in sorted.withIndex()) {
            if (si !in assignedIds) {
                assignedIds[si] = si // 面积最大的 = ID 0
                usedTids.add(si)
            }
        }

        // ──── PnP 求解 ────
        val results = mutableMapOf<Int, DetectionResult>()
        for ((si, tid) in assignedIds) {
            val cand = sorted[si]

            // 靶标物理尺寸
            val sizeMm = if (tid < defaultSizes.size) defaultSizes[tid] else 200.0

            // 构建3D点 (坐标系原点在靶标圆心, XY平面为靶标平面)
            val objectPoints = MatOfPoint3f(
                Point3(-sizeMm / 2.0, -sizeMm / 2.0, 0.0),
                Point3( sizeMm / 2.0, -sizeMm / 2.0, 0.0),
                Point3( sizeMm / 2.0,  sizeMm / 2.0, 0.0),
                Point3(-sizeMm / 2.0,  sizeMm / 2.0, 0.0)
            )

            // 2D 图像点 (椭圆外接矩形四点, 近似)
            val cxEl = cand.ellipse.center.x
            val cyEl = cand.ellipse.center.y
            val halfW = cand.ellipse.size.width / 2.0
            val halfH = cand.ellipse.size.height / 2.0
            val angleRad = Math.toRadians(cand.ellipse.angle.toDouble())

            val cosA = Math.cos(angleRad)
            val sinA = Math.sin(angleRad)

            // 旋转后的四个角点
            fun rotatePoint(dx: Double, dy: Double): Point {
                return Point(
                    cxEl + dx * cosA - dy * sinA,
                    cyEl + dx * sinA + dy * cosA
                )
            }

            val imagePoints = MatOfPoint2f(
                rotatePoint(-halfW, -halfH),
                rotatePoint( halfW, -halfH),
                rotatePoint( halfW,  halfH),
                rotatePoint(-halfW,  halfH)
            )

            // PnP 求解
            if (cameraMatrix != null && !cameraMatrix.empty()) {
                val rvec = Mat()
                val tvec = Mat()
                val d = MatOfDouble(distCoeffs ?: Mat.zeros(5, 1, CvType.CV_64F))

                try {
                    Calib3d.solvePnP(objectPoints, imagePoints, cameraMatrix, d, rvec, tvec)
                    results[tid] = DetectionResult(
                        success = true,
                        targetId = tid,
                        center = Point(cxEl, cyEl),
                        ellipse = cand.ellipse,
                        rvec = rvec.clone(),
                        tvec = tvec.clone(),
                        quality = cand.quality
                    )
                    rvec.release(); tvec.release()
                } catch (e: Exception) {
                    Log.w(TAG, "PnP failed for target $tid: ${e.message}")
                    results[tid] = pinholeFallback(tid, cxEl, cyEl, halfW, halfH, sizeMm, cameraMatrix, cand)
                }
            } else {
                results[tid] = pinholeFallback(tid, cxEl, cyEl, halfW, halfH, sizeMm, cameraMatrix, cand)
            }

            objectPoints.release(); imagePoints.release()
        }

        return results
    }

    /**
     * PnP 失败时的 pinhole 回退
     */
    private fun pinholeFallback(
        tid: Int, cx: Double, cy: Double,
        halfW: Double, halfH: Double,
        sizeMm: Double, cameraMatrix: Mat?, cand: Candidate
    ): DetectionResult {
        val fx = if (cameraMatrix != null && !cameraMatrix.empty())
            cameraMatrix.get(0, 0)[0] else 800.0
        val fy = if (cameraMatrix != null && !cameraMatrix.empty())
            cameraMatrix.get(1, 1)[0] else 800.0
        val cx0 = if (cameraMatrix != null && !cameraMatrix.empty())
            cameraMatrix.get(0, 2)[0] else 640.0
        val cy0 = if (cameraMatrix != null && !cameraMatrix.empty())
            cameraMatrix.get(1, 2)[0] else 360.0

        val pixelDiam = (halfW + halfH)
        val z = if (pixelDiam > 1.0) ((fx + fy) / 2.0 * sizeMm) / pixelDiam else sizeMm * 10.0
        val x = (cx - cx0) * z / fx
        val y = (cy - cy0) * z / fy

        val tvec = Mat(3, 1, CvType.CV_64F)
        tvec.put(0, 0, x, y, z)

        return DetectionResult(
            success = true, targetId = tid,
            center = Point(cx, cy), ellipse = cand.ellipse,
            rvec = Mat.zeros(3, 1, CvType.CV_64F),
            tvec = tvec, quality = cand.quality * 0.8
        )
    }

    /** 更新追踪状态 */
    private fun updateTracking(results: Map<Int, DetectionResult>) {
        val detected = results.values.filter { it.success }
        val allTids = results.keys.toSet()

        // 更新已检测到的
        for (r in detected) {
            trackedTargets[r.targetId] = TrackedTarget(r.center, 0)
        }

        // 标记丢失
        for ((tid, t) in trackedTargets.toMap()) {
            if (tid !in allTids) {
                val lost = t.lostFrames + 1
                if (lost >= lostTimeout) {
                    trackedTargets.remove(tid)
                } else {
                    trackedTargets[tid] = t.copy(lostFrames = lost)
                }
            }
        }
    }

    /** 设置靶标物理尺寸 */
    fun setTargetSize(targetId: Int, sizeMm: Double) {
        targetSizes[targetId] = sizeMm
    }

    // ──── 内部数据类 ────
    data class DescendantInfo(
        val idx: Int,
        val area: Double,
        val cx: Double,
        val cy: Double,
        val depth: Int
    )

    data class Candidate(
        val contour: MatOfPoint,
        val ellipse: RotatedRect,
        val quadCenters: Array<Point>,
        val area: Double,
        val quality: Double,
        val ratio: Double
    )
}
