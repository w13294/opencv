package com.example.targettracker.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class CalibStage { INPUT, SAMPLING, DONE }

data class CalibUiState(
    val stage: CalibStage = CalibStage.INPUT,
    val gridCols: Int = 8,
    val gridRows: Int = 5,
    val squareSizeMm: String = "25",
    val requiredSamples: Int = 25,
    val sampleCount: Int = 0,
    val rejectReason: String? = null,
    val reprojectionError: Double? = null,
    val fx: Double? = null,
    val fy: Double? = null,
    val cx: Double? = null,
    val cy: Double? = null,
    val previewBitmap: Bitmap? = null,
    val cameraId: String = "0",
    val cameraLabel: String = "",
    val cameraLabels: List<String> = emptyList(),
    val zoomRatio: Float = 1.0f,
    val zoomOptions: List<Float> = emptyList(),
    /** 当前选择的镜头（摄像头+变焦档）是否已标定 */
    val calibrated: Boolean = false
)

@Composable
fun CalibrationDialog(
    state: CalibUiState,
    onStartSampling: (Int, Int, Double, String, Float) -> Unit = { _, _, _, _, _ -> },
    onFinish: () -> Unit = {},
    onCancel: () -> Unit = {},
    onSelectCamera: (Int) -> Unit = {},
    onSelectZoom: (Float) -> Unit = {}
) {
    Dialog(
        onDismissRequest = { if (state.stage != CalibStage.SAMPLING) onCancel() },
        properties = DialogProperties(
            dismissOnBackPress = state.stage != CalibStage.SAMPLING,
            dismissOnClickOutside = state.stage != CalibStage.SAMPLING,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2B2B))
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state.stage) {
                    CalibStage.INPUT -> InputStage(state, onStartSampling, onCancel, onSelectCamera, onSelectZoom)
                    CalibStage.SAMPLING -> SamplingStage(state, onCancel)
                    CalibStage.DONE -> DoneStage(state, onFinish, onCancel)
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// 阶段1：输入棋盘格参数
// ──────────────────────────────────────────────
@Composable
private fun InputStage(
    state: CalibUiState,
    onStartSampling: (Int, Int, Double, String, Float) -> Unit,
    onCancel: () -> Unit,
    onSelectCamera: (Int) -> Unit,
    onSelectZoom: (Float) -> Unit
) {
    // 本地可编辑状态, 初始值从外部 state 读取
    var gridCols by remember(state) { mutableIntStateOf(state.gridCols) }
    var gridRows by remember(state) { mutableIntStateOf(state.gridRows) }
    var squareSizeText by remember(state) { mutableStateOf(state.squareSizeMm) }

    val sqMm = squareSizeText.toDoubleOrNull()
    val isValid = sqMm != null && sqMm > 0 && gridCols >= 3 && gridRows >= 3

    Text("棋盘格相机标定", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Spacer(Modifier.height(4.dp))
    Text(
        "内角点数 = 方格数 − 1。例如 9×6 个方格填 8×5。",
        fontSize = 12.sp, color = Color(0xFFAAAAAA)
    )
    Spacer(Modifier.height(12.dp))

    // 摄像头选择（支持多摄像头分别标定）
    if (state.cameraLabels.size > 1) {
        CameraSelector(
            labels = state.cameraLabels,
            selectedId = state.cameraId,
            onSelect = { idx -> onSelectCamera(idx) }
        )
        Spacer(Modifier.height(8.dp))
    } else {
        Text(
            "当前摄像头: ${state.cameraLabel.ifBlank { "默认" }}",
            fontSize = 12.sp, color = Color(0xFF4FC3F7)
        )
        Spacer(Modifier.height(8.dp))
    }

    // 变焦/镜头选择（多摄手机的每个物理镜头通过不同变焦比切换）
    if (state.zoomOptions.size > 1) {
        ZoomSelector(
            options = state.zoomOptions,
            selected = state.zoomRatio,
            onSelect = { zoom -> onSelectZoom(zoom) }
        )
        Spacer(Modifier.height(8.dp))
    }

    // 当前所选镜头的标定状态徽章
    val zoomLabel = formatZoomLabel(state.zoomRatio)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (state.calibrated) Color(0xFF1B5E20) else Color(0xFF37474F),
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                if (state.calibrated) "✓ 该镜头已标定" else "○ 该镜头未标定",
                fontSize = 12.sp,
                color = if (state.calibrated) Color(0xFF81C784) else Color(0xFFB0BEC5)
            )
        }
        Text(
            "${state.cameraLabel.ifBlank { "默认" }} · $zoomLabel ${String.format("%.1f", state.zoomRatio)}x",
            fontSize = 12.sp, color = Color(0xFFAAAAAA)
        )
    }
    if (state.calibrated) {
        Spacer(Modifier.height(4.dp))
        Text(
            "该镜头已有标定数据，重新标定将覆盖原有结果",
            fontSize = 11.sp, color = Color(0xFFFFB74D)
        )
    }
    Spacer(Modifier.height(8.dp))

    // 棋盘格规格
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("内角列数", fontSize = 13.sp, color = Color(0xFFCCCCCC))
            Spacer(Modifier.height(4.dp))
            GridNumberPicker(
                value = gridCols,
                onValueChange = { gridCols = it },
                range = 3..25
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("内角行数", fontSize = 13.sp, color = Color(0xFFCCCCCC))
            Spacer(Modifier.height(4.dp))
            GridNumberPicker(
                value = gridRows,
                onValueChange = { gridRows = it },
                range = 3..25
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = squareSizeText,
        onValueChange = { newVal ->
            // 只允许数字和小数点
            if (newVal.isEmpty() || newVal.matches(Regex("^\\d*\\.?\\d*$"))) {
                squareSizeText = newVal
            }
        },
        label = { Text("每格边长 (mm)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF4FC3F7),
            unfocusedBorderColor = Color(0xFF555555),
            cursorColor = Color(0xFF4FC3F7),
            focusedLabelColor = Color(0xFF4FC3F7),
            unfocusedLabelColor = Color(0xFF999999)
        )
    )

    Spacer(Modifier.height(20.dp))

    Button(
        onClick = {
            val sqSize = squareSizeText.toDoubleOrNull() ?: return@Button
            val zoom = state.zoomOptions.firstOrNull { it == state.zoomRatio } ?: state.zoomOptions.firstOrNull() ?: 1.0f
            onStartSampling(gridCols, gridRows, sqSize, state.cameraId, zoom)
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC3F7)),
        enabled = isValid
    ) {
        Text("开始标定 →", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }

    Spacer(Modifier.height(8.dp))

    TextButton(onClick = onCancel) {
        Text("取消", color = Color(0xFF999999))
    }
}

// ──────────────────────────────────────────────
// 阶段2：采样中
// ──────────────────────────────────────────────
@Composable
private fun SamplingStage(state: CalibUiState, onCancel: () -> Unit) {
    Text("正在采集棋盘格图像…", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Spacer(Modifier.height(4.dp))
    Text(
        "请从不同角度和距离对准棋盘格，确保每个格子清晰可见",
        fontSize = 11.sp, color = Color(0xFFAAAAAA)
    )
    Spacer(Modifier.height(8.dp))

    // 当前正在标定的镜头（摄像头 + 变焦档）
    val zoomLabel = formatZoomLabel(state.zoomRatio)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A4C))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("🎯 正在为", fontSize = 12.sp, color = Color(0xFFB3E5FC))
            Text(
                "${state.cameraLabel.ifBlank { "默认" }} · $zoomLabel ${String.format("%.1f", state.zoomRatio)}x",
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7)
            )
            Text("标定", fontSize = 12.sp, color = Color(0xFFB3E5FC))
        }
    }
    Spacer(Modifier.height(6.dp))
    // 当前棋盘格规格
    Text(
        "棋盘格: ${state.gridCols}×${state.gridRows} 内角点, ${state.squareSizeMm}mm/格",
        fontSize = 11.sp, color = Color(0xFFAAAAAA)
    )
    Spacer(Modifier.height(8.dp))

    // 预览画面 + 棋盘格角点绘制（固定高度, 不占满, 避免太大）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1A))
            .border(1.dp, Color(0xFF444444), RoundedCornerShape(8.dp))
    ) {
        val bmp = state.previewBitmap
        if (bmp != null) {
            val imgBitmap: ImageBitmap = bmp.asImageBitmap()
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 保持原图宽高比居中缩放绘制, 不拉伸变形
                val bw = imgBitmap.width.toFloat()
                val bh = imgBitmap.height.toFloat()
                val scale = minOf(size.width / bw, size.height / bh)
                val dw = (bw * scale).toInt()
                val dh = (bh * scale).toInt()
                drawImage(
                    image = imgBitmap,
                    dstSize = IntSize(dw, dh),
                    dstOffset = IntOffset(
                        ((size.width - dw) / 2).toInt(),
                        ((size.height - dh) / 2).toInt()
                    )
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("等待画面…", color = Color(0xFF666666))
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // 进度
    val ratio = state.sampleCount.toFloat() / state.requiredSamples.coerceAtLeast(1)
    LinearProgressIndicator(
        progress = { ratio.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        color = Color(0xFF4FC3F7),
        trackColor = Color(0xFF444444)
    )

    Spacer(Modifier.height(6.dp))
    Text(
        "已采集 ${state.sampleCount} / ${state.requiredSamples} 帧",
        fontSize = 13.sp, color = Color(0xFFCCCCCC)
    )

    // 提示/拒绝原因
    if (!state.rejectReason.isNullOrBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            state.rejectReason,
            fontSize = 12.sp,
            color = Color(0xFFFF8A65),
            lineHeight = 16.sp
        )
    } else if (state.sampleCount > 0) {
        Spacer(Modifier.height(6.dp))
        Text("✓ 角点检测成功，保持移动继续采集", fontSize = 12.sp, color = Color(0xFF81C784))
    }

    Spacer(Modifier.height(10.dp))
    TextButton(onClick = onCancel) {
        Text("取消标定", color = Color(0xFFFF8A65))
    }
}

// ──────────────────────────────────────────────
// 阶段3：标定完成
// ──────────────────────────────────────────────
@Composable
private fun DoneStage(state: CalibUiState, onFinish: () -> Unit, onCancel: () -> Unit) {
    val zoomLabel = formatZoomLabel(state.zoomRatio)
    Text("✓ 标定完成！", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF81C784))
    Spacer(Modifier.height(6.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("已为", fontSize = 12.sp, color = Color(0xFFA5D6A7))
            Text(
                "${state.cameraLabel.ifBlank { "默认" }} · $zoomLabel ${String.format("%.1f", state.zoomRatio)}x",
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
            Text("保存标定", fontSize = 12.sp, color = Color(0xFFA5D6A7))
        }
    }
    Spacer(Modifier.height(12.dp))

    // 结果卡片
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF333333))
    ) {
        Column(Modifier.padding(14.dp)) {
            ResultRow("fx", "%.2f".format(state.fx ?: 0.0))
            ResultRow("fy", "%.2f".format(state.fy ?: 0.0))
            ResultRow("cx", state.cx?.let { "%.2f".format(it) } ?: "-")
            ResultRow("cy", state.cy?.let { "%.2f".format(it) } ?: "-")
            ResultRow("RMS 重投影误差", "%.4f px".format(state.reprojectionError ?: 0.0))
        }
    }

    if ((state.reprojectionError ?: 1.0) > 0.5) {
        Spacer(Modifier.height(8.dp))
        Text(
            "⚠ 重投影误差较大 (>0.5px)，建议增加样本或重新标定",
            fontSize = 11.sp,
            color = Color(0xFFFFB74D)
        )
    }

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC3F7))
    ) {
        Text("保存并使用", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TextButton(onClick = onCancel) {
            Text("重新标定", color = Color(0xFF4FC3F7))
        }
        TextButton(onClick = onCancel) {
            Text("丢弃并关闭", color = Color(0xFF999999))
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFFAAAAAA))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

// ──────────────────────────────────────────────
// 摄像头选择器（多摄像头分别标定）
// ──────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraSelector(
    labels: List<String>,
    selectedId: String,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedIndex = labels.indexOfFirst { it == selectedId }.coerceAtLeast(0)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = labels.getOrNull(selectedIndex) ?: "默认摄像头",
            onValueChange = {},
            readOnly = true,
            label = { Text("标定摄像头") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color(0xFF555555),
                focusedLabelColor = Color(0xFF4FC3F7),
                unfocusedLabelColor = Color(0xFF999999)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            labels.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label, color = Color.White) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// 变焦/镜头选择器（把每个 zoom ratio 映射为多摄手机的物理镜头）
// ──────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoomSelector(
    options: List<Float>,
    selected: Float,
    onSelect: (Float) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val labels = options.map { "${formatZoomLabel(it)} (${String.format("%.1f", it)}x)" }
    val selectedIndex = options.indexOfFirst { kotlin.math.abs(it - selected) < 0.05f }.coerceAtLeast(0)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = labels.getOrNull(selectedIndex) ?: "1.0x",
            onValueChange = {},
            readOnly = true,
            label = { Text("变焦 / 物理镜头") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color(0xFF555555),
                focusedLabelColor = Color(0xFF4FC3F7),
                unfocusedLabelColor = Color(0xFF999999)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, zoom ->
                DropdownMenuItem(
                    text = { Text(labels[index], color = Color.White) },
                    onClick = {
                        onSelect(zoom)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 把变焦比映射为更直观的镜头名称 */
private fun formatZoomLabel(zoom: Float): String = when {
    zoom < 0.8f -> "超广角"
    zoom < 1.2f -> "主摄"
    zoom < 2.5f -> "2x 长焦"
    zoom < 5.0f -> "5x 长焦"
    else -> "10x 长焦"
}

// ──────────────────────────────────────────────
// 简易数字选择器（加减按钮）
// ──────────────────────────────────────────────
@Composable
private fun GridNumberPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .background(Color(0xFF3A3A3A), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        TextButton(
            onClick = { if (value > range.first) onValueChange(value - 1) },
            modifier = Modifier.size(36.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("−", fontSize = 20.sp, color = Color(0xFF4FC3F7))
        }
        Text(
            "$value",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.widthIn(min = 36.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        TextButton(
            onClick = { if (value < range.last) onValueChange(value + 1) },
            modifier = Modifier.size(36.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("+", fontSize = 20.sp, color = Color(0xFF4FC3F7))
        }
    }
}
