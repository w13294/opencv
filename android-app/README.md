# 靶标位移追踪 App (Android)

基于 OpenCV 四象限靶标检测 + 卡尔曼滤波的 Android 实时位移测量应用。
算法由桌面端 Python 版本完整翻译为 Kotlin + Jetpack Compose。

## 技术栈

- **Kotlin + Jetpack Compose** (Material 3 暗色界面)
- **CameraX** 相机采集 (后摄, 1280×720)
- **OpenCV 4.9.0** (Maven Central 官方 AAR, 无需手动下载 SDK)
- **卡尔曼滤波** 三轴位移平滑 + 自适应死区 + 异常恢复

## 功能

- 四象限同心椭圆靶标检测 (递归层级遍历 + 对角角度验证)
- `solvePnP` 6DOF 位姿求解 (失败时 pinhole 回退)
- 按像素面积自动分配靶标 ID
- XYZ 三轴位移 + 二维位移实时显示
- XY 轨迹实时绘制
- 归零 / 重置
- 未检测到靶标时中文提示

## 项目结构

```
android-app/
├── build.gradle.kts            # 根构建 (AGP 8.2.2 + Kotlin 1.9.22)
├── settings.gradle.kts         # 模块声明
├── .github/workflows/build.yml # 云端自动构建 APK
└── app/
    └── src/main/java/.../targettracker/
        ├── MainActivity.kt          # 入口: OpenCV 初始化 + 权限 + 相机绑定
        ├── config/                  # Config.kt + CalibrationData.kt
        ├── camera/CameraAnalyzer.kt # YUV → OpenCV Mat 转换
        ├── detector/                # 四象限检测 (Kotlin)
        ├── engine/                  # 卡尔曼滤波 + 滑动平均
        └── ui/                      # Compose UI
```

## 云端构建 (GitHub Actions) — 推荐

本项目已配置 GitHub Actions，推送即可自动出包，**无需本机安装 Android 环境**。

### 调试版 (Debug APK)

1. 将 `android-app/` 推送到 GitHub 仓库的 `main` 分支
2. Actions 自动运行 `Build APK` 工作流
3. 在 Actions 页面下载 `app-debug` 产物 (APK)

### 发布版 (Release APK, 签名)

1. 在仓库 **Settings → Secrets → Actions** 添加:
   - `KEYSTORE_BASE64` : 签名 keystore 的 base64 (`base64 -w0 your.keystore.jks`)
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
2. 打 tag 并推送: `git tag v1.0.0 && git push origin v1.0.0`
3. 自动创建 GitHub Release 并附上签名 APK

### 本机构建 (可选)

需要 JDK 17 + Android SDK (platform-34, build-tools 34.0.0):

```bash
cd android-app
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 安装

手机开启「未知来源」安装，传输 APK 后安装即可。需要 Android 8.0+ (minSdk 26)。
首次运行授予相机权限。
