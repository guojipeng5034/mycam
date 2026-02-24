# 我的摄像头

将 Android 手机摄像头作为 PC 虚拟摄像头使用的应用。通过 RTSP H.264 硬件编码，实现低延迟、低发热的视频推流。

## 功能特性

- 📱 将手机摄像头转换为 PC 虚拟摄像头
- 🌐 RTSP H.264 流传输，硬件零拷贝编码
- 🔄 支持前后摄像头切换
- ⚡ 零拷贝 Surface → MediaCodec，低发热高帧率
- 🎨 Material Design 3 界面
- 📐 支持 VGA / 720p / 1080p 分辨率，1–12 Mbps 码率可调
- 🔍 1x–30x 数字变焦

## 技术架构

### 技术栈

| 模块     | 技术选型 |
|----------|----------|
| 语言     | Kotlin，JVM 11 |
| UI       | Jetpack Compose + Material Design 3 |
| 相机     | CameraX（非推流预览）/ RootEncoder RtspServerCamera2（推流，Camera2 API） |
| 推流     | [RootEncoder](https://github.com/pedroSG94/RootEncoder) + [RTSP-Server](https://github.com/pedroSG94/RTSP-Server)，H.264 硬件编码 |
| 预览     | OpenGlView（RootEncoder 渲染） |
| 流控     | StreamControl 单例 + StreamViewModel |
| 构建     | Gradle Kotlin DSL，minSdk 31，targetSdk 36，arm64-v8a |

### 整体流程

1. **界面层**：MainActivity 使用 Compose 单屏布局，顶部标题、中部 16:9 预览、RTSP URL 卡片、设置卡片（码率、分辨率、前后摄）、Start/Stop 按钮。
2. **预览**：非推流时用 CameraX PreviewView；推流时用 RootEncoder 的 OpenGlView，通过 StreamPreviewHolder 供 StreamingService 绑定。
3. **推流服务**：StreamingService 在 onCreate 中创建 RtspServerCamera2，绑定 OpenGlView（或无预览模式），启动 RTSP 服务（端口 8554，路径 `/live`）。
4. **编码链路**：相机 Surface → MediaCodec（H.264 Baseline，无 B 帧，短 I 帧间隔）→ RTSP 输出，采集与编码分辨率一致，避免额外缩放。
5. **流控**：StreamControl 用 StateFlow 管理分辨率、镜头朝向、码率、FPS、zoom、RTSP URL，界面与服务双向联动；推流中支持前后摄切换。

### 核心组件

- **StreamControl**：单例，保存 resolution、lensFacing、fps、videoBitrateMbps、zoomHandler、rtspStreamUrl 等状态。
- **StreamViewModel**：封装 StreamControl 与 StreamingService 的启动/停止，供 Compose 使用。
- **StreamPreviewHolder**：持有 OpenGlView，供 StreamingService 的 RtspServerCamera2 绑定本地预览。
- **NetworkInfo**：获取 WiFi IP，用于 RTSP URL 展示。
- **Resolution**：VGA(640×480)、HD_720P(1280×720)、FULL_HD(1920×1080)。

### 权限与清单

CAMERA、RECORD_AUDIO、INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE、CHANGE_WIFI_STATE、WAKE_LOCK、POST_NOTIFICATIONS、FOREGROUND_SERVICE、FOREGROUND_SERVICE_CAMERA；StreamingService 为 camera 类型前台服务。

## 系统要求

- Android 12+ (API 31+)
- 支持摄像头
- WiFi 网络连接

## 安装

### 直接安装 APK
1. 下载 `app-release.apk`
2. 启用「未知来源」安装
3. 安装并打开

### ADB 安装
```bash
adb install app-release.apk
```

## 使用说明

1. 启动应用，允许摄像头、麦克风、通知权限
2. 点击「开始推流」
3. 复制显示的 RTSP 地址：`rtsp://手机IP:8554/live`
4. 在 PC 端 OBS、VLC 等软件中输入该地址

## PC 端配置

### OBS Studio
1. 添加「媒体源」
2. URL：`rtsp://手机IP:8554/live`
3. 取消勾选「本地文件」
4. 确定

### VLC
1. 媒体 → 打开网络串流
2. 输入：`rtsp://手机IP:8554/live`
3. 播放

## 故障排除

| 问题       | 处理建议 |
|------------|----------|
| 无法连接   | 确认手机与 PC 同一 WiFi；检查防火墙；核对手机 IP |
| 视频卡顿   | 降低码率或分辨率；确保网络稳定 |
| 权限异常   | 重新授权摄像头、麦克风、通知权限 |

## 技术规格

| 项目       | 说明 |
|------------|------|
| 视频格式   | H.264 (AVC Baseline) |
| 协议       | RTSP |
| 端口       | 8554 |
| 路径       | /live |
| 分辨率     | VGA / 720p / 1080p |
| 码率       | 1–12 Mbps 可调 |
| 帧率       | 15–60 fps（设备支持） |
| 编码       | 硬件 MediaCodec，零拷贝 |
| 变焦       | 1x–30x 数字变焦 |

## 隐私说明

- 不收集个人数据
- 视频流仅在本地网络传输
- 不上传至任何服务器

## 开发者信息

- **应用名称**：我的摄像头
- **包名**：com.example.mycam
- **最低版本**：Android 12 (API 31)

## 许可证

本项目仅供学习和个人使用。如有问题或建议，请联系开发者。
