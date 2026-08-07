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
        private const val EMA_ALPHA = 0.65  // EMA 平滑因子: 越小越稳定，越大越灵敏
    }

    // 追踪状态
    data class TrackedTarget(
        val center: Point,               // 原始中心, 用于帧间匹配
        val lostFrames: Int = 0,
        val sizeMm: Double = 200.0,
        val lastResult: DetectionResult? = null,
        // ── EMA 平滑状态 (叠加到 lastResult.center/ellipse/tvec) ──
        val smoothCenter: Point = center,
        val smoothEllipseW: Double = 0.0,
        val smoothEllipseH: Double = 0.0,
        val smoothTx: Double = 0.0,
        val smoothTy: Double = 0.0,
        val smoothTz: Double = 0.0
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
        // ──── 分辨率自适应参数缩放 ────
        val pixelCount = width.toDouble() * height.toDouble()
        val basePixels = 640.0 * 480.0
        val resScale = kotlin.math.sqrt(pixelCount / basePixels).coerceIn(0.5, 3.0)

        // 高斯模糊核: 必须奇数, ≥3
        val blurSize = (3.0 * resScale).toInt().let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(3)
        // 自适应阈值邻域大小: 必须奇数, ≥5
        val blockSize = (7.0 * resScale).toInt().let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(5)
        val cVal = (3.0 * resScale).coerceAtLeast(1.0)

        // 1) 高斯模糊
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(blurSize.toDouble(), blurSize.toDouble()), 0.0)

        // 1.5) 光照归一化 (CLAHE): 抑制强光/阴影带来的局部对比度失真,
        //      让后续二值化对光照变化更鲁棒, 减少假象限/漏真象限
        val clahe = Imgproc.createCLAHE(3.0, Size(16.0, 16.0))
        val normalized = Mat()
        clahe.apply(blurred, normalized)

        // 2) 自适应阈值 (GAUSSIAN_C). 仅用局部自适应即可区分黑环/白内圆。
        //    原先用 Otsu 全局阈值做 bitwise_or, 在背景偏暗时会把大块背景判成
        //    前景(255), 导致前景占比高达 65%、靶标结构被噪声淹没而检测不到。
        val thresh = Mat()
        Imgproc.adaptiveThreshold(
            normalized, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            blockSize, cVal
        )
        normalized.release()
        blurred.release()

        // ──── 轮廓查找 ────
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            thresh, contours, hierarchy,
            Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE
        )
        thresh.release()

        val candidates = findQuadrantCandidates(contours, hierarchy, grayData, width, height)
        hierarchy.release()
        contours.forEach { it.release() }

        if (candidates.isEmpty()) {
            // 无新检测: 返回已追踪目标的持久化结果 (帧间保持可见)
            updateTracking(emptyMap())
            val persistingResults = mutableMapOf<Int, DetectionResult>()
            for ((id, t) in trackedTargets) {
                t.lastResult?.let { persistingResults[id] = it }
            }
            return persistingResults
        }

        // ──── ID 分配 + PnP 求解 ────
        val results = assignAndSolve(candidates, cameraMatrix, distCoeffs, resScale)

        updateTracking(results)

        // 返回 EMA 平滑后的结果 (从 trackedTargets.lastResult 取出, 而非原始 results)
        val smoothedResults = mutableMapOf<Int, DetectionResult>()
        for ((tid, t) in trackedTargets) {
            t.lastResult?.let { smoothedResults[tid] = it }
        }
        return smoothedResults
        } catch (e: Exception) {
            Log.e(TAG, "detect() EXCEPTION: ${e.message}", e)
            return emptyMap()
        }
    }

    /**
     * 寻找符合"外环 + 内部对角黑色象限"特征的候选
     *
     * 严格对齐 Windows 端 detector.py 的 detect() 流程:
     *  1) RETR_TREE 递归后代收集 (collect_descendants)
     *  2) 按面积比筛选象限候选 (1%~45% 外环面积)
     *  3) 取面积前 2 的象限 -> 对角夹角验证 (>=100° / fallback 90°)
     *  4) 外环椭圆拟合 + 圆度 + 象限面积比一致性
     *  5) 质量评分 (angle/area/ratio 加权)
     */
    private var debugFrameCount = 0
    private var debugLastLogTime = 0L
    private var debugLastLogTime2 = 0L
    private var diagLastLogTime = 0L

    private fun findQuadrantCandidates(
        contours: List<MatOfPoint>,
        hierarchy: Mat,
        grayData: ByteArray,
        width: Int,
        height: Int
    ): List<Candidate> {
        if (contours.isEmpty()) {
            Log.w(TAG, "DEBUG: 0 contours found!")
            return emptyList()
        }

        val n = contours.size
        val wD = width.toDouble()
        val hD = height.toDouble()
        val hCols = hierarchy.cols()
        val hRows = hierarchy.rows()
        val hType = hierarchy.type()

        // 每15秒打印一次摘要 (降低日志量)
        val now = System.currentTimeMillis()
        if (now - debugLastLogTime > 15000) {
            debugLastLogTime = now
            Log.i(TAG, "Detector info: res=${width}x${height} contours=$n")
        }

        // 分辨率自适应面积缩放 (必须在 collectDescendants 之前定义)
        val areaScale = (wD * hD) / (640.0 * 480.0)
        val minArea = (500.0 * areaScale).coerceAtLeast(300.0)
        val minChildArea = (20.0 * areaScale).coerceAtLeast(10.0)

        // 递归收集某个轮廓的所有后代 (深度 <= 5)
        // 关键: OpenCV Android hierarchy 返回 CV_32SC4 (4通道int32), 必须用 int[] 读取!
        // 每个轮廓的 [next,prev,child,parent] 是同一列的4个通道
        fun collectDescendants(parentIdx: Int, depth: Int = 0): List<Map<String, Any>> {
            if (depth > 5) return emptyList()
            val result = mutableListOf<Map<String, Any>>()
            val hData = IntArray(4)  // 必须 int[]: hierarchy 深度是 CV_32S
            hierarchy.get(0, parentIdx, hData)
            var child = hData[2]   // hierarchy[parentIdx][2] = first child
            while (child >= 0) {
                if (child >= n) break
                val childArea = Imgproc.contourArea(contours[child])
                if (childArea > minChildArea) {  // 对齐 Python: minChildArea 按分辨率缩放
                    val M = Imgproc.moments(contours[child], false)
                    if (M.m00 != 0.0) {
                        result.add(mapOf(
                            "idx" to child,
                            "area" to childArea,
                            "cx" to (M.m10 / M.m00),
                            "cy" to (M.m01 / M.m00),
                            "depth" to depth
                        ))
                    }
                }
                // 递归收集孙子轮廓
                result.addAll(collectDescendants(child, depth + 1))
                hierarchy.get(0, child, hData)
                child = hData[0]   // hierarchy[child][0] = next sibling
            }
            return result
        }

        var cntPassArea = 0
        var cntPassDesc = 0
        var cntPassInnerArea = 0
        var cntPassQuad = 0
        var cntPassAngle = 0
        var cntPassAreaRatio = 0
        var cntPassEllipse = 0
        var cntPassEdge = 0

        val candidates = mutableListOf<Candidate>()
        val outerH = IntArray(4)
        for (i in 0 until n) {
            // [对齐 Windows detector.py] 只处理最外层轮廓 (parent == -1),
            // 跳过有父轮廓的 (如靶标的白内圆), 否则内圆会被当成外环再生成一个候选 → 误识别(T1)
            hierarchy.get(0, i, outerH)
            if (outerH[3] != -1) continue

            val area = Imgproc.contourArea(contours[i])
            // 面积过滤: 太小是噪声, 太大是背景/边缘 (对齐 Python: area < 500 且按分辨率缩放)
            if (area < minArea || area > wD * hD * 0.25) continue
            cntPassArea++

            // 必须有至少一个后代轮廓 (外环内部的子结构)
            val allDescendants = collectDescendants(i)
            if (allDescendants.isEmpty()) continue
            cntPassDesc++

            // 后代总面积应占外环的合理比例
            val totalInnerArea = allDescendants.sumOf { it["area"] as Double }
            if (totalInnerArea < area * 0.1 || totalInnerArea > area * 0.95) continue
            cntPassInnerArea++

            // ──── 调试: 打印通过前三层过滤的候选详情 (仅每15秒) ────
            val nowDetail = System.currentTimeMillis()
            if (nowDetail - debugLastLogTime2 > 15000) {
                val minArea = area * 0.01; val maxArea = area * 0.45
                val descInfo = allDescendants.joinToString("; ") {
                    "d${it["idx"]}@d${it["depth"]}(A=${"%.0f".format(it["area"] as Double)},[minA=%.0f,maxA=%.0f])".format(minArea, maxArea)
                }
                Log.i(TAG, "DEBUG quad-chk: outer#${i} area=%.0f, totalInner=%.0f ratio=%.2f; ${allDescendants.size} desc: $descInfo"
                    .format(area, totalInnerArea, totalInnerArea / area))
            }

            // 筛选象限候选: 面积在外环 1%~45% 之间的后代
            var quadrants = allDescendants.filter {
                val a = it["area"] as Double
                a > area * 0.01 && a < area * 0.45
            }

            var fallbackMode = false
            var mergedQuadrant = false  // 单后代模式 (低分辨率下象限合并)
            if (quadrants.size < 2) {
                val sorted = allDescendants.sortedByDescending { it["area"] as Double }
                if (sorted.size >= 2) {
                    quadrants = sorted.take(2)
                    fallbackMode = true
                } else if (sorted.size == 1) {
                    // 单后代: 象限因分辨率限制而合并，椭圆中心验证兜底
                    quadrants = sorted  // 1元素
                    fallbackMode = true
                    mergedQuadrant = true
                } else {
                    continue
                }
            } else if (quadrants.size > 2) {
                quadrants = quadrants.sortedByDescending { it["area"] as Double }.take(2)
            }
            cntPassQuad++

            val cx1 = quadrants[0]["cx"] as Double
            val cy1 = quadrants[0]["cy"] as Double
            val cx2: Double
            val cy2: Double
            if (mergedQuadrant) {
                cx2 = cx1; cy2 = cy1  // 单后代无第二象限
            } else {
                cx2 = quadrants[1]["cx"] as Double
                cy2 = quadrants[1]["cy"] as Double
            }

            // ──── 对角验证 ────
            val angleDeg: Double
            val areaRatioQuad: Double
            if (mergedQuadrant) {
                angleDeg = 180.0; areaRatioQuad = 1.0  // 跳过角度/面积验证
            } else {
                val oCx = (cx1 + cx2) / 2.0
                val oCy = (cy1 + cy2) / 2.0
                val v1x = cx1 - oCx; val v1y = cy1 - oCy
                val v2x = cx2 - oCx; val v2y = cy2 - oCy
                val dist1 = Math.sqrt(v1x * v1x + v1y * v1y)
                val dist2 = Math.sqrt(v2x * v2x + v2y * v2y)
                if (dist1 < 3.0 || dist2 < 3.0) continue

                val cosAngle = ((v1x * v2x + v1y * v2y) / (dist1 * dist2))
                    .coerceIn(-1.0, 1.0)
                angleDeg = Math.toDegrees(Math.acos(cosAngle))
                val minAngle = if (fallbackMode) 90.0 else 100.0
                if (angleDeg < minAngle) continue

                val a1 = quadrants[0]["area"] as Double
                val a2 = quadrants[1]["area"] as Double
                areaRatioQuad = Math.max(a1, a2) / (Math.min(a1, a2) + 1.0)
                val maxRatio = if (fallbackMode) 5.0 else 3.5
                if (areaRatioQuad > maxRatio) continue
            }
            cntPassAngle++
            cntPassAreaRatio++

            // ──── 外轮廓椭圆拟合 ────
            if (contours[i].total() < 5) continue
            val contour2f = MatOfPoint2f(*contours[i].toArray())
            val ellipse: RotatedRect
            val ratio: Double
            try {
                val e = Imgproc.fitEllipse(contour2f)
                contour2f.release()
                val axisA = e.size.width
                val axisB = e.size.height
                if (axisA < 1.0 || axisB < 1.0) continue
                val maxAxis = Math.max(axisA, axisB)
                if (maxAxis == 0.0) continue
                ratio = Math.min(axisA, axisB) / maxAxis
                // 外环必须接近圆形 (回退/合并模式下更严格，过滤非靶标圆形物体)
                // 真靶标外环接近正圆(ratio≈0.95~1.0); 误检常偏扁, 收紧下限可挡掉 T1 这类 0.85 的椭圆
                val minRatio = if (mergedQuadrant) 0.85 else if (fallbackMode) 0.78 else 0.85
                if (ratio < minRatio) continue
                ellipse = e
            } catch (ex: Exception) {
                contour2f.release()
                continue
            }
            cntPassEllipse++

            // 边缘排除
            val ex = ellipse.center.x
            val ey = ellipse.center.y
            val margin = 20.0
            if (ex < margin || ex > wD - margin || ey < margin || ey > hD - margin) continue
            cntPassEdge++

            // 象限质心 (2 点, 对齐 Windows 端 c_pts)
            val qc = arrayOf(Point(cx1, cy1), Point(cx2, cy2))

            // 质量评分
            val quality: Double = if (mergedQuadrant) {
                0.35  // 合并象限模式: 单后代，必须质量更高才能信任
            } else if (fallbackMode) {
                0.3   // 回退模式质量较低
            } else {
                val angleScore = Math.min(1.0, (angleDeg - 100.0) / 80.0)
                val areaConsistency = 1.0 - (areaRatioQuad - 1.0) / 2.5
                Math.max(0.3, 0.4 * ((ratio - 0.7) / 0.3) + 0.3 * angleScore + 0.3 * areaConsistency)
            }

            // ──── 质量阈值 ────
            if (quality < 0.22) continue

            // ──── 椭圆中心对齐验证: 象限质心中点应靠近椭圆中心 (靶标同心结构) ────
            // 合并象限模式: 单后代中心在椭圆中心 50% 半径内
            val quadMidX = if (mergedQuadrant) cx1 else (cx1 + cx2) / 2.0
            val quadMidY = if (mergedQuadrant) cy1 else (cy1 + cy2) / 2.0
            val dxCenter = quadMidX - ellipse.center.x
            val dyCenter = quadMidY - ellipse.center.y
            val centerDist = Math.sqrt(dxCenter * dxCenter + dyCenter * dyCenter)
            val ellipseRadius = Math.max(ellipse.size.width, ellipse.size.height) / 2.0
            val maxCenterDist = if (mergedQuadrant) ellipseRadius * 0.35 else if (fallbackMode) ellipseRadius * 0.45 else ellipseRadius * 0.55
            if (centerDist > maxCenterDist) continue

            // ──── 象限内部实心性验证 (抗光照伪影) ────
            // 真实靶标的对角象限是实心暗区, 周围是亮环; 光照亮斑/阴影产生的伪象限
            // 内部灰度与周围无明显差异。采样质心邻域(暗) vs 外环带(亮)灰度。
            val quadR = (Math.min(ellipse.size.width, ellipse.size.height) / 4.0).coerceAtLeast(3.0)
            // 原 verifyQuadrantSolid 在原始灰度图上要求质心比周围环带暗, 因坐标/灰度语义偏差
            // 会系统性误杀真实靶标(实测 inner>ring 且 inner>global+25)。几何过滤已足够严格,
            // 此处仅做极弱"非纯白"合理性检查, 避免完全失去抗纯白噪声能力。
            fun samplePt(x: Double, y: Double): Double {
                val px = x.toInt().coerceIn(0, width - 1)
                val py = y.toInt().coerceIn(0, height - 1)
                val idx = py * width + px
                return (grayData[idx].toInt() and 0xFF).toDouble()
            }
            val gAt1 = samplePt(cx1, cy1)
            val gAt2 = if (mergedQuadrant) -1.0 else samplePt(cx2, cy2)
            if (gAt1 > 245.0 || gAt2 > 245.0) {
                continue
            }

            candidates.add(Candidate(ellipse, qc, area, quality, ratio))
        }

        // 每15秒汇总一次过滤统计 (诊断用, 不影响性能)
        val now2 = System.currentTimeMillis()
        if (now2 - debugLastLogTime2 > 15000) {
            debugLastLogTime2 = now2
            Log.i(TAG, "Filter: Area=${cntPassArea} Desc=${cntPassDesc} InnerArea=${cntPassInnerArea} Quad=${cntPassQuad} Angle=${cntPassAngle} AreaR=${cntPassAreaRatio} Ellipse=${cntPassEllipse} Edge=${cntPassEdge} Final=${candidates.size}")
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No valid candidates")
        }
        return candidates
    }


    /**
     * 按面积排序分配 ID + PnP 位姿求解
     */
    private fun assignAndSolve(
        candidates: List<Candidate>,
        cameraMatrix: Mat?,
        distCoeffs: Mat?,
        resScale: Double = 1.0
    ): Map<Int, DetectionResult> {
        // candidates 必定非空 (调用前已检查)
        // 重投影误差阈值: 真靶标的 PnP 解重投影应很小; 伪目标会很大
        val reprojThresh = 4.0 * resScale + 2.0

        val initialSorted = candidates.sortedByDescending { it.area }

        // ──── 剔除包围真实靶标的幽灵外部轮廓 ────
        val validCandidates = mutableListOf<Candidate>()
        for (i in initialSorted.indices) {
            val cand1 = initialSorted[i]
            var isGhost = false
            for (j in initialSorted.indices) {
                if (i == j) continue
                val cand2 = initialSorted[j]
                if (cand1.area > cand2.area * 1.1) {
                    val dx = cand1.ellipse.center.x - cand2.ellipse.center.x
                    val dy = cand1.ellipse.center.y - cand2.ellipse.center.y
                    val dist = Math.sqrt(dx * dx + dy * dy)
                    if (dist < Math.max(cand1.ellipse.size.width, cand1.ellipse.size.height) * 0.3) {
                        isGhost = true
                        break
                    }
                }
            }
            if (!isGhost) {
                validCandidates.add(cand1)
            }
        }
        val sorted = validCandidates
        // 重投影验证被拒绝的候选索引, 后续既不更新旧目标也不创建新目标
        val rejected = mutableSetOf<Int>()
        // ──── 全局最近邻匹配 ────
        // 旧实现按面积顺序贪心: 先轮到的候选可以抢走"几何上更属于别人"的 ID,
        // 导致相邻两帧 T0/T1 互换 (表现为"重新识别错误").
        // 改为收集所有 (候选, 轨迹) 距离对, 按距离从小到大全局择优.
        val assignedIds = mutableMapOf<Int, Int>()
        val usedTids = mutableSetOf<Int>()

        data class Pair2(val si: Int, val tid: Int, val dist: Double)
        val pairs = mutableListOf<Pair2>()
        for ((si, cand) in sorted.withIndex()) {
            val cc = cand.ellipse.center
            val candR = (cand.ellipse.size.width + cand.ellipse.size.height) / 4.0
            for ((tid, t) in trackedTargets) {
                // 使用平滑后的中心做匹配 (比原始坐标更稳定, 减少ID跳变)
                val matchCx = if (t.smoothEllipseW > 0.0) t.smoothCenter.x else t.center.x
                val matchCy = if (t.smoothEllipseW > 0.0) t.smoothCenter.y else t.center.y
                val dx = cc.x - matchCx
                val dy = cc.y - matchCy
                val dist = Math.sqrt(dx * dx + dy * dy)
                if (dist >= targetConfig.minDistancePx) continue
                // 允许的位移随目标尺寸放宽: 大靶标在画面里本来就移动得快
                val maxDist = Math.max(targetConfig.minDistancePx * 0.5, candR * 1.5)
                if (dist > maxDist) continue
                // ── 面积一致性: 候选面积与追踪历史偏差 > 3x 则拒绝匹配 ──
                if (t.smoothEllipseW > 0.0) {
                    val trackedAreaEst = t.smoothEllipseW * t.smoothEllipseH
                    val candAreaEst = cand.ellipse.size.width * cand.ellipse.size.height
                    val areaRatio = Math.max(trackedAreaEst, candAreaEst) /
                                    (Math.min(trackedAreaEst, candAreaEst) + 1.0)
                    if (areaRatio > 3.0) continue  // 面积差超过3倍，不是同一个目标
                }
                pairs.add(Pair2(si, tid, dist))
            }
        }
        pairs.sortBy { it.dist }
        for (p in pairs) {
            if (p.si in assignedIds || p.tid in usedTids) continue
            assignedIds[p.si] = p.tid
            usedTids.add(p.tid)
        }

        // 未匹配的分配新 ID: 取最小未占用 tid
        // 质量不够 / 重投影验证被拒的候选不创建新追踪器 (防止单帧假阳性/光照误检)
        var nextFreeTid = 0
        for ((si, cand) in sorted.withIndex()) {
            if (si !in assignedIds && si !in rejected) {
                if (cand.quality < 0.4) continue  // 新目标必须有足够质量
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
            // 优先用用户设定的尺寸, 否则按 tid 取默认尺寸表, 再否则 200mm
            val sizeMm = targetSizes[tid]
                ?: (if (tid < defaultSizes.size) defaultSizes[tid] else 200.0)

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
            fun rotPt(dx: Double, dy: Double): Point = Point(
                cxEl + dx * cosA - dy * sinA,
                cyEl + dx * sinA + dy * cosA
            )
            // PnP 用椭圆 4 个极端点 (16点, 4角), 子轮廓质心 (2点) 仅用于渲染
            val imagePoints = MatOfPoint2f(
                rotPt(-halfW, -halfH), rotPt( halfW, -halfH),
                rotPt( halfW,  halfH), rotPt(-halfW,  halfH)
            )

            // 该候选是否匹配到"已存在的追踪目标" (用于区分新/旧目标的不同 reject 策略)
            val matchedExisting = trackedTargets.containsKey(tid)
            if (cameraMatrix != null && !cameraMatrix.empty()) {
                val rvec = Mat()
                val tvec = Mat()
                val d = MatOfDouble(distCoeffs ?: Mat.zeros(5, 1, CvType.CV_64F))

                try {
                    Calib3d.solvePnP(objectPoints, imagePoints, cameraMatrix, d, rvec, tvec)
                    // ──── 重投影验证: 真靶标误差应很小, 误检/光照伪影会很大 ────
                    val proj = MatOfPoint2f()
                    Calib3d.projectPoints(objectPoints, rvec, tvec, cameraMatrix, d, proj)
                    val perr = averageReprojError(imagePoints, proj)
                    proj.release()
                    // 仅对"新目标"用重投影误差做硬拒绝 (防光照误检);
                    // 已追踪目标不因此丢帧 (尺寸/标定略有偏差时不应整段丢失检测)
                    if (perr > reprojThresh) {
                        if (!matchedExisting) {
                            // 新目标: 重投影误差过大直接拒绝 (不创建), 防止把误检/对称镜像当成目标
                            rvec.release(); tvec.release()
                            objectPoints.release(); imagePoints.release()
                            continue
                        } else {
                            // 已追踪目标: 仅记录警告, 保持上一帧 (尺寸/标定略偏也不应整段丢检测)
                            Log.w(TAG, "Target T$tid [TRACKED] PnP reproj err=%.1f > thr=%.1f, keep prev frame".format(perr, reprojThresh))
                        }
                    }
                    results[tid] = DetectionResult(
                        success = true,
                        targetId = tid,
                        center = Point(cxEl, cyEl),
                        ellipse = cand.ellipse,
                        corners = cand.quadCenters.toList(),
                        rvec = rvec.clone(),
                        tvec = tvec.clone(),
                        quality = cand.quality,
                        sizeMm = sizeMm
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

        // 移除被重投影验证拒绝的候选, 避免它们被当作新目标重新创建
        for (si in rejected) assignedIds.remove(si)

        return results
    }

    /** 计算两组对应点的平均欧氏距离 (重投影误差) */
    private fun averageReprojError(observed: MatOfPoint2f, projected: MatOfPoint2f): Double {
        val o = observed.toArray()
        val p = projected.toArray()
        if (o.size != p.size || o.isEmpty()) return Double.MAX_VALUE
        var sum = 0.0
        for (i in o.indices) {
            val dx = o[i].x - p[i].x
            val dy = o[i].y - p[i].y
            sum += Math.sqrt(dx * dx + dy * dy)
        }
        return sum / o.size
    }

    /**
     * 计算像素直径（混合算法，对齐 Windows 端 detector.py）
     *
     * 利用四象限靶标的对角象限质心距离 + 椭圆轴长加权，
     * 比单纯使用椭圆半轴和 (halfW+halfH) 更鲁棒，不易受阈值膨胀影响。
     *
     * Windows 经验系数: centroid_dist / 0.58 ≈ full pixel diameter
     * 混合权重: 80% 象限距离, 20% 椭圆轴
     */
    private fun calcPixelDiam(halfW: Double, halfH: Double, quadCenters: Array<Point>): Double {
        if (quadCenters.size < 2) return halfW + halfH
        val dx = quadCenters[0].x - quadCenters[1].x
        val dy = quadCenters[0].y - quadCenters[1].y
        val centroidDist = Math.sqrt(dx * dx + dy * dy)
        if (centroidDist < 5.0) return halfW + halfH
        return 0.8 * (centroidDist / 0.58) + 0.2 * (halfW + halfH)
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

        val pixelDiam = calcPixelDiam(halfW, halfH, cand.quadCenters)
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
            tvec = tvec, quality = cand.quality * 0.8,
            sizeMm = sizeMm
        )
    }

    private fun updateTracking(results: Map<Int, DetectionResult>) {
        val detected = results.values.filter { it.success }
        val allTids = results.keys.toSet()

        // 成功检测: 应用 EMA 平滑，重置 lostFrames
        for (r in detected) {
            val prev = trackedTargets[r.targetId]

            if (prev != null && prev.smoothEllipseW > 0.0) {
                // ── 已有平滑状态: EMA 插值 ──
                val scx = prev.smoothCenter.x + EMA_ALPHA * (r.center.x - prev.smoothCenter.x)
                val scy = prev.smoothCenter.y + EMA_ALPHA * (r.center.y - prev.smoothCenter.y)

                val el = r.ellipse!!  // 检测成功时 ellipse 一定非 null
                val ew = el.size.width
                val eh = el.size.height
                val sew = prev.smoothEllipseW + EMA_ALPHA * (ew - prev.smoothEllipseW)
                val seh = prev.smoothEllipseH + EMA_ALPHA * (eh - prev.smoothEllipseH)

                // EMA 平滑 3D 位姿 tvec [tx, ty, tz]
                val curT = DoubleArray(3)
                r.tvec.get(0, 0, curT)
                val stx = prev.smoothTx + EMA_ALPHA * (curT[0] - prev.smoothTx)
                val sty = prev.smoothTy + EMA_ALPHA * (curT[1] - prev.smoothTy)
                val stz = prev.smoothTz + EMA_ALPHA * (curT[2] - prev.smoothTz)

                // 构建平滑后的 DetectionResult (ellipse + tvec 同步平滑)
                val smoothedEllipse = RotatedRect(
                    Point(scx, scy),
                    Size(sew, seh),
                    el.angle.toDouble()
                )
                val smoothedTvec = Mat(3, 1, CvType.CV_64F)
                smoothedTvec.put(0, 0, stx, sty, stz)

                val smoothedResult = r.copy(
                    center = Point(scx, scy),
                    ellipse = smoothedEllipse,
                    tvec = smoothedTvec,
                    corners = r.corners  // 保留象限质心点用于 UI 渲染
                )

                trackedTargets[r.targetId] = TrackedTarget(
                    center = r.center,  // 原始中心用于帧间匹配，不用平滑值
                    lostFrames = 0,
                    sizeMm = r.sizeMm,
                    lastResult = smoothedResult,
                    smoothCenter = Point(scx, scy),
                    smoothEllipseW = sew,
                    smoothEllipseH = seh,
                    smoothTx = stx,
                    smoothTy = sty,
                    smoothTz = stz
                )
            } else {
                // 首次检测该目标: 初始化平滑状态
                val curT = DoubleArray(3)
                r.tvec.get(0, 0, curT)
                val elInit = r.ellipse!!
                val ew = elInit.size.width
                val eh = elInit.size.height

                trackedTargets[r.targetId] = TrackedTarget(
                    center = r.center,
                    lostFrames = 0,
                    sizeMm = r.sizeMm,
                    lastResult = r,
                    smoothCenter = r.center,
                    smoothEllipseW = ew,
                    smoothEllipseH = eh,
                    smoothTx = curT[0],
                    smoothTy = curT[1],
                    smoothTz = curT[2]
                )
            }
        }

        // 未匹配的追踪目标: 累计丢失帧数
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

    /** 获取某靶标的实际尺寸(mm)，未设置返回 null */
    fun getTargetSize(targetId: Int): Double? = targetSizes[targetId]

    data class Candidate(
        val ellipse: RotatedRect,
        val quadCenters: Array<Point>,
        val area: Double,
        val quality: Double,
        val ratio: Double
    )
}
