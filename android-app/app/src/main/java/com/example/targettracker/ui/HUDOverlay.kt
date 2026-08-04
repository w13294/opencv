package com.example.targettracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.targettracker.TargetTrackerState
import com.example.targettracker.ui.theme.*

/**
 * 画面上层 HUD 叠加 — XYZ位移数值 + XY轨迹图
 */
@Composable
fun HUDOverlay(
    state: TargetTrackerState,
    modifier: Modifier = Modifier
) {
    val disp = state.primaryDisplacement
    val detect = state.primaryDetection

    Row(modifier = modifier.padding(12.dp)) {
        // ──── 左: 位移数值面板 ────
        DisplacementPanel(disp, detect, state.warningMessage)

        Spacer(Modifier.weight(1f))

        // ──── 右: XY轨迹图 ────
        TrajectoryChart(state, maxHistory = 300)
    }
}

@Composable
private fun DisplacementPanel(
    disp: com.example.targettracker.engine.DisplacementResult?,
    detect: com.example.targettracker.detector.DetectionResult?,
    warning: String?
) {
    Column(
        modifier = Modifier
            .background(HudWhite.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (disp != null) {
            HudLine("X", disp.xMm, HudGreen, "mm")
            HudLine("Y", disp.yMm, HudGreen, "mm")
            HudLine("Z", disp.zMm, PrimaryCyan, "mm")
            HudLine("二维", disp.displacement2d, HudYellow, "mm")

            val qual = detect?.quality ?: disp.detectionQuality
            val qualColor = when {
                qual >= 0.7 -> HudGreen
                qual >= 0.4 -> HudYellow
                else -> HudRed
            }
            Text(
                "质量: %.2f".format(qual),
                style = MaterialTheme.typography.labelSmall,
                color = qualColor
            )

            when {
                disp.isOutlier -> Text("异常值", style = MaterialTheme.typography.labelSmall, color = HudRed)
                disp.isStale -> Text("预测中", style = MaterialTheme.typography.labelSmall, color = HudYellow)
            }
        } else {
            Text("未检测到靶标", style = MaterialTheme.typography.bodyLarge, color = HudRed)
        }

        if (warning != null) {
            Spacer(Modifier.height(4.dp))
            Text(warning, style = MaterialTheme.typography.labelSmall, color = HudYellow)
        } else if (detect != null) {
            // 默认内参估算, 提示距离仅供参考
            Text("未标定·距离≈估算值", style = MaterialTheme.typography.labelSmall, color = HudGray)
        }
    }
}

@Composable
private fun HudLine(label: String, value: Double, color: androidx.compose.ui.graphics.Color, unit: String) {
    Text(
        "%s: %+8.3f %s".format(label, value, unit),
        style = MaterialTheme.typography.titleMedium,
        color = color
    )
}

/**
 * XY 轨迹图 — 纯 Compose Canvas 绘制
 */
@Composable
fun TrajectoryChart(
    state: TargetTrackerState,
    maxHistory: Int = 300
) {
    // 轨迹缓存
    val points = remember { mutableStateListOf<Pair<Float, Float>>() }
    val disp = state.primaryDisplacement
    if (disp != null && !disp.isOutlier) {
        points.add(disp.xMm.toFloat() to disp.yMm.toFloat())
        if (points.size > maxHistory) {
            points.removeRange(0, points.size - maxHistory)
        }
    }

    Box(
        modifier = Modifier
            .width(180.dp).height(180.dp)
            .background(DarkBackground.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
    ) {
        Column {
            Text(
                "X-Y 轨迹",
                style = MaterialTheme.typography.labelSmall,
                color = HudWhite,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )

            Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                if (points.isEmpty()) return@Canvas

                val cx = size.width / 2f
                val cy = size.height / 2f

                // 自动缩放
                val maxAbs = points.flatMap { listOf(it.first, it.second) }
                    .map { kotlin.math.abs(it) }.maxOrNull()?.coerceAtLeast(10f) ?: 50f
                val scale = (size.width * 0.4f) / maxAbs

                // 十字参考线
                drawLine(
                    androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.25f),
                    Offset(0f, cy), Offset(size.width, cy), 1f
                )
                drawLine(
                    androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.25f),
                    Offset(cx, 0f), Offset(cx, size.height), 1f
                )

                // 轨迹连线
                for (i in 1 until points.size) {
                    val (x1, y1) = points[i - 1]
                    val (x2, y2) = points[i]
                    drawLine(
                        HudGreen.copy(alpha = (i.toFloat() / points.size).coerceIn(0.2f, 1f)),
                        Offset(cx + x1 * scale, cy - y1 * scale),
                        Offset(cx + x2 * scale, cy - y2 * scale),
                        1.5f
                    )
                }

                // 起点 (绿色)
                if (points.size > 1) {
                    val (sx, sy) = points.first()
                    drawCircle(HudGreen, 5f, Offset(cx + sx * scale, cy - sy * scale))
                }

                // 终点/当前点 (红色)
                val (lx, ly) = points.last()
                drawCircle(HudRed, 6f, Offset(cx + lx * scale, cy - ly * scale))
            }
        }
    }
}
