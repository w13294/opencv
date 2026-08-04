package com.example.targettracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.targettracker.TargetTrackerState
import com.example.targettracker.ui.theme.*
import com.example.targettracker.engine.DisplacementResult

/**
 * 底部控制栏
 */
@Composable
fun ControlBar(
    state: TargetTrackerState,
    cameraLabels: List<String> = emptyList(),
    currentCameraIndex: Int = 0,
    onSwitchCamera: (Int) -> Unit = {},
    zoomRatios: List<Float> = emptyList(),
    currentZoom: Float = 1.0f,
    onSetZoom: (Float) -> Unit = {},
    onShowCameraPicker: () -> Unit = {},
    onEditSize: (Int) -> Unit = {},
    onZeroReset: () -> Unit,
    onCalibrate: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    resolutionLabels: List<String> = listOf("640x480", "1280x720", "1920x1080", "2560x1440", "3840x2160"),
    currentResolutionIndex: Int = 0,
    onSetResolution: (Int) -> Unit = {}
) {
    val primary = state.primaryDisplacement
    var expanded by remember { mutableStateOf(false) }
    val activeTargets = state.stats["activeTargets"] as? Int ?: 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Surface1.copy(alpha = 0.95f), Surface0.copy(alpha = 0.97f))))
            .border(1.dp, HudLine.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // ───── 折叠态: 单行精简条 ─────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onZeroReset,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.zeroed) AccentCyan else HudGreen
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.zeroed) "归零" else "零位", color = Color.Black)
                }
                if (state.zeroed && primary != null) {
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(primary)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("靶 $activeTargets", style = MaterialTheme.typography.labelSmall, color = HudWhite)
                Spacer(Modifier.width(8.dp))
                Text("%.0ffps".format(state.fps), style = MaterialTheme.typography.labelSmall, color = HudYellow)
                Spacer(Modifier.width(4.dp))
                MiniIcon(Icons.Filled.Refresh, "重置", HudWhite, onReset)
                MiniIcon(Icons.Filled.Tune, "标定", HudCyan, onCalibrate)
                Spacer(Modifier.width(2.dp))
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = HudWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ───── 展开态: 完整控制行 ─────
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            // 摄像头切换 + 尺寸编辑
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillButton(
                    icon = Icons.Filled.CameraAlt,
                    text = if (cameraLabels.isEmpty()) "摄像头" else "摄像头▾",
                    onClick = onShowCameraPicker,
                    tint = HudCyan
                )
                val firstTid = state.detectResults.keys.firstOrNull()
                PillButton(
                    icon = Icons.Filled.Straighten,
                    text = if (firstTid != null) "尺寸 T$firstTid" else "尺寸",
                    onClick = { onEditSize(firstTid ?: 0) },
                    tint = HudGreen
                )
            }

            // 分辨率选择
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("分辨率: ", color = HudGray, style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    resolutionLabels.forEachIndexed { idx, label ->
                        val sel = idx == currentResolutionIndex
                        Box(
                            modifier = Modifier
                                .background(
                                    if (sel) Brush.horizontalGradient(listOf(AccentTeal, AccentCyan))
                                    else SolidColor(Color.Transparent),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onSetResolution(idx) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sel) Color.Black else HudWhite
                            )
                        }
                    }
                }
            }

            // 变焦档位
            if (zoomRatios.size > 1) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface2)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    zoomRatios.forEach { z ->
                        val sel = kotlin.math.abs(z - currentZoom) < 0.05f
                        val txt = if (z < 1f) "%.1fx".format(z) else "%.0fx".format(z)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (sel) Brush.horizontalGradient(listOf(AccentTeal, AccentCyan))
                                    else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onSetZoom(z) }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                txt,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sel) Color.Black else HudWhite
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .shadow(4.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(5.dp))
        Text(text, color = HudWhite, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatusBadge(disp: DisplacementResult) {
    val (text, color) = when {
        disp.isOutlier -> "异常值" to HudRed
        disp.isStale -> "预测中" to HudYellow
        disp.detectionQuality < 0.4 -> "低质量" to HudYellow
        else -> "追踪中" to HudGreen
    }
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** 摄像头选择弹窗 */
@Composable
fun CameraPickerDialog(
    labels: List<String>,
    current: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .shadow(28.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(Surface1, Surface0)))
                .border(1.dp, HudLine.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .padding(22.dp)
        ) {
            Text("选择摄像头", style = MaterialTheme.typography.titleLarge, color = HudWhite)
            Spacer(Modifier.height(16.dp))
            if (labels.isEmpty()) {
                Text("未枚举到摄像头，请稍候或重启应用", color = HudYellow,
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                labels.forEachIndexed { idx, label ->
                    val selected = idx == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Surface2 else Color.Transparent)
                            .border(1.dp, if (selected) AccentTeal else HudLine, RoundedCornerShape(12.dp))
                            .clickable { onSelect(idx) }
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelect(idx) },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentTeal)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = if (selected) AccentTeal else HudWhite)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("关闭", color = HudGray)
            }
        }
    }
}

/** 靶标尺寸编辑弹窗 — 预设 200/100/50mm + 自定义 */
@Composable
fun SizeEditDialog(
    targetId: Int,
    currentSizeMm: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var customText by remember { mutableStateOf(if (currentSizeMm > 0) currentSizeMm.toInt().toString() else "200") }
    val presets = listOf(200, 100, 50)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .shadow(28.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(Surface1, Surface0)))
                .border(1.dp, HudLine.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .padding(22.dp)
        ) {
            Text("靶标 T$targetId 尺寸 (mm)", style = MaterialTheme.typography.titleLarge, color = HudWhite)
            Spacer(Modifier.height(8.dp))
            Text("选择预设直径或输入自定义:", color = HudGray, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (customText == p.toString()) AccentTeal else Surface2)
                            .border(1.dp, if (customText == p.toString()) Color.Transparent else HudLine, RoundedCornerShape(10.dp))
                            .clickable { customText = p.toString() }
                            .padding(vertical = 10.dp)
                    ) {
                        Text("${p}mm", color = if (customText == p.toString()) Color.Black else HudWhite,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it.filter { c -> c.isDigit() } },
                label = { Text("直径 mm", color = HudGray) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = HudWhite,
                    unfocusedTextColor = HudWhite,
                    focusedBorderColor = AccentTeal,
                    unfocusedBorderColor = HudLine
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消", color = HudGray)
                }
                Button(
                    onClick = {
                        val v = customText.toIntOrNull() ?: 200
                        onConfirm(v.toDouble().coerceIn(5.0, 5000.0))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确定", color = Color.Black)
                }
            }
        }
    }
}
