package com.example.targettracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.compose.runtime.mutableStateOf
import androidx.camera.camera2.interop.Camera2CameraInfo
import android.hardware.camera2.CameraCharacteristics
import com.example.targettracker.camera.CameraAnalyzer
import com.example.targettracker.config.CalibrationData
import com.example.targettracker.config.CalibrationStore
import com.example.targettracker.config.Config
import com.example.targettracker.config.DistanceCalibrator
import com.example.targettracker.ui.CalibStage
import com.example.targettracker.ui.CalibUiState
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

        val resolutionOptions = listOf(
            android.util.Size(640, 480),
            android.util.Size(1280, 720),
            android.util.Size(1920, 1080),
            android.util.Size(2560, 1440),
            android.util.Size(3840, 2160)
        )
        val resolutionLabels = listOf("640x480", "1280x720", "1920x1080", "2560x1440", "3840x2160")
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

    // ── 相机启动防并发 guard ──
    @Volatile private var cameraStarting = false
    @Volatile private var pendingCameraStart = false

    // ──── 多摄像头支持 ────
    data class CameraOption(
        val id: String,            // Camera2 cameraId (如 "0", "1")
        val lensFacing: Int,       // CameraSelector.LENS_FACING_BACK / FRONT
        val focalLengths: FloatArray, // 物理焦距 (mm), 用于区分广角/长焦
        val label: String,
        val cameraInfo: CameraInfo
    )

    private var availableCameras = mutableListOf<CameraOption>()
    // Compose 可观察状态: 摄像头标签列表 & 当前索引 (供 UI 重组)
    private val cameraLabelsState = mutableStateOf<List<String>>(emptyList())
    private val cameraIndexState = mutableStateOf(0)
    private var currentCameraIndex = 0
    // 当前选中的 CameraInfo (用于 CameraSelector.addCameraFilter)
    private var selectedCameraInfo: CameraInfo? = null

    // ── 变焦控制 (后摄是逻辑多摄, 超广角/长焦通过变焦比切换物理镜头) ──
    private var boundCamera: androidx.camera.core.Camera? = null
    val zoomRatiosState = mutableStateOf<List<Float>>(emptyList())
    val currentZoomState = mutableStateOf(1.0f)
    // 当前是否前摄 (前摄画面水平镜像, 需翻转后再检测)
    @Volatile private var isFrontCamera = false

    // ──── 距离标定 ────
    /** 标定向导 UI 状态 (null = 未打开) */
    val calibUiState = mutableStateOf<CalibUiState?>(null)
    /** 采样中的标定器, 由检测线程投喂 */
    @Volatile private var activeCalibrator: DistanceCalibrator? = null
    /** 采样完成待用户确认的结果 */
    private var pendingCalib: CalibrationData? = null

    /** 当前摄像头的 Camera2 id, 作为标定存储的键 */
    private fun currentCameraId(): String =
        availableCameras.getOrNull(currentCameraIndex)?.id ?: "0"

    private fun currentCameraLabel(): String =
        availableCameras.getOrNull(currentCameraIndex)?.label ?: "默认摄像头"

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
                            cameraLabels = cameraLabelsState.value,
                            currentCameraIndex = cameraIndexState.value,
                            onSwitchCamera = { idx -> switchCamera(idx) },
                            zoomRatios = zoomRatiosState.value,
                            currentZoom = currentZoomState.value,
                            onSetZoom = { z -> setZoom(z) },
                            onCameraReady = { pv ->
                                previewView = pv
                                if (availableCameras.isEmpty()) {
                                    enumerateCameras()
                                    startCamera()
                                }
                            },
                            onZeroReset = {
                                state.zeroed = !state.zeroed
                                if (state.zeroed) engine.setZero()
                                else engine.reset()
                            },
                            onCalibrate = { openCalibration() },
                            calibUi = calibUiState.value,
                            calibCameraLabel = currentCameraLabel(),
                            calibHasExisting = CalibrationStore.load(
                                this@MainActivity, currentCameraId(), currentZoomState.value
                            ) != null,
                            onCalibStart = { dist, size -> startCalibration(dist, size) },
                            onCalibCancel = { cancelCalibration() },
                            onCalibAccept = { acceptCalibration() },
                            onCalibRetry = { retryCalibration() },
                            onCalibClear = { clearCalibration() },
                            onReset = {
                                engine.reset()
                                state.zeroed = false
                            },
                            onSetTargetSize = { tid, mm -> detector.setTargetSize(tid, mm) },
                            resolutionLabels = resolutionLabels,
                            currentResolutionIndex = state.resolutionIndex,
                            onSetResolution = { idx -> setResolution(idx) }
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
        if (cameraStarting) {
            Log.d(TAG, "startCamera 排队: 上次未完成")
            pendingCameraStart = true
            return
        }
        cameraStarting = true
        pendingCameraStart = false
        previewView?.let { startCameraWithPreview(it) }
            ?: run { cameraStarting = false }
    }

    /** 枚举设备所有摄像头 (后摄/前摄/超广角/长焦) */
    private fun enumerateCameras() {
        try {
            val provider = ProcessCameraProvider.getInstance(this).get()
            val infos = provider.availableCameraInfos
            availableCameras.clear()
            for (info in infos) {
                val c2 = Camera2CameraInfo.from(info)
                val id = c2.cameraId
                // 镜头朝向: 从 CameraCharacteristics.LENS_FACING 读取 (Int: 1=后摄, 0=前摄, 2=外置)
                val facingKey = android.hardware.camera2.CameraCharacteristics.LENS_FACING
                val facingRaw: Int = try {
                    val f = c2.getCameraCharacteristic(facingKey)
                    f ?: CameraSelector.LENS_FACING_BACK
                } catch (_: Exception) { CameraSelector.LENS_FACING_BACK }
                val lensFacing = facingRaw
                // 物理焦距 (用于区分广角/长焦); getCameraCharacteristic 返回 FloatArray?
                val focalKey = android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                val focals: FloatArray = try {
                    val ch = c2.getCameraCharacteristic(focalKey)
                    ch ?: floatArrayOf(0f)
                } catch (_: Exception) { floatArrayOf(0f) }
                val facingStr = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "前摄" else "后摄"
                val minFocal = focals.minOrNull() ?: 0f
                // 逻辑多摄: 读取隐藏的物理镜头数量 (本机后摄 physicalIds=[3,2,4,5])
                val physCount = try {
                    val cm = getSystemService(android.content.Context.CAMERA_SERVICE)
                        as android.hardware.camera2.CameraManager
                    cm.getCameraCharacteristics(id).physicalCameraIds.size
                } catch (_: Exception) { 0 }
                // 变焦范围: 决定能否够到超广角(<1x)与长焦(>1x)
                val zr = try {
                    c2.getCameraCharacteristic(
                        android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
                    )
                } catch (_: Exception) { null }
                val zoomStr = if (zr != null) " ${zr.lower}~${zr.upper}x" else ""
                val physStr = if (physCount > 1) "·${physCount}镜头" else ""
                val label = "摄像头$id $facingStr$physStr (${"%.1f".format(minFocal)}mm)$zoomStr"
                availableCameras.add(CameraOption(id, lensFacing, focals, label, info))
            }
            // 默认选中第一个后摄, 没有则第一个
            if (availableCameras.isNotEmpty()) {
                val backIdx = availableCameras.indexOfFirst { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                currentCameraIndex = if (backIdx >= 0) backIdx else 0
                selectedCameraInfo = availableCameras[currentCameraIndex].cameraInfo
            }
            // 发布到 Compose 状态, 触发 UI 重组 (否则弹窗因列表为空而不显示)
            cameraLabelsState.value = availableCameras.map { it.label }
            cameraIndexState.value = currentCameraIndex
            Log.i(TAG, "enumerateCameras: found ${availableCameras.size} -> ${cameraLabelsState.value}")
        } catch (e: Exception) {
            Log.e(TAG, "enumerateCameras failed: ${e.message}")
        }
    }

    /** 根据当前选中摄像头构造 CameraSelector */
    private fun buildCameraSelector(): CameraSelector {
        val info = selectedCameraInfo
        return if (info != null) {
            CameraSelector.Builder()
                .addCameraFilter { cameraInfos -> cameraInfos.filter { it == info } }
                .build()
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    // CameraFilter 的入参是 Camera, 通过 cameraInfo 比较

    /** 由 UI 调用: 切换摄像头 */
    fun switchCamera(index: Int) {
        if (index < 0 || index >= availableCameras.size) return
        currentCameraIndex = index
        cameraIndexState.value = index
        selectedCameraInfo = availableCameras[index].cameraInfo
        Log.i(TAG, "switchCamera -> $index ${availableCameras[index].label}")
        // 重新绑定相机 (会 unbindAll 后重新 bind)
        startCamera()
    }

    /** 由 UI 调用: 切换分辨率 */
    fun setResolution(index: Int) {
        val idx = index.coerceIn(0, resolutionOptions.size - 1)
        state.resolutionIndex = idx
        Log.i(TAG, "setResolution -> $idx ${resolutionLabels[idx]}")
        // 重启相机以应用新分辨率
        startCamera()
    }

    /**
     * 由 UI 调用: 设置变焦比。
     * 后摄是逻辑多摄 (physicalIds=[3,2,4,5]), 系统会根据变焦比自动切到
     * 对应物理镜头: <1x 走超广角, 1x 主摄, >=2x 走长焦。
     */
    fun setZoom(ratio: Float) {
        val cam = boundCamera ?: return
        val zs = cam.cameraInfo.zoomState.value
        val minZ = zs?.minZoomRatio ?: 1.0f
        val maxZ = zs?.maxZoomRatio ?: 1.0f
        val r = ratio.coerceIn(minZ, maxZ)
        cam.cameraControl.setZoomRatio(r)
        currentZoomState.value = r
        // 切换变焦档等于换了焦距, 重新套用该档的标定值 (没有则回落默认内参)
        applyStoredCalibration()
        Log.i(TAG, "setZoom -> $r")
    }

    // ──────────── 距离标定流程 ────────────

    /** 打开标定向导 */
    private fun openCalibration() {
        activeCalibrator = null
        pendingCalib = null
        calibUiState.value = CalibUiState(stage = CalibStage.INPUT)
    }

    /** 开始采样: 用户已填入实测距离与靶标直径 */
    private fun startCalibration(distanceMm: Double, sizeMm: Double) {
        val calibrator = DistanceCalibrator(
            targetSizeMm = sizeMm,
            knownDistanceMm = distanceMm
        )
        activeCalibrator = calibrator
        calibUiState.value = CalibUiState(
            stage = CalibStage.SAMPLING,
            requiredSamples = calibrator.requiredSamples,
            hint = "将靶标完整置于画面中"
        )
        Log.i(TAG, "calibration started D=$distanceMm W=$sizeMm")
    }

    /** 取消 / 关闭向导 */
    private fun cancelCalibration() {
        activeCalibrator = null
        pendingCalib = null
        calibUiState.value = null
    }

    /** 重新采样 (回到输入阶段) */
    private fun retryCalibration() {
        activeCalibrator = null
        pendingCalib = null
        calibUiState.value = CalibUiState(stage = CalibStage.INPUT)
    }

    /** 保存标定结果并立即生效 */
    private fun acceptCalibration() {
        val data = pendingCalib
        if (data == null) {
            cancelCalibration()
            return
        }
        CalibrationStore.save(this, currentCameraId(), currentZoomState.value, data)
        calibData = data
        useCalibration = true
        updateCalibrationMats()
        state.calibrationData = calibData
        state.warningMessage = null
        activeCalibrator = null
        pendingCalib = null
        calibUiState.value = null
        Toast.makeText(this, "标定已保存, 距离测量已启用实测焦距", Toast.LENGTH_SHORT).show()
    }

    /** 清除当前镜头/变焦档的标定 */
    private fun clearCalibration() {
        CalibrationStore.clear(this, currentCameraId(), currentZoomState.value)
        calibData = CalibrationData()
        useCalibration = false
        updateCalibrationMats()
        state.calibrationData = calibData
        state.warningMessage = "相机未标定 (使用默认内参)"
        calibUiState.value = null
        Toast.makeText(this, "已清除该镜头的标定", Toast.LENGTH_SHORT).show()
    }

    /** 根据当前摄像头 + 变焦档载入已保存的标定值 */
    private fun applyStoredCalibration() {
        val stored = CalibrationStore.load(this, currentCameraId(), currentZoomState.value)
        if (stored != null && stored.isValid) {
            calibData = stored
            useCalibration = true
            state.warningMessage = null
            Log.i(TAG, "applied stored calib for ${currentCameraId()}@${currentZoomState.value}")
        } else {
            calibData = CalibrationData()
            useCalibration = false
            state.warningMessage = "相机未标定 (使用默认内参)"
        }
        if (::cameraMatrix.isInitialized) updateCalibrationMats()
        state.calibrationData = calibData
    }

    /**
     * 由检测线程调用: 投喂一帧检测结果给标定器。
     * 采样满后计算结果并切到 DONE 阶段等待用户确认。
     */
    private fun feedCalibration(
        results: Map<Int, com.example.targettracker.detector.DetectionResult>,
        width: Int,
        height: Int
    ) {
        val calibrator = activeCalibrator ?: return
        calibrator.feed(results, width, height)

        if (calibrator.isComplete) {
            val result = calibrator.finish()
            activeCalibrator = null
            if (result == null) {
                runOnUiThread {
                    calibUiState.value = CalibUiState(
                        stage = CalibStage.INPUT,
                        hint = "样本不足, 请重试"
                    )
                }
                return
            }
            pendingCalib = result
            // 与默认内参对比, 得出距离修正比例 (默认 fx = w*0.866*zoom)
            val defFx = width / (2.0 * kotlin.math.tan(Math.toRadians(30.0))) *
                    currentZoomState.value.toDouble().coerceAtLeast(0.1)
            val newFx = result.cameraMatrix[0]
            val ratio = if (defFx > 0) newFx / defFx else 1.0
            runOnUiThread {
                calibUiState.value = CalibUiState(
                    stage = CalibStage.DONE,
                    progress = 1f,
                    sampleCount = calibrator.requiredSamples,
                    requiredSamples = calibrator.requiredSamples,
                    resultFx = result.cameraMatrix[0],
                    resultFy = result.cameraMatrix[4],
                    resultErr = result.reprojectionError,
                    correctionRatio = ratio
                )
            }
        } else {
            val n = calibrator.sampleCount
            val p = calibrator.progress
            val hint = calibrator.lastReject ?: "采样中, 保持稳定"
            runOnUiThread {
                val cur = calibUiState.value
                if (cur != null && cur.stage == CalibStage.SAMPLING) {
                    calibUiState.value = cur.copy(
                        progress = p,
                        sampleCount = n,
                        hint = hint
                    )
                }
            }
        }
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

            // ImageAnalysis: 使用用户选择的分辨率
            val resIdx = state.resolutionIndex.coerceIn(0, resolutionOptions.size - 1)
            val targetSize = resolutionOptions[resIdx]
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        targetSize,
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

            // 选择摄像头: 优先用枚举选中的 CameraInfo (支持多摄像头/超广角/长焦/前摄)
            val cameraSelector = buildCameraSelector()

            try {
                cameraProvider.unbindAll()
                val cam = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                boundCamera = cam
                // 记录是否前摄: 前摄画面水平镜像, 需在检测前翻转
                isFrontCamera = availableCameras.getOrNull(currentCameraIndex)
                    ?.lensFacing == CameraSelector.LENS_FACING_FRONT
                // 发布该摄像头支持的变焦档位 (逻辑多摄靠变焦切换超广角/长焦物理镜头)
                val zs = cam.cameraInfo.zoomState.value
                val minZ = zs?.minZoomRatio ?: 1.0f
                val maxZ = zs?.maxZoomRatio ?: 1.0f
                val presets = listOf(0.6f, 1.0f, 2.0f, 3.0f, 5.0f, 10.0f)
                    .filter { it in minZ..maxZ }
                zoomRatiosState.value = if (presets.isEmpty()) listOf(1.0f) else presets
                currentZoomState.value = zs?.zoomRatio ?: 1.0f
                // 套用该摄像头/变焦档已保存的标定值
                applyStoredCalibration()
                Log.i(TAG, "bound cam front=$isFrontCamera zoom=$minZ~$maxZ presets=${zoomRatiosState.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
                state.warningMessage = "相机初始化失败"
            } finally {
                // ── 防并发: 标记本次 startCamera 完成，处理排队请求 ──
                cameraStarting = false
                if (pendingCameraStart) {
                    Log.d(TAG, "执行排队的 camera start")
                    startCamera()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 处理单帧 — 检测 + 测量 + 更新状态
     */
    private fun processFrame(imageProxy: androidx.camera.core.ImageProxy) {
        val width = imageProxy.width
        val height = imageProxy.height

        if (useCalibration && calibData.isValid) {
            // 有真实标定: 按当前分辨率缩放内参
            val scaleX = width.toDouble() / calibData.imageWidth
            val scaleY = height.toDouble() / calibData.imageHeight
            val cm = calibData.cameraMatrix.copyOf()
            cm[0] *= scaleX; cm[2] *= scaleX
            cm[4] *= scaleY; cm[5] *= scaleY
            cameraMatrix.put(0, 0, *cm)
        } else {
            // 默认内参: 近似 60° 水平 FOV (fx ≈ width / (2*tan(30°)) ≈ width*0.866)
            // 变焦会等效放大焦距, 需按变焦比缩放, 否则距离估算随变焦漂移
            val zoom = currentZoomState.value.toDouble().coerceAtLeast(0.1)
            val fx = width / (2.0 * kotlin.math.tan(Math.toRadians(30.0))) * zoom
            val fy = height / (2.0 * kotlin.math.tan(Math.toRadians(30.0))) * zoom
            cameraMatrix.put(0, 0, fx, 0.0, width / 2.0, 0.0, fy, height / 2.0, 0.0, 0.0, 1.0)
        }

        // YUV → Gray (Mat + ByteArray 共用同一份数据)
        var (gray, grayData) = imageProxyToGray(imageProxy) ?: return
        val grayW = imageProxy.width
        val grayH = imageProxy.height
        val timestamp = System.currentTimeMillis()

        // 前摄传感器画面水平镜像, 会导致靶标黑白象限的对角关系反转,
        // 从而无法通过检测器的对角校验 -> 检测前先水平翻转还原
        if (isFrontCamera) {
            val flipped = Mat()
            org.opencv.core.Core.flip(gray, flipped, 1)
            gray.release()
            gray = flipped
            // grayData 与 Mat 数据需保持一致 (检测器同时使用两者)
            if (grayData.size >= grayW * grayH) {
                gray.get(0, 0, grayData)
            }
        }

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

            // 标定采样 (向导打开且处于采样阶段时才会消费)
            if (activeCalibrator != null) {
                feedCalibration(detResults, grayW, grayH)
            }

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

    /**
     * 刷新内参 Mat。
     *
     * 该方法会被反复调用 (切摄像头/切变焦/保存标定), 而检测线程同时在读 cameraMatrix,
     * 因此只在首次分配 Mat, 之后原地覆写数据 —— 避免 release 掉正被 native 使用的 Mat。
     */
    @Synchronized
    private fun updateCalibrationMats() {
        if (!::cameraMatrix.isInitialized) {
            cameraMatrix = Mat(3, 3, org.opencv.core.CvType.CV_64F)
        }
        if (!::distCoeffs.isInitialized) {
            distCoeffs = Mat(5, 1, org.opencv.core.CvType.CV_64F)
        }

        if (useCalibration) {
            cameraMatrix.put(0, 0, *calibData.cameraMatrix)
            distCoeffs.put(0, 0, *calibData.distCoeffs)
        } else {
            cameraMatrix.put(0, 0,
                1000.0, 0.0, 640.0,
                0.0, 1000.0, 360.0,
                0.0, 0.0, 1.0
            )
            distCoeffs.put(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdownNow()
        if (::cameraMatrix.isInitialized) cameraMatrix.release()
        if (::distCoeffs.isInitialized) distCoeffs.release()
    }
}
