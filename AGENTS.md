# AGENTS.md

本文件为在 `ToolHub` MonoRepo 仓库中协作的 AI 编程助手提供项目结构、构建验证命令及开发规范指引。

## 项目概览

- **应用名称 / 包名**: `ToolHub` / `com.qytech.toolhub`
- **定位**: 面向嵌入式 Android 设备的系统级硬件调试、实时遥测监控、诊断与车队远程运维平台（Android 客户端 + Web 工作台 + Rust 服务端）。

## 架构模式（MonoRepo）

- `apps/android/`: 原生 Android 调试客户端（Gradle `:app` 模块，Jetpack Compose + Material 3 + ViewModel + `object *Repository`，通过根目录 `settings.gradle.kts` 映射）。
- `apps/web/`: Vite + React 19 + Tailwind v4 + shadcn/ui 车队运维工作台。
- `apps/server/`: Rust Axum (`toolhub-fleet`) 服务端，提供 WebSocket 反向隧道、多账号 RBAC、历史遥测与审计日志持久化，由根目录 `Cargo.toml` Workspace 管理。
- **系统级特性**: `apps/android/src/main/AndroidManifest.xml` 声明了 `android:sharedUserId="android.uid.system"` 及特权权限，用于直接读写 sysfs 节点、系统属性（`SystemPropertiesHelper`）、执行原生压测二进制、获取 `Bugreport` 与控制开发者选项。

## 常用命令

```bash
# 运行前端构建 / Rust 服务端校验
npm run build:web
cargo check -p toolhub-fleet

# 运行 JVM 单元测试（修改逻辑或数据模型后优先验证）
./gradlew testDebugUnitTest

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK（启用了 isMinifyEnabled = true 与 isShrinkResources = true）
./gradlew assembleRelease

# 运行仪器测试（需连接设备）
./gradlew connectedDebugAndroidTest
```

## 代码结构与核心模块

### 1. Android 客户端 (`apps/android/src/main/java/com/qytech/toolhub`)
- **入口与导航**:
  - `MainActivity.kt`: 启动 `WebReportServer.start(applicationContext)`、`RemoteFleetRepository.init(applicationContext)` 与 `FleetKeepAliveService.startIfEnabled(applicationContext)`，通过 `MainApp()` 自适应切换 `NavigationBar` / `NavigationRail`。
  - 顶级页面路由定义在 `Screen` sealed class：`Home`、`Otg`、`Perf`、`Thermal`、`Audio`、`Tools`、`Device`。
- **页面与 ViewModel**:
  - 页面：`HomeScreen.kt`、`OtgModeScreen.kt`、`PerfScreen.kt`、`ThermalScreen.kt`、`AudioScreen.kt`、`ToolsScreen.kt`、`DeviceInfoScreen.kt`
  - 状态管理：`OtgModeViewModel.kt`、`HardwareViewModel.kt`、`MonitorViewModel.kt`、`DeviceInfoViewModel.kt`
- **数据层与系统交互 (`data/`)**:
  - `OtgRepository.kt`: sysfs `otg_mode` 节点扫描、模式读写、USB 外设枚举及开机默认策略恢复。
  - `StressTestRepository.kt` & `ReportStorageManager.kt`: 调用 `libstressapptest.so` 与 Kotlin 压测任务，管理老化测试状态与报告持久化。
  - `TelemetryRepository.kt`、`HardwareRepository.kt`、`DeviceInfoRepository.kt`、`DevControlsRepository.kt`、`AudioToneGenerator.kt`: 性能遥测、温度/CPU/GPU 调频锁频、ALSA 音频节点、信号发生器、设备信息与开发者选项控制。
  - `NetworkDiagnosticsRepository.kt`、`CrashDiagnosticsRepository.kt`、`BugreportManagerHelper.kt`、`SystemPropertiesHelper.kt`: 网络、崩溃日志与系统诊断、系统属性读写。
  - `WebReportServer.kt`、`WebApiModels.kt`、`WebConsoleHtml.kt`: 基于 `ServerSocket` 的内置 HTTP 服务与序列化模型，配合 `apps/android/src/main/assets/web_console.html` 提供本地 Web 控制台及 `/api/*` 端点。
  - `RemoteFleetRepository.kt` & `WsReverseTunnelClient.kt`: 读取构建期注入的 `BuildConfig.DEFAULT_FLEET_URL` / `DEFAULT_DEVICE_TOKEN`，维护 WebSocket 反向隧道（`/api/tunnel/ws`）、设备心跳与局域网 UDP 发现。
  - `ToolHubPreferences.kt`: 本地偏好设置持久化。
- **开机自启与后台保活 (`boot/` / `service/`)**:
  - `BootCompletedReceiver.kt`: `directBootAware="true"`，监听 `LOCKED_BOOT_COMPLETED`、`BOOT_COMPLETED` 与 `QUICKBOOT_POWERON`，应用默认 OTG 策略并在系统启动后拉起 `WebReportServer` 与 `FleetKeepAliveService`。
  - `StressKeepAliveService.kt`: 压测与 Bugreport 收集期间的 ForegroundService + `PARTIAL_WAKE_LOCK` 保活服务。
  - `FleetKeepAliveService.kt`: 远程管理与 WS 反向隧道后台保活前台服务，确保 App 退居后台或开机未打开界面时仍可远程操控。
- **UI 组件 (`ui/`)**:
  - `components/`: `StressTestDialog.kt`、`AgingReportDialog.kt`、`NetworkDiagnosticsDialog.kt`、`CrashDiagnosticsDialog.kt`、`WebServerDialog.kt`、`QrCodeDialog.kt`、`RemoteFleetDialog.kt`、`ScreenTouchTestDialog.kt`、`SysPropBrowserDialog.kt`、`AudioToneDialog.kt`、`AotDialog.kt`、`LiveLineChart.kt`、`ToolHubComponents.kt`。
  - `theme/`: `Color.kt`、`Theme.kt`、`Type.kt`。

### 2. Web 工作台 (`apps/web/src`)
- `App.tsx`: `AppShell` 侧边栏与 Hash 路由骨架（`#/fleet`、`#/workbench/<mac>`、`#/batch`、`#/admin`、`#/settings`）、多语言与主题管理。
- `components/DeviceWorkbench.tsx`: 单机工作台（遥测历史曲线、CPU/GPU 调频锁频、OTG/ADB/压测、音频信号发生器、系统属性编辑 & AOT、报告/崩溃诊断/快照下载）。
- `components/BatchOpsView.tsx`: 多机批量运维与并发 Ping / `setprop`。
- `components/AdminAuditView.tsx`: 多账号 RBAC 管理（`admin` / `operator` / `viewer`）与操作审计日志。

### 3. Rust 服务端 (`apps/server/src`)
- `main.rs`: Axum 路由、WS 反向隧道与分片响应组装、`/proxy/{mac}` 转发、设备别名/分组持久化。
- `auth.rs`: 多账号密码哈希与 HMAC-SHA256 持久化 Token 签发/校验。
- `storage.rs`: 历史遥测（`fleet_history/*.jsonl`）、审计日志（`fleet_audit.jsonl`）与 SSH Ed25519 公钥托管。

## 开发约定与注意事项

1. **依赖管理**: Android 依赖统一维护在 `gradle/libs.versions.toml` 并在 `apps/android/build.gradle.kts` 引用；Web 依赖维护在 `apps/web/package.json`；Rust 依赖维护在 `apps/server/Cargo.toml`。
2. **代码风格与语言规范**:
   - Android 遵循 `gradle.properties` 中的 `kotlin.code.style=official`，Java 编译目标为 `JavaVersion.VERSION_11`，图标优先使用 `Icons.AutoMirrored.Filled.*`。
   - Android 与 Web 均支持简体中文、繁體中文（TW/HK）、English、日本語与 한국어。
3. **签名、密钥与构建期注入**:
   - Android 签名通过根目录 `keystore.properties`（已 gitignore）或 `TOOLHUB_KEYSTORE_*` 环境变量读取；远程服务器地址与 Token 可通过 `TOOLHUB_FLEET_URL` / `TOOLHUB_DEVICE_TOKEN`（或 `keystore.properties` 中的 `fleetUrl` / `fleetToken`）注入，严禁将私钥、口令、服务器公网 IP 或 Token 提交到公开仓库。
   - `jniLibs.useLegacyPackaging = true` 与 `abiFilters += listOf("arm64-v8a", "armeabi-v7a")` 用于保证 `libstressapptest.so` 可被正确解压并执行，请勿破坏此配置。
4. **版本发布规范**:
   - 每次上传发布到 GitHub 时，必须在 `apps/android/build.gradle.kts` 中将 `versionCode` 与 `versionName` 补丁号同步 `+1`（例如 `10007` / `0.0.7` -> `10008` / `0.0.8`），并创建与推送对应的 annotated tag（`v<versionName>`）触发 GitHub Actions 自动发布 Release。
   - **打 tag 与执行 `git push` 发布前，必须先经过用户明确确认，禁止未经确认直接打 tag 或推送。**
5. **序列化与接口兼容**:
   - `WebApiModels.kt` 使用 `kotlinx.serialization`。修改接口字段时注意与 `apps/android/src/main/assets/web_console.html` 及 `apps/web/src/types.ts` 保持兼容。
6. **单元测试**:
   - 纯 Kotlin/逻辑测试放在 `apps/android/src/test/java/com/qytech/toolhub/`，避免在 JVM 单元测试中直接依赖未 mock 的 Android Framework 类。
