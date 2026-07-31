package com.example.targettracker.ui

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.targettracker.TargetTrackerState
import com.example.targettracker.ui.theme.*

/**
 * 主画面 — 相机预览 + HUD叠加 + 控制栏
 */
@Composable
fun MainScreen(
    state: TargetTrackerState,
    onCameraReady: (PreviewView) -> Unit,
    onZeroReset: () -> Unit,
    onCalibrate: () -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        // ──── 相机预览 (全屏背景) ────
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(this.surfaceProvider)
                        }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, cameraSelector, preview
                            )
                            onCameraReady(this)
                        } catch (e: Exception) {
                            // 权限或设备问题, 稍后在 MainActivity 处理
                            android.util.Log.e("TargetTracker", "Camera bind failed: ${e.message}")
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ──── HUD 叠加层 ────
        HUDOverlay(
            state = state,
            modifier = Modifier.align(Alignment.TopStart)
        )

        // ──── 底部控制栏 ────
        ControlBar(
            state = state,
            onZeroReset = onZeroReset,
            onCalibrate = onCalibrate,
            onReset = onReset,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
