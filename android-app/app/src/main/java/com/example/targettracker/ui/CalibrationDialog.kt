package com.example.targettracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.targettracker.ui.theme.*

/** 标定向导阶段 */
enum class CalibStage { INPUT, SAMPLING, DONE }

/** 标定向导 UI 状态 (由 MainActivity 持有/驱动) */
data class CalibUiState(
    val stage: CalibStage = CalibStage.INPUT,
    val progress: Float = 0f,
    val sampleCount: Int = 0,
    val requiredSamples: Int = 30,
    val resultFx: Double = 0.0,
    val resultFy: Double = 0.0,
    val resultErr: Double = 0.0,
    val correctionRatio: Double = 1.0,
    val hint: String = ""
)

@Composable
fun CalibrationDialog(
    uiState: CalibUiState,
    onStart: (Double, Double) -> Unit,   // (distanceMm, sizeMm)
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onAccept: () -> Unit,
    onClear: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .width(340.dp)
                .shadow(28.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(Surface1, Surface0)))
                .border(1.dp, HudLine.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(AccentTeal, AccentCyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("◎", color = BackgroundDark, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(12.dp))
                Text("距离标定", style = MaterialTheme.typography.titleLarge, color = HudWhite)
            }

            Spacer(Modifier.height(16.dp))

            when (uiState.stage) {
                CalibStage.INPUT -> InputStage(uiState, onStart, onCancel)
                CalibStage.SAMPLING -> SamplingStage(uiState, onCancel)
                CalibStage.DONE -> DoneStage(uiState, onAccept, onRetry, onClear)
            }
        }
    }
}

@Composable
private fun InputStage(
    uiState: CalibUiState,
    onStart: (Double, Double) -> Unit,
    onCancel: () -> Unit
) {
    var distance by remember { mutableStateOf("1000") }   // mm
    var size by remember { mutableStateOf("200") }        // mm
    val distOk = distance.toDoubleOrNull()?.let { it > 0 } == true
    val sizeOk = size.toDoubleOrNull()?.let { it > 0 } == true
    val canStart = distOk && sizeOk

    Text(
        "将靶标完整置于画面中央，输入其实测距离与直径，\n系统将自动采样焦距以校准测距。",
        style = MaterialTheme.typography.bodyMedium, color = HudGray
    )
    Spacer(Modifier.height(18.dp))

    LabeledField("靶标当前真实距离", distance, "mm", onValueChange = { distance = it }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("500", "1000", "2000", "3000").forEach { d ->
                val sel = distance == d
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) AccentTeal else Surface2)
                        .border(1.dp, if (sel) Color.Transparent else HudLine, RoundedCornerShape(10.dp))
                        .clickable { distance = d }
                        .padding(vertical = 9.dp)
                ) { Text("$d", color = if (sel) BackgroundDark else HudWhite, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    LabeledField("靶标真实直径", size, "mm", onValueChange = { size = it })

    Spacer(Modifier.height(22.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text("取消", color = HudGray)
        }
        Button(
            onClick = { onStart(distance.toDouble(), size.toDouble()) },
            enabled = canStart,
            modifier = Modifier.weight(1f)
        ) {
            Text("开始采样", color = BackgroundDark)
        }
    }
}

@Composable
private fun SamplingStage(uiState: CalibUiState, onCancel: () -> Unit) {
    Column {
        Text("采样中… 请保持靶标稳定居中",
            style = MaterialTheme.typography.bodyMedium, color = HudWhite)
        if (uiState.hint.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(uiState.hint, style = MaterialTheme.typography.labelSmall, color = HudYellow)
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = uiState.progress.coerceIn(0f, 1f),
            color = AccentTeal, trackColor = Surface2, strokeCap = StrokeCap.Round,
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("已采样", style = MaterialTheme.typography.labelSmall, color = HudGray)
            Text("${uiState.sampleCount} / ${uiState.requiredSamples} 帧",
                style = MaterialTheme.typography.displaySmall, color = AccentCyan)
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("取消", color = HudGray)
        }
    }
}

@Composable
private fun DoneStage(
    uiState: CalibUiState,
    onAccept: () -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit
) {
    // 质量: 重投影误差越小越好
    val err = uiState.resultErr
    val q = (0.6 / (0.6 + err)).coerceIn(0.0, 1.0)
    val qc = when {
        q >= 0.6f -> AccentGreen
        q >= 0.35f -> HudYellow
        else -> HudRed
    }
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2)
                .border(1.dp, qc.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("标定质量", style = MaterialTheme.typography.labelSmall, color = HudGray)
                    Text("${(q * 100).toInt()}%", style = MaterialTheme.typography.displaySmall, color = qc)
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = q.toFloat(),
                    color = qc, trackColor = BackgroundDark, strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.height(12.dp))
                Text("焦距 fx = ${"%.2f".format(uiState.resultFx)} px",
                    style = MaterialTheme.typography.displaySmall, color = HudWhite)
                Text("焦距 fy = ${"%.2f".format(uiState.resultFy)} px",
                    style = MaterialTheme.typography.displaySmall, color = HudWhite)
                Text("重投影误差 = ${"%.3f".format(err)} px",
                    style = MaterialTheme.typography.labelSmall, color = HudGray)
                Text("与默认内参比 = ${"%.2f".format(uiState.correctionRatio)}×",
                    style = MaterialTheme.typography.labelSmall, color = HudCyan)
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text("保存并应用", color = BackgroundDark)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) {
                Text("重测", color = HudGray)
            }
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text("清除", color = HudRed)
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    unit: String,
    onValueChange: (String) -> Unit,
    extra: (@Composable ColumnScope.() -> Unit)? = null
) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = AccentCyan)
    Spacer(Modifier.height(8.dp))
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = TextFieldDefaults.colors(
            focusedTextColor = HudWhite,
            unfocusedTextColor = HudWhite,
            focusedContainerColor = BackgroundDark,
            unfocusedContainerColor = BackgroundDark,
            cursorColor = AccentTeal,
            focusedIndicatorColor = AccentTeal,
            unfocusedIndicatorColor = HudLine
        ),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        suffix = { Text(unit, color = HudGray) },
        textStyle = MaterialTheme.typography.displaySmall
    )
    if (extra != null) {
        Spacer(Modifier.height(10.dp))
        Column(content = extra)
    }
}
