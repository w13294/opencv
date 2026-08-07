# 安卓端架构说明文档

> 靶标位移追踪 App —— 代码结构、数据流与二次开发指南
>
> 面向对象：需要修改/扩展本项目的开发者
> 对应版本：versionName 1.0.1 (versionCode 2)

---

## 目录

1. [技术栈与工程配置](#一技术栈与工程配置)
2. [整体架构框图](#二整体架构框图)
3. [三线程流水线详解](#三三线程流水线详解)
4. [核心算法链路](#四核心算法链路)
5. [模块与文件清单](#五模块与文件清单)
6. [功能修改速查表](#六功能修改速查表)
7. [关键设计约定（改代码必读）](#七关键设计约定改代码必读)
8. [常见扩展任务示例](#八常见扩展任务示例)

---

## 一、技术栈与工程配置

| 项目 | 说明 |
|---|---|
| 语言 | Kotlin (JVM target 17) |
| UI 框架 | Jetpack Compose + Material 3（暗色主题） |
| 相机 | CameraX 1.4.2（兼容 Android 15/16） |
| 视觉库 | OpenCV 4.11.0（Maven Central 官方 AAR，支持 16KB 页对齐） |
| 持久化 | DataStore Preferences + kotlinx-serialization-json |
| 异步 | Kotlin Coroutines + 原生 Thread/Executor |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |
| ABI | arm64-v8a、armeabi-v7a |

**两条关键构建配置（不要随意改动）**

```kotlin
// app/build.gradle.kts
ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
packaging {
    // 不压缩 native 库，确保 OpenCV 的 16KB 对齐 .so 可被系统直接 mmap 加载
    jniLibs { useLegacyPackaging = true }
}
```

Android 15/16 强制要求 native 库支持 16KB 内存页，上述配置是 App 能在新系统上启动的前提。

---

## 二、整体架构框图

```
┌────────────────────────────────────────────────────────────────┐
│                      AppApplication.kt                          │
│         进程启动入口 → System.loadLibrary 加载 OpenCV native     │
└───────────────────────────┬────────────────────────────────────┘
                            ↓
┌────────────────────────────────────────────────────────────────┐
│                MainActivity.kt  (总调度中枢，约 1058 行)         │
│  权限申请 · 相机枚举与绑定 · 变焦 · 分辨率 · 标定编排 · 生命周期  │
└───────────────────────────┬────────────────────────────────────┘
                            ↓
╔═════════════════ 多线程流水线（性能核心）═══════════════════════╗
║                                                                ║
║  【线程 1】CameraX analysisExecutor —— 轻量预处理 ~5ms          ║
║    ImageProxy                                                  ║
║      → imageProxyToGray()  提取 YUV 的 Y 通道得灰度 Mat          ║
║      → 前摄水平翻转（isFrontCamera）                             ║
║      → 组装当前内参 cameraMatrix / distCoeffs                    ║
║      → Mat 深拷贝，封装成 FrameJob 投递                          ║
║                            ↓                                   ║
║        frameQueue : LinkedBlockingQueue，容量 = 1               ║
║        ★ 投递前 poll() 丢弃旧帧，再 offer() 新帧                 ║
║          ⇒ 永远只处理最新帧，零积压、零累积延迟                   ║
║                            ↓                                   ║
║  【线程 2】detectionLoop  线程名 "TargetDetection" —— ~20-50ms  ║
║      TargetDetector.detect()      四象限靶标检测                 ║
║      DisplacementEngine.measureAll()  位移解算 + 滤波            ║
║      （标定模式下额外分流 → feedCalibration()）                  ║
║                            ↓  runOnUiThread                    ║
║  【线程 3】主线程 —— 写入 TargetTrackerState → Compose 自动重组   ║
║                                                                ║
║  【线程 4】"CalibCompute"（临时线程）                            ║
║      calibrateCamera() 重运算，避免阻塞相机与 UI                  ║
╚════════════════════════════════════════════════════════════════╝
                            ↓
┌──────────────── 数据中枢：TargetTrackerState.kt ────────────────┐
│  以 Compose mutableStateOf 承载全部可观察状态：                  │
│   detectResults · dispResults · fps · imageSize                │
│   selectedTargetId · zeroed · stats · 各类告警标志               │
│  ——— 唯一的「单向数据源」，UI 只读，检测线程只写 ———              │
└───────────────────────────┬────────────────────────────────────┘
                            ↓
┌──────────────────────── UI 渲染层 ──────────────────────────────┐
│  MainScreen.kt —— Box 层叠布局，自底向上 6 层：                  │
│    ① PreviewView          相机预览（全屏底层）                   │
│    ② MarkerOverlayView    靶标标注（Canvas 绘制椭圆/十字/ID）    │
│    ③ 顶部状态条 + 告警提示                                       │
│    ④ HUDOverlay（左上数值面板）+ TrajectoryChart（右上轨迹图）   │
│    ⑤ ControlBar（底部控制栏）                                    │
│    ⑥ 各类 Dialog（标定向导、设置等）                             │
└────────────────────────────────────────────────────────────────┘
```

---

## 三、三线程流水线详解

### 为什么要拆线程

相机回调频率固定（如 30fps ≈ 33ms/帧），而重检测耗时 20-50ms。若在回调线程内直接做检测，会出现帧积压 → 画面延迟越来越大。

### 解法：生产者-消费者 + 容量 1 丢帧队列

| 角色 | 线程 | 职责 | 典型耗时 |
|---|---|---|---|
| 生产者 | `analysisExecutor` | YUV→灰度、翻转、深拷贝 | ~5ms |
| 缓冲 | `frameQueue`（容量 1） | 只保留最新一帧 | — |
| 消费者 | `TargetDetection` | 检测 + 测量 | ~20-50ms |
| 展示 | 主线程 | 状态写入 + Compose 重组 | ~1ms |

投递逻辑（关键）：

```kotlin
frameQueue.poll()      // 丢弃尚未处理的旧帧
frameQueue.offer(job)  // 放入最新帧
```

**效果**：检测慢于采集时，中间帧被自然丢弃，显示的永远是最新画面，延迟恒定不累积。

> 若需求改为"不丢帧、全部处理"（如离线逐帧分析），把队列容量调大并去掉 `poll()` 即可，但要接受延迟累积。

---

## 四、核心算法链路

```
灰度 Mat (CV_8UC1)
   │
   ├─► TargetDetector.detect(gray, buf, w, h, cameraMatrix, distCoeffs)
   │     ① 自适应阈值二值化
   │     ② 轮廓提取 + 层级递归遍历（找同心结构）
   │     ③ 椭圆拟合，按面积/圆度/长宽比筛选
   │     ④ 四象限灰度统计 → 提取质心 → 解析靶标 ID
   │     ⑤ solvePnP 求 6DOF 位姿（失败时 pinhole 回退）
   │           ↑ 依赖 cameraMatrix + distCoeffs（标定产物）
   │
   └─► DetectionResult { ellipse, corners, tvec, rvec, sizeMm, id, ... }
         │
         ├─► DisplacementEngine.measureAll(detResults, timestamp)
         │     ① 与归零基准比较（setZero 记录的参考位姿）
         │     ② 换算物理位移 dx / dy / dz
         │     ③ SimpleKalmanFilter 三轴平滑
         │     ④ 自适应死区抑制抖动 + 异常值恢复
         │     ⑤ 统计量累计（getStats）
         │
         └─► DisplacementResult { dx, dy, dz, dist2d, ... }
               │
               ├─► HUDOverlay        数值显示
               ├─► TrajectoryChart   XY 轨迹曲线（保留最近 300 点）
               └─► MarkerOverlayView 画面标注
```

### 标定链路（独立支线）

```
灰度 Mat ──► CheckerboardCalibrator.feed()
                ① findChessboardCorners 找角点
                ② 质量门槛：棋盘占画面 ≥ 20%
                ③ 去重：与已有样本距离 ≥ 10px
                ④ 累计到 25 组样本
                     ↓
              calibrateCamera()  ← 在 "CalibCompute" 线程执行
                     ↓
              CalibrationData { cameraMatrix[9], distCoeffs[5], rms }
                     ↓
              DataStore 持久化，键：calibration_data_{cameraId}_{zoom}x
                     ↓
              updateCalibrationMats() 刷新内参 → 供 solvePnP 使用
```

**为什么按「摄像头 ID + 变焦档」双键存储**：逻辑多摄手机切换变焦实际会切到不同物理镜头（主摄/广角/长焦），内参完全不同，必须分别标定分别存储。

---

## 五、模块与文件清单

```
android-app/
├── build.gradle.kts                 # 根构建 (AGP 8.2.2 + Kotlin 1.9.22)
├── settings.gradle.kts              # 模块声明
├── .github/workflows/build.yml      # 云端自动构建 APK
└── app/
    ├── build.gradle.kts             # 依赖、SDK 版本、ABI、打包配置
    └── src/main/
        ├── AndroidManifest.xml      # 权限 / 应用名 / 图标 / 屏幕方向
        └── java/com/example/targettracker/
            ├── AppApplication.kt        # 加载 OpenCV native 库
            ├── MainActivity.kt          # 总调度中枢（约 1058 行）
            ├── TargetTrackerState.kt    # 全局可观察状态（数据中枢）
            │
            ├── camera/
            │   └── CameraAnalyzer.kt        # YUV → OpenCV Mat 转换封装
            │
            ├── config/
            │   ├── Config.kt                # ★ 全部算法可调参数集中处
            │   ├── CalibrationData.kt       # 标定数据结构 + 存取
            │   └── CheckerboardCalibrator.kt# 棋盘格采样与标定计算
            │
            ├── detector/
            │   ├── TargetDetector.kt        # ★ 四象限靶标检测算法
            │   └── DetectionResult.kt       # 检测结果数据类
            │
            ├── engine/
            │   ├── DisplacementEngine.kt    # ★ 位移解算 + 归零 + 统计
            │   ├── SimpleKalmanFilter.kt    # 三轴卡尔曼滤波
            │   └── DisplacementResult.kt    # 测量结果数据类
            │
            └── ui/
                ├── MainScreen.kt            # 顶层布局（6 层 Box 层叠）
                ├── ControlBar.kt            # 底部控制栏
                ├── HUDOverlay.kt            # 左上数值面板 + 右上轨迹图
                ├── MarkerOverlayView.kt     # 画面靶标标注（Canvas）
                ├── CalibrationDialog.kt     # 标定向导 UI
                └── theme/
                    ├── Color.kt             # 配色
                    └── Type.kt              # 字体
```

### MainActivity.kt 内部分区（按行号）

| 行号范围 | 职责 |
|---|---|
| ~55-63 | `resolutionOptions` / `resolutionLabels` 分辨率档位定义 |
| ~399-410 | `enumerateCameras()` 摄像头枚举与默认选择 |
| ~471-760 | 标定业务流程编排（开始/采样/计算/接受/取消/清除） |
| ~860-911 | `startCameraWithPreview()` 相机绑定、变焦档位、用例配置 |
| ~922-970 | `detectionLoop()` 后台检测主循环 |
| ~982-1020 | `imageProxyToGray()` YUV 转灰度 |
| ~1028-1048 | `updateCalibrationMats()` 内参刷新 |
| ~1050-1057 | `onDestroy()` 资源释放 |

---

## 六、功能修改速查表

### 相机相关

| 想做的修改 | 文件 | 具体位置 |
|---|---|---|
| 增删**分辨率档位** | `MainActivity.kt` | `resolutionOptions` + `resolutionLabels`（~55-63 行，**两个列表必须一一对应**） |
| 改**变焦档位**（如加 0.5x / 20x） | `MainActivity.kt` | `startCameraWithPreview()` 内 `presets = listOf(0.6f, 1.0f, ...)`（~892 行） |
| 改**摄像头显示名** | `MainActivity.kt` | `enumerateCameras()` 内 `label = "摄像头$id..."`（~399 行） |
| 改**默认选中摄像头** | `MainActivity.kt` | `enumerateCameras()` 末尾 `backIdx` 逻辑（~404 行） |
| 改 YUV 转换 / 加图像预处理 | `MainActivity.kt` | `imageProxyToGray()`（~982 行） |

### 检测算法（最常改）

| 想做的修改 | 文件 |
|---|---|
| **检测参数调优**（阈值、面积范围、圆度、椭圆长宽比） | `config/Config.kt` → `Config.target` ← **首选，不动算法** |
| **检测算法本身**（二值化方式、轮廓筛选、象限质心、ID 编码规则） | `detector/TargetDetector.kt` |
| 检测结果**新增字段** | `detector/DetectionResult.kt` |

### 测量算法

| 想做的修改 | 文件 |
|---|---|
| **测量参数**（滤波强度、单位、有效范围） | `config/Config.kt` → `Config.measure` |
| **位移计算 / 归零逻辑 / 统计量** | `engine/DisplacementEngine.kt` |
| **卡尔曼参数或更换滤波器** | `engine/SimpleKalmanFilter.kt` |
| 测量结果**新增字段** | `engine/DisplacementResult.kt` |

### 标定

| 想做的修改 | 文件 |
|---|---|
| **采样质量门槛**（占比 20% / 去重 10px / 样本数 25） | `config/CheckerboardCalibrator.kt` 的 `feed()` |
| **数据结构 / 存储键名** | `config/CalibrationData.kt` |
| **向导 UI / 步骤流程** | `ui/CalibrationDialog.kt` |
| **业务流程编排** | `MainActivity.kt` ~471-760 行 |

### 界面

| 想做的修改 | 文件 |
|---|---|
| **底部控制栏**（按钮、摄像头/变焦/分辨率选择器） | `ui/ControlBar.kt` |
| **左上数值面板**（显示项、格式化） | `ui/HUDOverlay.kt` |
| **右上轨迹图**（曲线样式、历史点数 300） | `ui/HUDOverlay.kt` → `TrajectoryChart` |
| **画面靶标标注**（椭圆框、十字、ID 标签、配色） | `ui/MarkerOverlayView.kt` |
| **布局层叠顺序 / 新增面板** | `ui/MainScreen.kt` |
| **配色主题** | `ui/theme/Color.kt` |
| **字体字号** | `ui/theme/Type.kt` |

### 工程配置

| 想做的修改 | 文件 |
|---|---|
| **权限**、应用名、图标、屏幕方向 | `app/src/main/AndroidManifest.xml` |
| **依赖库、版本号、SDK、ABI、签名** | `app/build.gradle.kts` |

> **最常改的三个文件**：`config/Config.kt`（调参）、`detector/TargetDetector.kt`（算法）、`ui/HUDOverlay.kt`（显示）。

---

## 七、关键设计约定（改代码必读）

### 1. 容量 1 队列的丢帧策略

`frameQueue` 容量固定为 1，投递前先 `poll()` 再 `offer()`。这保证永远处理最新帧、零积压。修改队列容量前请确认你能接受延迟累积。

### 2. Mat 内存必须手动释放 ⚠️

OpenCV 的 `Mat` 持有 native 堆内存，**不受 JVM GC 管理**。检测线程在 `finally` 块中释放：

```kotlin
} finally {
    job.gray.release()
    job.cameraMatrix?.release()
    job.distCoeffs?.release()
}
```

**你新增的任何 `Mat` 都必须配对 `release()`**，否则 native 内存泄漏，运行数分钟后 OOM 崩溃。尤其注意 `clone()`、`submat()`、中间运算产生的临时 Mat。

### 3. `updateCalibrationMats()` 只分配一次、之后原地覆写

```kotlin
if (!::cameraMatrix.isInitialized) {
    cameraMatrix = Mat(3, 3, CvType.CV_64F)   // 仅首次分配
}
cameraMatrix.put(0, 0, *calibData.cameraMatrix)  // 之后原地覆写
```

**原因**：该方法会被反复调用（切摄像头 / 切变焦 / 保存标定），而检测线程同时在读这两个 Mat。若 `release()` 掉正被 native 使用的 Mat，会直接 native 层崩溃。修改标定相关代码时务必保持这个约定。

### 4. 标定按「摄像头 ID + 变焦档」双键存储

键格式：`calibration_data_{cameraId}_{zoom}x`。切换任一维度都需重新标定或读取对应内参。

### 5. UI 状态更新必须切回主线程

检测线程不能直接写 `state`，必须包在 `runOnUiThread { ... }` 中，否则 Compose 重组行为未定义。

### 6. 前摄需先水平翻转再检测

`isFrontCamera` 为真时对灰度图做水平翻转。**跳过这步会导致四象限顺序镜像，靶标 ID 识别错误。**

### 7. 屏幕常亮

`MainActivity.onCreate()` 中通过
`window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` 保持屏幕不熄灭。
采用系统 Flag 而非手动 `PowerManager.WakeLock`：App 在前台时生效，退到后台自动释放，不会漏电、无需手动管理生命周期。Manifest 中已声明 `WAKE_LOCK` 权限。

---

## 八、常见扩展任务示例

### 示例 1：调整检测灵敏度（不改算法）

打开 `config/Config.kt`，修改 `Config.target` 中的阈值、最小/最大面积、圆度下限等参数即可。这是最安全的调优入口。

### 示例 2：在 HUD 中新增一项显示（如速度）

1. `engine/DisplacementResult.kt` — 新增字段 `velocity: Double`
2. `engine/DisplacementEngine.kt` — 在 `measureAll()` 中依据时间戳差分计算并填充
3. `ui/HUDOverlay.kt` — 增加一行显示

### 示例 3：新增一个底部按钮

1. `ui/ControlBar.kt` — 添加按钮及其 `onClick` 回调参数
2. `ui/MainScreen.kt` — 传入回调实现
3. `MainActivity.kt` — 若需访问相机/检测器，在此实现具体逻辑

### 示例 4：更换轨迹图历史长度

`ui/HUDOverlay.kt` 的 `TrajectoryChart` 中，历史点数上限当前为 300，按需调整。

### 示例 5：新增一种靶标类型

主要改动集中在 `detector/TargetDetector.kt` 的筛选与 ID 解析逻辑，并在 `DetectionResult.kt` 中扩展类型字段；若涉及物理尺寸，同步更新 `Config.kt`。

---

## 附：构建与安装

```bash
cd android-app

# 仅编译
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 编译 + 通过 adb 安装到已连接设备
./gradlew installDebug
```

本机构建需 JDK 17 + Android SDK（platform-34, build-tools 34.0.0）。
云端构建见 `README.md` 的 GitHub Actions 章节。
