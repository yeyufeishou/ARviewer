# AR 超宽屏视频观看器 — 构建说明

## 环境要求

| 工具 | 版本 |
|------|------|
| Android Studio | Hedgehog 2023.1.1 及以上 |
| Android SDK | API 26–34 |
| JDK | 17（Android Studio 内置） |
| Gradle | 8.4（包装器自动下载） |

## 一键构建（Android Studio）

1. 打开 Android Studio → **Open** → 选择本目录（`ARVideoViewer/`）
2. 等待 Gradle 同步完成（首次约 2–5 分钟，需联网下载依赖）
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. 生成的 APK 位于：`app/build/outputs/apk/debug/app-debug.apk`

## 命令行构建（需配置 ANDROID_HOME）

```bash
cd ARVideoViewer
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Windows PowerShell：
```powershell
cd ARVideoViewer
.\gradlew.bat assembleDebug
```

## 安装到设备

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 设备要求

- Android 8.0（API 26）及以上
- 后置摄像头
- 陀螺仪传感器（必须）
- OpenGL ES 2.0 支持

---

## 功能说明

| 功能 | 说明 |
|------|------|
| 悬浮屏幕 | 视频以 3D 透视渲染在摄像头画面之上 |
| 全景平移 | 左右旋转手机平移超宽视频，上下倾斜垂直平移 |
| 方向重置 | 点击右上角指南针按钮，将当前方向设为中心 |
| 视频选择 | 支持本地任意视频（推荐 4:1 以上超宽分辨率） |
| 帧边框 | 视频周围有蓝白色发光边框，增强 Vision Pro 感 |

## 超宽视频制作建议

- 分辨率推荐：`7680×1080`（8:1）或 `5760×1080`（16:3）
- 普通视频可用 FFmpeg 横向拼接或 Pillow 处理
- 或直接使用 16:9 视频（效果较弱，但可用）

## 项目结构

```
app/src/main/java/com/arvideo/viewer/
├── MainActivity.kt          — 主界面：视频选择
├── ARVideoActivity.kt       — AR 观看界面
├── gl/
│   ├── ARVideoRenderer.kt   — OpenGL ES 渲染器（核心）
│   └── GlUtils.kt           — shader 编译工具
├── sensor/
│   └── SensorHelper.kt      — 旋转向量传感器 + 低通滤波
└── camera/
    └── CameraHelper.kt      — Camera2 后置摄像头预览
```
