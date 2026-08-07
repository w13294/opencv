package com.example.targettracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.example.targettracker.TargetTrackerState
import com.example.targettracker.ui.theme.*

/**
 * 画面上层 HUD 叠加 — XYZ位移数值面板 (左上角, 可折叠)
 * 轨迹图由 MainScreen 在右上角独立放置，避免抢占中心画面
 *
 * 多靶标时显示 Tab 选择器，可切换查看不同靶标的位移数据
 */
@Composable
fun HUDOverlay(
    state: TargetTrackerState,
    modifier: Modifier = Modifier,
    onSelectTarget: (Int) -> Unit = {}
) {
    val disp = state.selectedDisplacement()
    val detect = state.selectedDetection()
    val targetIds = state.detectResults.keys.sorted()
    val selectedId = state.selectedTargetId ?: targetIds.firstOrNull()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.padding(12.dp)) {
        DisplacementPanel(
            disp = disp,
            detect = detect,
            warning = state.warningMessage,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            targetIds = targetIds,
            selectedId = selectedId,
            onSelectTarget = onSelectTarget,
            targetSizes = state.targetSizes
        )
    }
}

@Composable
private fun DisplacementPanel(
    disp: com.example.targettracker.engine.DisplacementResult?,
    detect: com.example.targettracker.detector.DetectionResult?,
    warning: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    targetIds: List<Int> = emptyList(),
    selectedId: Int? = null,
    onSelectTarget: (Int) -> Unit = {},
    targetSizes: Map<Int, Double> = emptyMap()
) {
    val cardBase = Modifier
        .shadow(14.dp, RoundedCornerShape(14.dp))
        .clip(RoundedCornerShape(14.dp))
        .background(Brush.verticalGradient(listOf(Surface1.copy(alpha = 0.92f), Surface0.copy(alpha = 0.95f))))
        .border(1.dp, HudLine.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
        .clickable(onClick = onToggle)

    if (!expanded) {
        // 折叠态: 一行紧凑胶囊
        Row(
            modifier = cardBase.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val c = when {
                disp == null -> HudRed
                disp.isOutlier || disp.isStale -> HudYellow
                else -> HudGreen
            }
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
            if (disp != null) {
                if (targetIds.size > 1 && selectedId != null) {
                    Text("#$selectedId", style = MaterialTheme.typography.labelSmall, color = HudCyan)
                }
                Text("X %.1f".format(disp.xMm), style = MaterialTheme.typography.labelSmall, color = HudWhite)
                Text("Y %.1f".format(disp.yMm), style = MaterialTheme.typography.labelSmall, color = HudWhite)
                Text("Z %.1f".format(disp.zMm), style = MaterialTheme.typography.labelSmall, color = HudCyan)
                // 常驻显示靶标尺寸
                selectedId?.let { tid -> targetSizes[tid]?.let { mm ->
                    Text("∅%.0fmm".format(mm), style = MaterialTheme.typography.labelSmall, color = HudGray)
                } }
            } else {
                Text("无靶标", style = MaterialTheme.typography.labelSmall, color = HudRed)
            }
            Icon(Icons.Filled.ExpandLess, "展开", tint = HudGray, modifier = Modifier.size(16.dp))
        }
        return
    }

    // 展开态: 完整面板
    Column(
        modifier = cardBase.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ── 靶标 Tab 选择器 ──
        if (targetIds.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                targetIds.forEach { tid ->
                    val isSelected = tid == selectedId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) PrimaryCyan.copy(alpha = 0.25f) else Surface2.copy(alpha =
                                0.4f))
                            .border(1.dp, if (isSelected) PrimaryCyan else HudLine.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .clickable { onSelectTarget(tid) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "#$tid",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) PrimaryCyan else HudGray
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectedId != null) "位移 #$selectedId" else "位移",
                style = MaterialTheme.typography.labelMedium, color = HudCyan,
                modifier = Modifier.weight(1f))
            // 常驻显示靶标实际尺寸
            selectedId?.let { tid -> targetSizes[tid]?.let { mm ->
                Text("∅%.0f mm".format(mm), style = MaterialTheme.typography.labelSmall, color = HudGray)
            } }
            Icon(Icons.Filled.ExpandMore, "收起", tint = HudGray, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(4.dp))
        if (disp != null) {
            HudLine("X", disp.xMm, HudGreen)
            HudLine("Y", disp.yMm, HudGreen)
            HudLine("Z", disp.zMm, PrimaryCyan)
            HudLine("2D", disp.displacement2d, HudYellow)

            val qual = detect?.quality ?: disp.detectionQuality
            val qualColor = when {
                qual >= 0.7 -> HudGreen
                qual >= 0.4 -> HudYellow
                else -> HudRed
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("质量", style = MaterialTheme.typography.labelSmall, color = HudGray,
                    modifier = Modifier.width(30.dp))
                LinearProgressIndicator(
                    progress = qual.toFloat().coerceIn(0f, 1f),
                    color = qualColor,
                    trackColor = Surface2,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.width(6.dp))
                Text("%.2f".format(qual), style = MaterialTheme.typography.labelSmall, color = qualColor,
                    modifier = Modifier.width(30.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }

            when {
                disp.isOutlier -> Text("异常值", style = MaterialTheme.typography.labelSmall, color = HudRed)
                disp.isStale -> Text("预测中", style = MaterialTheme.typography.labelSmall, color = HudYellow)
            }
        } else {
            Text("未检测到靶标", style = MaterialTheme.typography.labelSmall, color = HudRed)
        }

        if (warning != null) {
            Spacer(Modifier.height(2.dp))
            Text(warning, style = MaterialTheme.typography.labelSmall, color = HudYellow, maxLines = 2)
        } else if (detect != null) {
            Text("未标定·距离估算", style = MaterialTheme.typography.labelSmall, color = HudGray)
        }
    }
}

@Composable
private fun HudLine(label: String, value: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = HudGray,
            modifier = Modifier.width(22.dp)
        )
        Text(
            "%+8.2f".format(value),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1
        )
        Text(
            " mm",
            style = MaterialTheme.typography.labelSmall,
            color = HudGray
        )
    }
}

/**
 * XY 轨迹图 — 纯 Compose Canvas 绘制 (可折叠)
 */
@Composable
fun TrajectoryChart(
    state: TargetTrackerState,
    maxHistory: Int = 300,
    onSelectTarget: (Int) -> Unit = {}
) {
    val disp = state.selectedDisplacement()
    val selectedId = state.selectedTargetId
    val targetIds = state.detectResults.keys.sorted()

    val points = remember { mutableStateListOf<Pair<Float, Float>>() }
    // 当选中目标变化时清空轨迹历史
    val keySelected by rememberUpdatedState(selectedId)
    LaunchedEffect(keySelected) {
        points.clear()
    }
    if (disp != null && !disp.isOutlier) {
        points.add(disp.xMm.toFloat() to disp.yMm.toFloat())
        if (points.size > maxHistory) {
            points.removeRange(0, points.size - maxHistory)
        }
    }

    var expanded by remember { mutableStateOf(false) }

    if (!expanded) {
        // 折叠态: 紧凑小角标
        Row(
            modifier = Modifier
                .shadow(10.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(Surface1.copy(alpha = 0.92f), Surface0.copy(alpha = 0.95f))))
                .border(1.dp, HudLine.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(HudGreen))
            if (targetIds.size > 1 && selectedId != null) {
                Text("#$selectedId", style = MaterialTheme.typography.labelSmall, color = HudCyan)
            }
            Text("轨迹", style = MaterialTheme.typography.labelSmall, color = HudCyan)
            Icon(
                Icons.Filled.ExpandLess, "展开轨迹",
                tint = HudGray, modifier = Modifier.size(14.dp)
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .width(130.dp).height(150.dp)
            .shadow(14.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Surface1.copy(alpha = 0.9f), Surface0.copy(alpha = 0.94f))))
            .border(1.dp, HudLine.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable { expanded = false }
    ) {
        Column {
            // ── 靶标 Tab 选择器 ──
            if (targetIds.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp, end = 6.dp)
                ) {
                    targetIds.forEach { tid ->
                        val isSelected = tid == selectedId
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) PrimaryCyan.copy(alpha = 0.25f) else Surface2.copy(alpha = 0.4f))
                                .border(0.5.dp, if (isSelected) PrimaryCyan else HudLine.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .clickable { onSelectTarget(tid) }
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                "#$tid",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) PrimaryCyan else HudGray,
                                fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (selectedId != null) "XY轨迹#$selectedId" else "X-Y 轨迹",
                    style = MaterialTheme.typography.labelSmall,
                    color = HudCyan,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.ExpandMore, "收起",
                    tint = HudGray, modifier = Modifier.size(14.dp)
                )
            }

            Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                if (points.isEmpty()) return@Canvas

                val cx = size.width / 2f
                val cy = size.height / 2f

                val maxAbs = points.flatMap { listOf(it.first, it.second) }
                    .map { kotlin.math.abs(it) }.maxOrNull()?.coerceAtLeast(10f) ?: 50f
                val scale = (size.width * 0.4f) / maxAbs

                drawLine(
                    HudLine.copy(alpha = 0.35f),
                    Offset(0f, cy), Offset(size.width, cy), 1f
                )
                drawLine(
                    HudLine.copy(alpha = 0.35f),
                    Offset(cx, 0f), Offset(cx, size.height), 1f
                )

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

                if (points.size > 1) {
                    val (sx, sy) = points.first()
                    drawCircle(HudGreen, 5f, Offset(cx + sx * scale, cy - sy * scale))
                }

                val (lx, ly) = points.last()
                drawCircle(HudRed, 6f, Offset(cx + lx * scale, cy - ly * scale))
            }
        }
    }
}
