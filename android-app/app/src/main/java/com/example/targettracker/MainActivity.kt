package com.example.targettracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.targettracker.camera.CameraAnalyzer
import com.example.targettracker.config.CalibrationData
import com.example.targettracker.config.Config
import com.example.targettracker.detector.TargetDetector
import com.example.targettracker.engine.DisplacementEngine
import com.example.targettracker.ui.MainScreen
import com.example.targettracker.ui.ErrorScreen
import com.example.targettracker.ui.theme.TargetTrackerTheme
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.util.concurrent.Executors

/**
 * 靶标追踪 Android 入口
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TargetTracker"
    }

    private val state = TargetTrackerState()

    // 核心模块
    private val detector = TargetDetector(Config.target)
    private val engine = DisplacementEngine(Config.measure)

    // 相机分析线程
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // 标定数据 (默认内参)
    private var calibData = CalibrationData()
    private var cameraMatrix: Mat = Mat()
    private var distCoeffs: Mat = Mat()
    private var useCalibration = false

    // 帧计数
    private var frameCount = 0L
    private var lastFpsTime = System.nanoTime()

    // 已有相机绑定的 previewView
    private var previewView: PreviewView? = null

    // ──── 权限请求 ────
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能使用", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            // 初始化 OpenCV (官方 AAR 自带 native 库, 4.11.0 使用 initLocal)
            var opencvOk = false
            try {
                opencvOk = OpenCVLoader.initLocal()
                if (!opencvOk) {
                    Log.e(TAG, "OpenCV initLocal returned false")
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenCV init failed: ${e.message}")
                writeCrash("onCreate-OpenCV", e)
            }

            // 初始化标定 Mat
            updateCalibrationMats()

            setContent {
                TargetTrackerTheme {
                    if (opencvOk) {
                        MainScreen(
                            state = state,
                            onCameraReady = { pv ->
                                previewView = pv
                                startCameraWithPreview(pv)
                            },
                            onZeroReset = {
                                state.zeroed = !state.zeroed
                                if (state.zeroed) engine.setZero()
                                else engine.reset()
                            },
                            onCalibrate = {
                                Toast.makeText(this@MainActivity, "标定功能: 请使用桌面版或导入标定文件", Toast.LENGTH_SHORT).show()
                            },
                            onReset = {
                                engine.reset()
                                state.zeroed = false
                            }
                        )
                    } else {
                        // OpenCV 加载失败: 显示错误界面, 不再启动相机
                        ErrorScreen("OpenCV 初始化失败，应用无法运行。\n请查看 crash.log 获取详情。")
                    }
                }
            }

            // 请求相机权限
            if (opencvOk) {
                if (checkCameraPermission()) {
                    // 权限已有, 等 Compose 就绪后会调用 onCameraReady
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate crashed", e)
            writeCrash("onCreate", e)
            throw e
        }
    }

    private fun writeCrash(tag: String, t: Throwable) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = java.io.File(dir, "crash.log")
            java.io.FileWriter(f, true).use { w ->
                w.appendLine("==== $tag @ ${System.currentTimeMillis()} ====")
                w.appendLine((t.javaClass.name) + ": " + (t.message ?: "no msg"))
                t.stackTrace.forEach { w.appendLine("    at $it") }
                var c = t.cause
                while (c != null) {
                    w.appendLine("Caused: " + c.javaClass.name + ": " + (c.message ?: ""))
                    c.stackTrace.forEach { w.appendLine("    at $it") }
                    c = c.cause
                }
                w.appendLine("====================")
            }
        } catch (_: Exception) { }
    }

    private fun checkCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    // ──── 相机启动 ────
    private fun startCamera() {
        previewView?.let { startCameraWithPreview(it) }
    }

    private fun startCameraWithPreview(pv: PreviewView) {
        if (!checkCameraPermission()) return

        // 更新标定状态
        state.calibrationData = calibData
        state.warningMessage = if (!useCalibration || !calibData.isValid)
            "相机未标定 (使用默认内参)" else null

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }

            // ImageAnalysis
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // 分析器回调
            var grayCache: Mat? = null
            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val startNs = System.nanoTime()
                processFrame(imageProxy)
                imageProxy.close()

                // FPS
                frameCount++
                val now = System.nanoTime()
                val elapsed = (now - lastFpsTime) / 1_000_000_000.0
                if (elapsed >= 1.0) {
                    state.fps = frameCount / elapsed
                    frameCount = 0
                    lastFpsTime = now
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
                state.warningMessage = "相机初始化失败"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 处理单帧 — 检测 + 测量 + 更新状态
     */
    private fun processFrame(imageProxy: androidx.camera.core.ImageProxy) {
        val width = imageProxy.width
        val height = imageProxy.height

        // 自适应内参缩放
        val scaleX = width.toDouble() / calibData.imageWidth
        val scaleY = height.toDouble() / calibData.imageHeight

        if (kotlin.math.abs(scaleX - 1.0) > 0.01 || kotlin.math.abs(scaleY - 1.0) > 0.01) {
            // 缩放内参（如果有标定数据）
            if (useCalibration) {
                val cm = calibData.cameraMatrix.copyOf()
                cm[0] *= scaleX; cm[2] *= scaleX
                cm[4] *= scaleY; cm[5] *= scaleY
                cameraMatrix.put(0, 0, *cm)
            } else {
                // 默认内参: 近似 60度 FOV
                val fx = width * 1.2; val fy = height * 1.2
                cameraMatrix.put(0, 0, fx, 0.0, width / 2.0, 0.0, fy, height / 2.0, 0.0, 0.0, 1.0)
            }
        }

        // YUV → Gray
        val gray = imageProxyToGray(imageProxy) ?: return
        val timestamp = System.currentTimeMillis()

        // 检测
        val detResults = detector.detect(
            gray,
            if (useCalibration) cameraMatrix else null,
            if (useCalibration) distCoeffs else null
        )

        // 测量
        val dispResults = engine.measureAll(detResults, timestamp)

        // 更新状态 (必须在主线程)
        runOnUiThread {
            state.detectResults = detResults
            state.dispResults = dispResults
            state.frameNum++
            state.stats = engine.getStats()
        }
    }

    private fun imageProxyToGray(imageProxy: androidx.camera.core.ImageProxy): Mat? {
        try {
            val planes = imageProxy.planes
            if (planes.isEmpty()) return null

            val yPlane = planes[0]
            val buffer = yPlane.buffer
            val pixelStride = yPlane.pixelStride
            val rowStride = yPlane.rowStride
            val width = imageProxy.width
            val height = imageProxy.height

            val gray = Mat(height, width, org.opencv.core.CvType.CV_8UC1)

            if (pixelStride == 1) {
                // Y平面连续
                val data = ByteArray(height * width)
                buffer.position(0)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(data, row * width, width)
                }
                gray.put(0, 0, data)
            } else {
                // 逐行拷贝
                buffer.position(0)
                val rowData = ByteArray(width)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(rowData, 0, kotlin.math.min(width, rowStride - pixelStride + 1))
                    gray.put(row, 0, rowData)
                }
            }
            return gray
        } catch (e: Exception) {
            Log.e(TAG, "YUV conversion failed: ${e.message}")
            return null
        }
    }

    private fun updateCalibrationMats() {
        cameraMatrix = Mat(3, 3, org.opencv.core.CvType.CV_64F)
        distCoeffs = Mat(5, 1, org.opencv.core.CvType.CV_64F)

        if (useCalibration) {
            cameraMatrix.put(0, 0, *calibData.cameraMatrix)
            distCoeffs.put(0, 0, *calibData.distCoeffs)
        } else {
            cameraMatrix.put(0, 0,
                1000.0, 0.0, 640.0,
                0.0, 1000.0, 360.0,
                0.0, 0.0, 1.0
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdownNow()
        cameraMatrix.release()
        distCoeffs.release()
    }
}
