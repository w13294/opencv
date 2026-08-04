package com.example.targettracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier
) {
    val primary = state.primaryDisplacement

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF121212))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // 第一行: 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onZeroReset,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.zeroed) AccentBlue else HudGreen
                    )
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.zeroed) "重新归零" else "设置零位")
                }
                if (state.zeroed && primary != null) {
                    Spacer(Modifier.width(16.dp))
                    StatusBadge(primary)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val activeTargets = state.stats["activeTargets"] as? Int ?: 0
                Text("靶标: $activeTargets", style = MaterialTheme.typography.labelSmall, color = HudWhite)
                Spacer(Modifier.width(12.dp))
                Text("%.0f fps".format(state.fps), style = MaterialTheme.typography.labelSmall, color = HudYellow)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onReset, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "重置", tint = HudWhite)
                }
                IconButton(onClick = onCalibrate, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = "标定", tint = HudWhite)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 第二行: 摄像头切换 + 尺寸编辑
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onShowCameraPicker,
                border = BorderStroke(1.dp, HudGreen),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HudGreen)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (cameraLabels.isEmpty()) "摄像头" else "摄像头▾")
            }
            val firstTid = state.detectResults.keys.firstOrNull()
            OutButtonWithIcon(
                text = if (firstTid != null) "尺寸 T$firstTid" else "尺寸",
                onClick = { onEditSize(firstTid ?: 0) }
            )
        }

        // 第三行: 变焦档位 (后摄为逻辑多摄, 变焦比会自动切换超广角/主摄/长焦物理镜头)
        if (zoomRatios.size > 1) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                zoomRatios.forEach { z ->
                    val sel = kotlin.math.abs(z - currentZoom) < 0.05f
                    val txt = if (z < 1f) "%.1fx".format(z) else "%.0fx".format(z)
                    Box(
                        modifier = Modifier
                            .background(
                                if (sel) HudGreen else Color(0xFF2A2A2A),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onSetZoom(z) }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            txt,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sel) Color.Black else HudWhite
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutButtonWithIcon(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, HudGreen),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = HudGreen)
    ) {
        Icon(Icons.Default.Straighten, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择摄像头", color = HudWhite) },
        text = {
            Column {
                if (labels.isEmpty()) {
                    Text("未枚举到摄像头，请稍候或重启应用", color = HudYellow)
                }
                labels.forEachIndexed { idx, label ->
                    val selected = idx == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(idx) }
                            .background(if (selected) Color(0xFF1E4D2B) else Color.Transparent)
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelect(idx) },
                            colors = RadioButtonDefaults.colors(selectedColor = HudGreen)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = HudWhite)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = HudGreen) }
        },
        containerColor = Color(0xFF1A1A1A)
    )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("靶标 T$targetId 尺寸 (mm)", color = HudWhite) },
        text = {
            Column {
                Text("选择预设直径或输入自定义:", color = HudGray)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { p ->
                        SmallButton(text = "${p}mm", onClick = { customText = p.toString() })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter { c -> c.isDigit() } },
                    label = { Text("直径 mm", color = HudGray) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = HudWhite,
                        unfocusedTextColor = HudWhite,
                        focusedBorderColor = HudGreen,
                        unfocusedBorderColor = HudGray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = customText.toIntOrNull() ?: 200
                onConfirm(v.toDouble().coerceIn(5.0, 5000.0))
            }) { Text("确定", color = HudGreen) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = HudGray) }
        },
        containerColor = Color(0xFF1A1A1A)
    )
}

/** 小尺寸文本按钮 (弹窗内预设用) */
@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = HudGreen),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Text(text, color = Color.Black)
    }
}
