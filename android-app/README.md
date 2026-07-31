# 靶标追踪 Android App

基于桌面版 Python 项目完整移植的 Android 靶标追踪 (四象限) 应用。

## 技术栈

- **Kotlin** + **Jetpack Compose** (Material3)
- **OpenCV Android SDK 4.9** (图像处理 + PnP 位姿估计)
- **CameraX** (相机预览 + ImageAnalysis)
- **卡尔曼滤波** (自实现, 三轴独立)

## 功能

- 四象限双黑靶标实时检测
- 6DOF 位姿估计 (solvePnP + pinhole 回退)
- 三轴卡尔曼滤波 + 滑动平均 + 自适应死区
- 异常值检测 + 连续异常自动恢复
- XY 轨迹可视化
- 多靶标 ID 分配 (按像素面积排序)
- 可设置零位/归零

## 构建步骤

### 1. 下载 OpenCV Android SDK

```bash
# 下载 OpenCV 4.9.0 Android SDK
wget https://github.com/opencv/opencv/releases/download/4.9.0/opencv-4.9.0-android-sdk.zip
unzip opencv-4.9.0-android-sdk.zip
```

### 2. 导入 OpenCV 模块

将解压后的 `OpenCV-android-sdk/sdk/` 目录内容复制到本项目的 `opencv/` 目录:

**Windows:**
```powershell
xcopy /E OpenCV-android-sdk\sdk\* android-app\opencv\
```

**macOS/Linux:**
```bash
cp -r OpenCV-android-sdk/sdk/* android-app/opencv/
```

然后修改 `opencv/build.gradle`，或者直接在 Android Studio 中通过 `File > New > Import Module` 导入。

**简化方案**: 在 `build.gradle.kts` 中直接引用 OpenCV 的 `.aar` 或 `.so` 文件。

### 3. 用 Android Studio 打开

```bash
# Android Studio → File → Open → 选择 android-app/ 目录
```

### 4. 同步 Gradle + 运行

- Android Studio 会自动提示 Sync Gradle
- 选择目标设备 (需 Android 8.0+)
- 点击 Run

### 最低要求

| 项目 | 版本 |
|------|------|
| Android SDK | 26+ (Android 8.0) |
| Gradle | 8.5 |
| Kotlin | 1.9.22 |
| Compose BOM | 2024.01.00 |
| OpenCV | 4.9.0 |

## 项目结构

```
android-app/
├── build.gradle.kts              # 根构建文件
├── settings.gradle.kts            # 项目设置 (含 :opencv 模块)
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties
├── opencv/                        # OpenCV 模块 (需手动导入)
│   ├── build.gradle
│   └── AndroidManifest.xml
└── app/
    ├── build.gradle.kts           # App 构建文件
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/targettracker/
        │   ├── MainActivity.kt           # 入口
        │   ├── TargetTrackerState.kt     # 全局状态
        │   ├── config/
        │   │   ├── Config.kt             # 全局配置
        │   │   └── CalibrationData.kt    # 标定数据
        │   ├── camera/
        │   │   └── CameraAnalyzer.kt     # CameraX+OpenCV 桥接
        │   ├── detector/
        │   │   ├── TargetDetector.kt     # 四象限检测器
        │   │   └── DetectionResult.kt    # 检测结果
        │   ├── engine/
        │   │   ├── DisplacementEngine.kt # 位移测量引擎
        │   │   ├── SimpleKalmanFilter.kt # 卡尔曼滤波器
        │   │   └── DisplacementResult.kt # 位移结果
        │   └── ui/
        │       ├── MainScreen.kt         # 主画面 (Compose)
        │       ├── HUDOverlay.kt         # HUD + 轨迹图
        │       ├── ControlBar.kt         # 底部控制栏
        │       └── theme/
        │           ├── Color.kt          # 配色方案
        │           ├── Type.kt           # 字体排印
        │           └── Theme.kt          # Material3 主题
        └── res/
            └── values/
                ├── strings.xml
                ├── colors.xml
                └── themes.xml
```

## 与 Python 版的对应关系

| Python | Android (Kotlin) |
|--------|------------------|
| `src/config.py` | `config/Config.kt` |
| `src/detector.py` | `detector/TargetDetector.kt` |
| `src/measure.py` | `engine/DisplacementEngine.kt` |
| `src/calibration.py` | 待实现 (标定接口预留) |
| `src/gui.py` | `ui/MainScreen.kt` |
| `src/visualizer.py` | `ui/HUDOverlay.kt` |
| `main.py` | `MainActivity.kt` |
