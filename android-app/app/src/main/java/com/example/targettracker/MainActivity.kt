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
import com.example.targettracker.config.CheckerboardCalibrator
import com.example.targettracker.config.Config
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
import java.util.concurrent.LinkedBlockingQueue

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

    // 相机分析线程 (单线程: 只做轻量预处理, 不做检测)
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // ── 多线程流水线解耦 ──
    // FrameJob: 预处理后的灰度帧 + 相机参数 (检测线程消费)
    private data class FrameJob(
        val gray: Mat,
        val grayW: Int,
        val grayH: Int,
        val timestamp: Long,
        val cameraMatrix: Mat?,
        val distCoeffs: Mat?
    )

    // 容量1的阻塞队列: 始终只保留最新帧, 旧帧自动丢弃
    private val frameQueue = LinkedBlockingQueue<FrameJob>(1)

    // 检测线程: 从队列取帧 → detect → measure → 抛结果到主线程
    private val detectionThread: Thread = Thread({
        detectionLoop()
    }, "TargetDetection").apply {
        isDaemon = true
        start()
    }

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
        val sensorWidthMm: Float,  // 传感器物理宽度 (mm)
        val sensorHeightMm: Float, // 传感器物理高度 (mm)
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

    // ──── 棋盘格标定 ────
    /** 标定向导 UI 状态 (null = 未打开) */
    val calibUiState = mutableStateOf<CalibUiState?>(null)
    /** 采样中的标定器, 由检测线程投喂 */
    @Volatile private var activeCalibrator: CheckerboardCalibrator? = null
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
                            calibHasExisting = CalibrationData.load(preferences) != null,
                            onCalibStart = { cols, rows, sqSize -> startCalibration(cols, rows, sqSize) },
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
                // 传感器物理尺寸 (mm)，用于计算真实 fx = focalMm * imageW / sensorWmm
                val sensorKey = android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
                val sensorSize = try {
                    c2.getCameraCharacteristic(sensorKey)
                } catch (_: Exception) { null }
                val sensorWmm = sensorSize?.width ?: 0f
                val sensorHmm = sensorSize?.height ?: 0f
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
                availableCameras.add(CameraOption(id, lensFacing, focals, sensorWmm, sensorHmm, label, info))
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

    // ──────────── 棋盘格标定流程 ────────────

    /** 打开标定向导 */
    private fun openCalibration() {
        activeCalibrator = null
        pendingCalib = null
        calibUiState.value = CalibUiState(stage = CalibStage.INPUT)
    }

    /** 开始采样: 用户已填入棋盘格参数（内角列/行数、每格边长 mm） */
    private fun startCalibration(gridCols: Int, gridRows: Int, squareSizeMm: Double) {
        val calibrator = CheckerboardCalibrator(
            patternSize = org.opencv.core.Size(gridCols.toDouble(), gridRows.toDouble()),
            squareSizeMm = squareSizeMm,
            requiredSamples = 25
        )
        activeCalibrator = calibrator
        calibUiState.value = CalibUiState(
            stage = CalibStage.SAMPLING,
            gridCols = gridCols,
            gridRows = gridRows,
            squareSizeMm = squareSizeMm.toInt().toString(),
            requiredSamples = calibrator.requiredSamples
        )
        Log.i(TAG, "checkerboard calib started: ${gridCols}x${gridRows}, square=${squareSizeMm}mm")
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
        if (data == null || !data.isValid) {
            cancelCalibration()
            return
        }
        // 棋盘格标定得到完整内参 + 畸变系数，不依赖 zoom level
        CalibrationData.save(preferences, data)
        calibData = data
        useCalibration = true
        updateCalibrationMats()
        state.calibrationData = calibData
        state.warningMessage = null
        activeCalibrator = null
        pendingCalib = null
        calibUiState.value = null
        Toast.makeText(this, "棋盘格标定已保存", Toast.LENGTH_SHORT).show()
    }

    /** 清除当前标定 */
    private fun clearCalibration() {
        preferences.edit().remove("calibration_data").apply()
        calibData = CalibrationData()
        useCalibration = false
        updateCalibrationMats()
        state.calibrationData = calibData
        state.warningMessage = "相机未标定 (使用 Camera2 内参)"
        calibUiState.value = null
        Toast.makeText(this, "已清除标定", Toast.LENGTH_SHORT).show()
    }

    /** 载入已保存的标定值 */
    private fun applyStoredCalibration() {
        val stored = CalibrationData.load(preferences)
        if (stored != null && stored.isValid) {
            calibData = stored
            useCalibration = true
            state.warningMessage = null
            Log.i(TAG, "applied stored checkerboard calib: fx=${stored.fx}")
        } else {
            calibData = CalibrationData()
            useCalibration = false
            state.warningMessage = "相机未标定 (使用 Camera2 内参)"
        }
        if (::cameraMatrix.isInitialized) updateCalibrationMats()
        state.calibrationData = calibData
    }

    /** SharedPreferences（供 CalibrationData 存储使用） */
    private val preferences by lazy {
        getSharedPreferences("checkerboard_calib", android.content.Context.MODE_PRIVATE)
    }

    /**
     * 由检测线程调用: 投喂一帧给棋盘格标定器。
     * 使用灰度图进行角点检测，与靶标检测完全独立。
     */
    private fun feedCalibration(
        gray: Mat,
        width: Int,
        height: Int
    ) {
        val calibrator = activeCalibrator ?: return
        val ok = calibrator.feed(gray, width, height)

        if (calibrator.isComplete) {
            val result = calibrator.finish(width, height)
            activeCalibrator = null
            if (result == null || !result.isValid) {
                runOnUiThread {
                    calibUiState.value = CalibUiState(
                        stage = CalibStage.INPUT,
                        rejectReason = "标定失败: 样本不足或质量不够"
                    )
                }
                return
            }
            pendingCalib = result
            runOnUiThread {
                calibUiState.value = CalibUiState(
                    stage = CalibStage.DONE,
                    sampleCount = calibrator.requiredSamples,
                    requiredSamples = calibrator.requiredSamples,
                    reprojectionError = result.reprojectionError,
                    fx = result.fx,
                    fy = result.fy,
                    cx = result.cx,
                    cy = result.cy
                )
            }
        } else {
            val n = calibrator.sampleCountValue
            val reason = calibrator.lastRejectReason()
            runOnUiThread {
                val cur = calibUiState.value
                if (cur != null && cur.stage == CalibStage.SAMPLING) {
                    calibUiState.value = cur.copy(
                        sampleCount = n,
                        rejectReason = if (!ok) reason else null
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

            // ── CameraX 回调: 只做轻量预处理, 检测交给后台线程 ──
            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    val w = imageProxy.width
                    val h = imageProxy.height

                    // 1. 相机内参 (轻量: 只做数学运算, ~1ms)
                    if (useCalibration && calibData.isValid) {
                        val scaleX = w.toDouble() / calibData.imageWidth
                        val scaleY = h.toDouble() / calibData.imageHeight
                        val cm = calibData.cameraMatrix.copyOf()
                        cm[0] *= scaleX; cm[2] *= scaleX
                        cm[4] *= scaleY; cm[5] *= scaleY
                        cameraMatrix.put(0, 0, *cm)
                        // 畸变系数不需要缩放
                        distCoeffs.put(0, 0, *calibData.distCoeffs)
                    } else {
                        // 优先使用 Camera2 API 读取的物理焦距和传感器尺寸（更准确）
                        val opt = availableCameras.getOrNull(currentCameraIndex)
                        val focalMm = opt?.focalLengths?.minOrNull()?.toDouble() ?: 0.0
                        val sensorW = opt?.sensorWidthMm?.toDouble() ?: 0.0
                        val sensorH = opt?.sensorHeightMm?.toDouble() ?: 0.0
                        val zoom = currentZoomState.value.toDouble().coerceAtLeast(0.1)

                        val fx: Double
                        val fy: Double
                        if (focalMm > 0.0 && sensorW > 0.0 && sensorH > 0.0) {
                            // 基于物理参数的真实焦距（像素）
                            fx = (focalMm * w / sensorW) * zoom
                            fy = (focalMm * h / sensorH) * zoom
                        } else {
                            // 兜底: 60° FOV 假设
                            fx = w / (2.0 * kotlin.math.tan(Math.toRadians(30.0))) * zoom
                            fy = h / (2.0 * kotlin.math.tan(Math.toRadians(30.0))) * zoom
                        }
                        cameraMatrix.put(0, 0, fx, 0.0, w / 2.0, 0.0, fy, h / 2.0, 0.0, 0.0, 1.0)
                        distCoeffs.put(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
                    }

                    // 2. YUV→Gray (轻量: 内存拷贝, ~2-3ms)
                    val grayPair = imageProxyToGray(imageProxy) ?: return@setAnalyzer
                    var (gray, grayData) = grayPair
                    val grayW = w
                    val grayH = h

                    // 3. 前摄翻转 (轻量)
                    if (isFrontCamera) {
                        val flipped = Mat()
                        org.opencv.core.Core.flip(gray, flipped, 1)
                        gray.release()
                        gray = flipped
                        if (grayData.size >= grayW * grayH) gray.get(0, 0, grayData)
                    }

                    // 4. 深拷贝: 传给检测线程 (互不干扰)
                    val grayClone = Mat()
                    gray.copyTo(grayClone)
                    gray.release()
                    val cmClone = cameraMatrix.clone()
                    val dcClone = if (useCalibration && calibData.isValid) distCoeffs.clone() else null

                    // 5. 投递到检测线程 (丢弃旧帧, 始终保持最新)
                    val job = FrameJob(grayClone, grayW, grayH, System.currentTimeMillis(), cmClone, dcClone)
                    frameQueue.poll() // 丢弃未处理的旧帧
                    frameQueue.offer(job)

                    // FPS
                    frameCount++
                    val now = System.nanoTime()
                    val elapsed = (now - lastFpsTime) / 1_000_000_000.0
                    if (elapsed >= 1.0) {
                        state.fps = frameCount / elapsed
                        frameCount = 0
                        lastFpsTime = now
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Frame extraction failed: ${e.message}")
                } finally {
                    imageProxy.close()
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
     * 后台检测线程: 从队列取帧 → detect → measure → 抛结果到主线程
     *
     * 流水线架构:
     *   CameraX 回调 (analysisExecutor)  →  frameQueue  →  detectionLoop (本线程)
     *   轻量预处理 (~5ms)                    容量1             重检测 (~20-50ms)
     *
     * 始终处理最新帧，旧帧自动丢弃，消除帧积压延迟。
     */
    private fun detectionLoop() {
        var dataBuf = ByteArray(0) // 可复用的 grayData 缓冲
        while (!Thread.currentThread().isInterrupted) {
            try {
                val job = frameQueue.take() // 阻塞等待新帧

                try {
                    // 提取 grayData (检测器签名需要 ByteArray 做象限灰度统计)
                    val buf = if (dataBuf.size >= job.grayW * job.grayH) dataBuf
                              else ByteArray(job.grayW * job.grayH).also { dataBuf = it }
                    job.gray.get(0, 0, buf)

                    // ── 执行检测 (detector/engine 均由本线程独占, 无竞态) ──
                    val detResults = detector.detect(
                        job.gray, buf, job.grayW, job.grayH,
                        job.cameraMatrix, job.distCoeffs
                    )

                    // 棋盘格标定采样 (使用灰度图, 独立于靶标检测)
                    if (activeCalibrator != null) {
                        feedCalibration(job.gray.clone(), job.grayW, job.grayH)
                    }

                    // 测量
                    val dispResults = engine.measureAll(detResults, job.timestamp)

                    // 更新状态 (主线程)
                    runOnUiThread {
                        state.detectResults = detResults
                        state.dispResults = dispResults
                        state.imageSize = job.grayW to job.grayH
                        state.frameNum++
                        state.stats = engine.getStats()
                    }
                } finally {
                    // 释放深拷贝的 Mat 资源 (无论检测成功与否)
                    job.gray.release()
                    job.cameraMatrix?.release()
                    job.distCoeffs?.release()
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Detection failed: ${e.message}", e)
            }
        }
        Log.i(TAG, "Detection thread stopped")
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
        detectionThread.interrupt()
        analysisExecutor.shutdownNow()
        frameQueue.clear()
        if (::cameraMatrix.isInitialized) cameraMatrix.release()
        if (::distCoeffs.isInitialized) distCoeffs.release()
        super.onDestroy()
    }
}
