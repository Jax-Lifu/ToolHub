# ToolHub

ToolHub 是一款面向嵌入式 Android 设备调试、实时遥测监控、硬件诊断与压力测试的 Jetpack Compose 系统级工具应用（包名：`com.qytech.toolhub`）。支持简体中文、繁體中文（TW/HK）、English、日本語与 한국어 6 套语言。

## 核心功能

- **总览控制台 (`HomeScreen`)**
  - 开发者快捷控制：USB 调试（ADB）、无线 ADB、充电保持常亮、系统重启与多语言切换。
  - 系统自检清单（UID/权限/OTG 节点/开机策略状态）与实时性能摘要条。
- **OTG 节点与开机策略 (`OtgModeScreen` / `BootCompletedReceiver`)**
  - 自动扫描 sysfs 中的 `otg_mode` 节点，支持 `otg` / `host` / `peripheral` 模式实时切换。
  - 枚举已连接的 USB 外设（显示 VID/PID、设备类别与接口数）。
  - 支持通过 `LOCKED_BOOT_COMPLETED`、`BOOT_COMPLETED`、`QUICKBOOT_POWERON` 开机广播自动应用默认 OTG 与 ADB 策略。
- **性能监控 (`PerfScreen`)**
  - 实时采集并绘制 CPU、GPU、内存、磁盘 I/O 与 ART GC 遥测曲线，支持 `1s / 2s / 5s` 采样率与后台保活/生命周期联动降耗。
- **温控与调频 (`ThermalScreen`)**
  - 监控多温区温度变化曲线，查看并按集群调整 CPU `cpufreq` 调度器与频点锁定、GPU `devfreq` 调度器与锁频。
- **音频与板级节点 (`AudioScreen`)**
  - 查看 ALSA 声卡与 PCM 实时状态、AudioManager 输出设备属性，支持板级 Sysfs 调试节点在线读写与自定义节点添加/移除。
- **诊断与产线工具 (`ToolsScreen`)**
  - 集中管理全部独立工具与产线检测入口：
    - **硬件压测与老化报告**：集成原生 `libstressapptest.so`（支持 `arm64-v8a` / `armeabi-v7a`）与 Foreground Service + WakeLock 后台保活。
    - **ART AOT 预编译**：查看已安装应用 dexopt 状态，支持单应用与一键批量 `speed` 编译。
    - **系统属性浏览器**：支持 `getprop` 过滤检索与 `setprop` 在线修改。
    - **屏幕与触控检测**：坏点纯色切换、灰阶检测与多指 TP 画线测试。
    - **网络连通性诊断**、**崩溃回溯与 Bugreport 收集**、**数字测试信号发生器 (Audio Tone)**、**Web 控制台设置**与 **无线 ADB 二维码**。
- **设备与网络信息 (`DeviceInfoScreen`)**
  - 展示设备型号、SoC 平台、Android 构建信息、Wi-Fi/以太网状态，支持一键扫码连接 ADB 与复制诊断快照。
- **内置 Web 控制台 (`WebReportServer` / `web_console.html`)**
  - 应用启动时自动开启本地 HTTP 服务（默认端口 `8080`），支持同局域网浏览器访问，提供多语言切换、可选 PIN 鉴权、合并后的「总览与设备」视图、CPU/GPU 调频、信号发生器、系统属性与 AOT 管理以及报告/Tombstone 下载。

## 技术栈与环境要求

- **构建工具**: Gradle `9.9.1`（已启用 Configuration Cache） / Android Gradle Plugin `9.5.1`
- **语言与编译**: Kotlin `2.2.10` / Java `11`
- **SDK 版本**: `minSdk = 31` / `targetSdk = 37` / `compileSdk = 37`
- **UI 框架**: Jetpack Compose (BOM `2024.09.00`)、Material 3、Navigation Compose `2.8.5`
- **常用库**: `kotlinx-serialization-json` `1.8.1`、`zxing-core` `3.5.3`
- **性能优化**: 启用 R8 Full Mode（`-allowaccessmodification`、`-repackageclasses`）、资源裁剪与专属 `baseline-prof.txt`

## 签名配置（本地与 CI）

仓库不包含私有系统签名密钥与口令（`*.jks` 与 `keystore.properties` 已加入 `.gitignore`）。

- **本地系统签名**：在项目根目录创建 `keystore.properties`（不会提交到 Git）：
  ```properties
  storeFile=SystemSignature.jks
  storePassword=your_store_password
  keyAlias=your_key_alias
  keyPassword=your_key_password
  ```
  未配置时自动回退使用本地默认 Debug 密钥签名，可直接编译运行。
- **GitHub Actions CI 签名**：在仓库 Settings → Secrets and variables → Actions 中按需配置 `TOOLHUB_KEYSTORE_BASE64`、`TOOLHUB_KEYSTORE_PASSWORD`、`TOOLHUB_KEY_ALIAS`、`TOOLHUB_KEY_PASSWORD`；未配置时同样可自动完成单元测试与 Release APK 编译。

## 构建与测试

```bash
# 运行 JVM 单元测试
./gradlew testDebugUnitTest

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK（启用 R8 混淆、资源压缩与 Baseline Profile）
./gradlew assembleRelease

# 运行仪器测试（需连接 Android 设备或模拟器）
./gradlew connectedDebugAndroidTest
```
