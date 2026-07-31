package com.example.targettracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onZeroReset: () -> Unit,
    onCalibrate: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = state.primaryDisplacement
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧: 归零按钮
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

        // 右侧: 状态信息 + 操作
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 靶标数量
            val activeTargets = state.stats["activeTargets"] as? Int ?: 0
            Text(
                "靶标: $activeTargets",
                style = MaterialTheme.typography.labelSmall,
                color = HudWhite
            )

            Spacer(Modifier.width(16.dp))

            // 帧率
            Text(
                "%.0f fps".format(state.fps),
                style = MaterialTheme.typography.labelSmall,
                color = HudYellow
            )

            Spacer(Modifier.width(12.dp))

            // 重置
            IconButton(onClick = onReset, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "重置", tint = HudWhite)
            }

            // 标定
            IconButton(onClick = onCalibrate, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Tune, contentDescription = "标定", tint = HudWhite)
            }
        }
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
