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
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
    // 注意: Mat 必须在 OpenCV native 库加载后才能创建, 故延迟初始化, 不在属性声明处 new
    private lateinit var cameraMatrix: Mat
    private lateinit var distCoeffs: Mat
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

            // OpenCV native 库由 AppApplication.onCreate 中的 System.loadLibrary 加载
            // 4.x 官方 AAR 的 initLocal() 不再执行加载, 故直接检查加载结果
            val opencvOk = AppApplication.openCvLoaded
            if (!opencvOk) {
                Log.e(TAG, "OpenCV native library not loaded")
                writeCrash("onCreate-OpenCV", RuntimeException("OpenCV native library not loaded"))
            }

            // 初始化标定 Mat (必须在 native 库加载之后)
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

            // ImageAnalysis: 强制 640x480, 避开 CameraX 在 Android 14+ 自动选 maxRes (2448x2448)
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
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

        // YUV → Gray (Mat + ByteArray 共用同一份数据)
        val (gray, grayData) = imageProxyToGray(imageProxy) ?: return
        val grayW = imageProxy.width
        val grayH = imageProxy.height
        val timestamp = System.currentTimeMillis()

        try {
            // 检测
            val detResults = detector.detect(
                gray,
                grayData,
                grayW,
                grayH,
                if (useCalibration) cameraMatrix else null,
                if (useCalibration) distCoeffs else null
            )

            // 测量
            val dispResults = engine.measureAll(detResults, timestamp)

            // 更新状态 (必须在主线程)
            runOnUiThread {
                state.detectResults = detResults
                state.dispResults = dispResults
                state.imageSize = grayW to grayH
                state.frameNum++
                state.stats = engine.getStats()
            }
        } finally {
            gray.release() // 释放每帧灰度 Mat, 避免内存泄漏导致后续帧检测失败
        }
    }

    // 复用 ByteArray 缓冲, 避免每帧分配
    private var grayBuffer: ByteArray = ByteArray(0)
    @Synchronized
    private fun acquireGrayBuffer(size: Int): ByteArray {
        if (grayBuffer.size < size) {
            grayBuffer = ByteArray(size)
        }
        return grayBuffer
    }

    private fun imageProxyToGray(imageProxy: androidx.camera.core.ImageProxy): Pair<Mat, ByteArray>? {
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
            // 复用 grayBuffer (避免每帧分配)
            val data = acquireGrayBuffer(width * height)

            if (pixelStride == 1) {
                buffer.position(0)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(data, row * width, width)
                }
            } else {
                buffer.position(0)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    val n = kotlin.math.min(width, rowStride - pixelStride + 1)
                    buffer.get(data, row * width, n)
                    // 处理不足部分: 0 填充
                    for (k in n until width) data[row * width + k] = 0
                }
            }
            gray.put(0, 0, data)
            return gray to data
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
        if (::cameraMatrix.isInitialized) cameraMatrix.release()
        if (::distCoeffs.isInitialized) distCoeffs.release()
    }
}
