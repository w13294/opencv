package com.example.targettracker.detector

import android.util.Log
import com.example.targettracker.config.Config
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * 四象限靶标检测器 (V3 - 基于 RETR_TREE)
 *
 * 靶标真实几何 (实拍图分析):
 *   1. 黑色实心外圆 (半径 R)
 *   2. 内部白色实心圆 (半径 ~0.8R) 清空中间
 *   3. 内圆被 X 形白十字臂分成 4 个对角黑色三角形象限
 *      (上/下/左/右 4 个 90° 扇形, OpenCV 角度: 0/90/180/270)
 *
 * 二值化后 (THRESH_OTSU, 黑色=255, 白色=0):
 *   - 外黑环是 1 个连通黑区
 *   - 内部 4 个黑象限被白色 X 形十字隔开, 形成 4 个独立黑区
 *   - 用 RETR_TREE: 外环 [第N层] -> 内部白洞 [N+1] -> 4 个黑象限 [N+2]
 *
 * 检测策略:
 *   1. Otsu 二值化 (不取反, 黑=255)
 *   2. RETR_TREE 找所有轮廓
 *   3. 遍历所有"无父 + 圆形度>0.7"的外环候选
 *   4. 找其"孙"轮廓 (内部白洞的子) = 4 个黑色象限
 *   5. 验证: 4 个象限面积相近 + 中心分布在 4 个方向 (45°~135° 间隔)
 *   6. PnP 求解
 */
class TargetDetector(private val targetConfig: Config.Target = Config.target) {

    companion object {
        private const val TAG = "TargetDetector"
    }

    // 追踪状态
    data class TrackedTarget(
        val center: Point,
        val lostFrames: Int = 0,
        val sizeMm: Double = 200.0
    )

    private val trackedTargets = mutableMapOf<Int, TrackedTarget>()
    private val defaultSizes = targetConfig.defaultSizesMm
    private val targetSizes = mutableMapOf<Int, Double>()
    private val lostTimeout = targetConfig.lostTimeoutFrames

    fun setMode(mode: String) { /* 单模式 */ }

    /**
     * 主检测入口
     *
     * @param gray YUV420 的 Y 平面 Mat (CV_8UC1), 用于 findContours/fitEllipse/PnP 等 OpenCV 操作
     * @param grayData 同一份灰度数据的 ByteArray (CV_8UC1 连续), 用于象限灰度统计 (跳过 JNI 提速)
     * @param width gray 宽
     * @param height gray 高
     */
    fun detect(
        gray: Mat,
        grayData: ByteArray,
        width: Int,
        height: Int,
        cameraMatrix: Mat?,
        distCoeffs: Mat?
    ): Map<Int, DetectionResult> {
        try {
        // ──── 预处理 ────
        val blurred = Mat()
        Imgproc.medianBlur(gray, blurred, 5)

        // Otsu 二值化 (不取反: 黑色靶区域 = 255)
        val thresh = Mat()
        Imgproc.threshold(
            blurred, thresh, 0.0, 255.0,
            Imgproc.THRESH_OTSU  // 黑=0, 白=255
        )
        blurred.release()

        // 形态学闭运算: 连接黑环细小缝隙
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(thresh, closed, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()
        thresh.release()

        // ──── 轮廓查找 (RETR_LIST 拿全部独立轮廓) ────
        // 外环 + 内部 4 块黑象限 + 噪声 = 6+ 个独立轮廓
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            closed, contours, hierarchy,
            Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
        )
        closed.release()

        val candidates = findQuadrantCandidates(contours, grayData, width, height)
        hierarchy.release()
        contours.forEach { it.release() }

        // ──── ID 分配 + PnP 求解 ────
        val results = assignAndSolve(candidates, cameraMatrix, distCoeffs)

        updateTracking(results)
        return results
        } catch (e: Exception) {
            Log.e(TAG, "detect() EXCEPTION: ${e.message}")
            return emptyMap()
        }
    }

    /**
     * 寻找符合"外环 + 内部对角黑色象限"特征的候选
     *
     * 外环: 用 RETR_LIST 轮廓 + fitEllipse 找圆形外环
     * 象限判定: 在灰度图 (ByteArray) 上对 4 个方向 (0/90/180/270) 的环形扇区统计平均灰度.
     *   黑象限 = 平均灰度低(暗), 白象限 = 高(亮). 不依赖二值化内部轮廓分离, 抗抖动强.
     *   使用 ByteArray 直接索引 (而非 gray.get(y,x) JNI), 帧率提升显著.
     */
    private fun findQuadrantCandidates(
        contours: List<MatOfPoint>,
        grayData: ByteArray,
        width: Int,
        height: Int
    ): List<Candidate> {
        if (contours.isEmpty()) return emptyList()

        // 统计所有轮廓的面积和位置
        val n = contours.size
        val areas = DoubleArray(n)
        val centroids = Array(n) { Point() }
        val validContour = BooleanArray(n)

        for (i in 0 until n) {
            areas[i] = Imgproc.contourArea(contours[i])
            val bb = Imgproc.boundingRect(contours[i])
            centroids[i] = Point(bb.x + bb.width / 2.0, bb.y + bb.height / 2.0)
            validContour[i] = areas[i] > 100
        }

        // 收集外环候选 (圆形度高 + 面积较大)
        val outerCandidates = mutableListOf<OuterCandidate>()
        for (i in 0 until n) {
            if (!validContour[i]) continue
            if (contours[i].total() < 5) continue
            val contour2f = MatOfPoint2f(*contours[i].toArray())
            val ellipse = try {
                Imgproc.fitEllipse(contour2f)
            } catch (e: Exception) {
                contour2f.release()
                continue
            }
            contour2f.release()

            val w = ellipse.size.width
            val h = ellipse.size.height
            val ratio = Math.min(w, h) / Math.max(w, h)
            if (ratio < 0.5) continue

            val center = ellipse.center
            val radius = (w + h) / 4.0
            outerCandidates.add(OuterCandidate(i, ellipse, center, radius, ratio, 0.0, areas[i]))
        }
        outerCandidates.sortByDescending { it.area }

        if (outerCandidates.isEmpty()) return emptyList()

        // 4 个象限中心方向角度 (0=右,90=下,180=左,270=上)
        // 4 对角方向 (screen coord atan2): 右上=315°, 右下=45°, 左下=135°, 左上=225°
        // 黑象限: 右上(270-360) + 左下(90-180) → dirAngles[0]=右上 + dirAngles[2]=左下 应是黑
        // 即 isBlack[0]=T, isBlack[2]=T, isBlack[1]=F, isBlack[3]=F → 0+2 对角同色=黑
        val dirAngles = doubleArrayOf(315.0, 45.0, 135.0, 225.0)
        val rInFrac = 0.30
        val rOutFrac = 0.70
        val sectorHalfDeg = 50.0

        val candidates = mutableListOf<Candidate>()
        for (oc in outerCandidates) {
            val cx = oc.center.x
            val cy = oc.center.y
            val R = oc.radius
            val rIn = rInFrac * R
            val rOut = rOutFrac * R

            val sums = DoubleArray(4)
            val counts = IntArray(4)

            val x0 = Math.max(0, Math.floor(cx - rOut).toInt())
            val x1 = Math.min(width - 1, Math.ceil(cx + rOut).toInt())
            val y0 = Math.max(0, Math.floor(cy - rOut).toInt())
            val y1 = Math.min(height - 1, Math.ceil(cy + rOut).toInt())
            val rOut2 = rOut * rOut
            val rIn2 = rIn * rIn

            // 直接读 ByteArray (CV_8UC1 连续), 无 JNI 开销
            for (y in y0..y1) {
                val rowBase = y * width
                val dy = y - cy
                val dy2 = dy * dy
                if (dy2 > rOut2) continue
                for (x in x0..x1) {
                    val dx = x - cx
                    val dist2 = dx * dx + dy2
                    if (dist2 < rIn2 || dist2 > rOut2) continue
                    val ang = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
                    var bestDir = -1
                    var bestDiff = Double.MAX_VALUE
                    for (d in 0..3) {
                        // 归一化角度差到 [0, 180]
                        var diff = Math.abs(ang - dirAngles[d]) % 360.0
                        if (diff > 180) diff = 360 - diff
                        if (diff <= sectorHalfDeg && diff < bestDiff) {
                            bestDiff = diff
                            bestDir = d
                        }
                    }
                    if (bestDir < 0) continue
                    val v = grayData[rowBase + x].toInt() and 0xFF
                    sums[bestDir] = sums[bestDir] + v
                    counts[bestDir] = counts[bestDir] + 1
                }
            }

            var valid = true
            val quadMean = DoubleArray(4)
            for (d in 0..3) {
                if (counts[d] == 0) { valid = false; break }
                quadMean[d] = sums[d] / counts[d]
            }
            if (!valid) continue

            val minMean = quadMean.minOrNull()!!
            val maxMean = quadMean.maxOrNull()!!
            val contrast = maxMean - minMean
            // 调试: 打印扇区灰度 (临时)
            if (oc.area > 50) {
                // Log.d(TAG, "  cx=($cx,$cy) R=$R area=${oc.area.toInt()} quadMean=[${quadMean[0].toInt()},${quadMean[1].toInt()},${quadMean[2].toInt()},${quadMean[3].toInt()}] contrast=${contrast.toInt()}")
            }
            // 黑白对比必须显著 (过低视为背景噪声)
            if (contrast < 25.0) continue

            // 灰度低 = 暗 = 黑象限; 灰度高 = 亮 = 白象限
            val thr = (minMean + maxMean) / 2.0
            val isBlack = BooleanArray(4) { quadMean[it] <= thr }
            val blackCount = isBlack.count { it }
            // 必须 2 个黑 + 2 个白 (标准 quadrant target)
            if (blackCount != 2) continue
            // 必须对角: 1+3 或 0+2 异或 (相邻黑=假阳性)
            val diag01 = isBlack[0] != isBlack[1]
            val diag02 = isBlack[0] == isBlack[2]
            val diag13 = isBlack[1] == isBlack[3]
            if (!diag01 || !diag02 || !diag13) continue
            // 黑白对比强度比
            val blackMean = (quadMean[if (isBlack[0]) 0 else 1] + quadMean[if (isBlack[2]) 2 else 3]) / 2.0
            val whiteMean = (quadMean[if (isBlack[0]) 1 else 0] + quadMean[if (isBlack[2]) 3 else 2]) / 2.0
            val bwRatio = (whiteMean - blackMean) / Math.max(whiteMean, 1.0)
            if (bwRatio < 0.35) continue

            val quadCenters = Array(4) { idx ->
                val ang = dirAngles[idx] * Math.PI / 180.0
                Point(cx + 0.6 * R * Math.cos(ang), cy + 0.6 * R * Math.sin(ang))
            }

            val quality = contrast / 255.0 * 0.6 + blackCount / 4.0 * 0.4
            candidates.add(Candidate(oc.ellipse, quadCenters, oc.area, quality, oc.ratio))
        }

        // 去重 (NMS): 椭圆中心距离 < 0.7 倍的较大半径 视为同一靶标
        val deduped = mutableListOf<Candidate>()
        for (c in candidates.sortedByDescending { it.area }) {
            val isDup = deduped.any { existing ->
                val dx = c.ellipse.center.x - existing.ellipse.center.x
                val dy = c.ellipse.center.y - existing.ellipse.center.y
                val dist = Math.sqrt(dx * dx + dy * dy)
                val cR = (c.ellipse.size.width + c.ellipse.size.height) / 4.0
                val exR = (existing.ellipse.size.width + existing.ellipse.size.height) / 4.0
                dist < Math.max(cR, exR) * 0.7
            }
            if (!isDup) deduped.add(c)
        }

        if (deduped.isEmpty() && outerCandidates.isNotEmpty()) {
            Log.w(TAG, "No valid candidates (outer=${outerCandidates.size})")
        }
        return deduped
    }

    private data class OuterCandidate(
        val idx: Int,
        val ellipse: RotatedRect,
        val center: Point,
        val radius: Double,
        val ratio: Double,
        val theoArea: Double,
        val area: Double
    )

    /**
     * 按面积排序分配 ID + PnP 位姿求解
     */
    private fun assignAndSolve(
        candidates: List<Candidate>,
        cameraMatrix: Mat?,
        distCoeffs: Mat?
    ): Map<Int, DetectionResult> {
        if (candidates.isEmpty()) {
            trackedTargets.forEach { (id, t) ->
                val newLost = t.lostFrames + 1
                if (newLost < lostTimeout) {
                    trackedTargets[id] = t.copy(lostFrames = newLost)
                }
            }
            trackedTargets.entries.removeAll { it.value.lostFrames >= lostTimeout }
            return emptyMap()
        }

        val sorted = candidates.sortedByDescending { it.area }

        // 最近邻匹配
        val assignedIds = mutableMapOf<Int, Int>()
        val usedTids = mutableSetOf<Int>()

        for ((si, cand) in sorted.withIndex()) {
            val cc = Point(cand.ellipse.center.x, cand.ellipse.center.y)
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

        // 未匹配的分配新 ID: 取最小未占用 tid, 避免错位 (旧版用 sorted 索引当 ID, 导致 T0+T3 → T1)
        var nextFreeTid = 0
        for ((si, _) in sorted.withIndex()) {
            if (si !in assignedIds) {
                // 跳过已被占用的 id
                while (nextFreeTid in usedTids) nextFreeTid++
                assignedIds[si] = nextFreeTid
                usedTids.add(nextFreeTid)
                nextFreeTid++
            }
        }

        // PnP 求解
        val results = mutableMapOf<Int, DetectionResult>()
        for ((si, tid) in assignedIds) {
            val cand = sorted[si]
            val sizeMm = if (tid < defaultSizes.size) defaultSizes[tid] else 200.0

            val objectPoints = MatOfPoint3f(
                Point3(-sizeMm / 2.0, -sizeMm / 2.0, 0.0),
                Point3( sizeMm / 2.0, -sizeMm / 2.0, 0.0),
                Point3( sizeMm / 2.0,  sizeMm / 2.0, 0.0),
                Point3(-sizeMm / 2.0,  sizeMm / 2.0, 0.0)
            )

            val cxEl = cand.ellipse.center.x
            val cyEl = cand.ellipse.center.y
            val halfW = cand.ellipse.size.width / 2.0
            val halfH = cand.ellipse.size.height / 2.0
            val angleRad = Math.toRadians(cand.ellipse.angle.toDouble())

            val cosA = Math.cos(angleRad)
            val sinA = Math.sin(angleRad)

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
                        corners = cand.quadCenters.toList(),
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
            corners = cand.quadCenters.toList(),
            rvec = Mat.zeros(3, 1, CvType.CV_64F),
            tvec = tvec, quality = cand.quality * 0.8
        )
    }

    private fun updateTracking(results: Map<Int, DetectionResult>) {
        val detected = results.values.filter { it.success }
        val allTids = results.keys.toSet()

        for (r in detected) {
            trackedTargets[r.targetId] = TrackedTarget(r.center, 0)
        }

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

    fun setTargetSize(targetId: Int, sizeMm: Double) {
        targetSizes[targetId] = sizeMm
    }

    data class Candidate(
        val ellipse: RotatedRect,
        val quadCenters: Array<Point>,
        val area: Double,
        val quality: Double,
        val ratio: Double
    )
}
