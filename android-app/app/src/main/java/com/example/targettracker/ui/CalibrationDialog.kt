package com.example.targettracker.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class CalibStage { INPUT, SAMPLING, DONE }

data class CalibUiState(
    val stage: CalibStage = CalibStage.INPUT,
    val gridCols: Int = 9,
    val gridRows: Int = 6,
    val squareSizeMm: String = "25",
    val requiredSamples: Int = 25,
    val sampleCount: Int = 0,
    val rejectReason: String? = null,
    val reprojectionError: Double? = null,
    val fx: Double? = null,
    val fy: Double? = null,
    val cx: Double? = null,
    val cy: Double? = null,
    val previewBitmap: Bitmap? = null
)

@Composable
fun CalibrationDialog(
    state: CalibUiState,
    onGridColsChange: (Int) -> Unit = {},
    onGridRowsChange: (Int) -> Unit = {},
    onSquareSizeChange: (String) -> Unit = {},
    onStartSampling: (Int, Int, Double) -> Unit = { _, _, _ -> },
    onFinish: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Dialog(
        onDismissRequest = { if (state.stage != CalibStage.SAMPLING) onCancel() },
        properties = DialogProperties(
            dismissOnBackPress = state.stage != CalibStage.SAMPLING,
            dismissOnClickOutside = state.stage != CalibStage.SAMPLING
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2B2B))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state.stage) {
                    CalibStage.INPUT -> InputStage(
                        state, onGridColsChange, onGridRowsChange,
                        onSquareSizeChange, onStartSampling, onCancel
                    )
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
    onGridColsChange: (Int) -> Unit,
    onGridRowsChange: (Int) -> Unit,
    onSquareSizeChange: (String) -> Unit,
    onStartSampling: () -> Unit,
    onCancel: () -> Unit
) {
    Text("棋盘格相机标定", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Spacer(Modifier.height(6.dp))
    Text(
        "请准备一张 9×6 内角点棋盘格（每格 25mm），将其打印或显示在屏幕上。\n标定时请从不同角度/距离拍摄棋盘格。",
        fontSize = 12.sp, color = Color(0xFFAAAAAA)
    )
    Spacer(Modifier.height(16.dp))

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
                value = state.gridCols,
                onValueChange = onGridColsChange,
                range = 5..12
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("内角行数", fontSize = 13.sp, color = Color(0xFFCCCCCC))
            Spacer(Modifier.height(4.dp))
            GridNumberPicker(
                value = state.gridRows,
                onValueChange = onGridRowsChange,
                range = 4..9
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = state.squareSizeMm,
        onValueChange = onSquareSizeChange,
        label = { Text("每格边长 (mm)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
            val sqSize = state.squareSizeMm.toDoubleOrNull() ?: return@Button
            onStartSampling(state.gridCols, state.gridRows, sqSize)
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC3F7)),
        enabled = state.squareSizeMm.toDoubleOrNull()?.let { it > 0 } == true
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
    Spacer(Modifier.height(6.dp))
    Text(
        "请从不同角度和距离对准棋盘格，确保每个格子清晰可见",
        fontSize = 12.sp, color = Color(0xFFAAAAAA)
    )
    Spacer(Modifier.height(12.dp))

    // 预览画面 + 棋盘格角点绘制
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1A))
            .border(1.dp, Color(0xFF444444), RoundedCornerShape(8.dp))
    ) {
        val bmp = state.previewBitmap
        if (bmp != null) {
            val imgBitmap: ImageBitmap = bmp.asImageBitmap()
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawImage(imgBitmap)
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("等待画面…", color = Color(0xFF666666))
            }
        }
    }

    Spacer(Modifier.height(10.dp))

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
        Spacer(Modifier.height(4.dp))
        Text(state.rejectReason, fontSize = 11.sp, color = Color(0xFFFF8A65))
    } else if (state.sampleCount > 0) {
        Spacer(Modifier.height(4.dp))
        Text("✓ 角点检测成功", fontSize = 11.sp, color = Color(0xFF81C784))
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
    Text("标定完成！", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF81C784))
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
