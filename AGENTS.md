# AGENTS.md

本文件为在 `ToolHub` 仓库中协作的 AI 编程助手提供项目结构、构建验证命令及开发规范指引。

## 项目概览

- **应用名称 / 包名**: `ToolHub` / `com.qytech.toolhub`
- **定位**: 面向嵌入式 Android 设备的系统级硬件调试、实时遥测监控、诊断与压测工具。
- **架构模式**: 单模块 Android 应用（`:app`），采用 Jetpack Compose + Material 3 + ViewModel + 单例 Repository（`object *Repository`）架构。
- **系统级特性**: `AndroidManifest.xml` 声明了 `android:sharedUserId="android.uid.system"` 及特权权限，用于直接读写 sysfs 节点、系统属性（`SystemPropertiesHelper`）、执行原生压测二进制、获取 `Bugreport` 与控制开发者选项。

## 常用命令

```bash
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

- **入口与导航**:
  - `app/src/main/java/com/qytech/toolhub/MainActivity.kt`: 启动 `WebReportServer.start(applicationContext)`，通过 `MainApp()` 根据屏幕宽度自适应切换 `NavigationBar` / `NavigationRail` 与 `NavHost`。
  - 顶级页面路由定义在 `Screen` sealed class：`Home`、`Otg`、`Perf`、`Thermal`、`Audio`、`Tools`、`Device`。
- **页面与 ViewModel (`com.qytech.toolhub`)**:
  - 页面：`HomeScreen.kt`、`OtgModeScreen.kt`、`PerfScreen.kt`、`ThermalScreen.kt`、`AudioScreen.kt`、`ToolsScreen.kt`、`DeviceInfoScreen.kt`
  - 状态管理：`OtgModeViewModel.kt`、`HardwareViewModel.kt`、`MonitorViewModel.kt`、`DeviceInfoViewModel.kt`
- **数据层与系统交互 (`com.qytech.toolhub.data`)**:
  - `OtgRepository.kt`: sysfs `otg_mode` 节点扫描、模式读写、USB 外设枚举及开机默认策略恢复。
  - `StressTestRepository.kt` & `ReportStorageManager.kt`: 调用 `libstressapptest.so` 与 Kotlin 压测任务，管理老化测试状态与报告持久化。
  - `TelemetryRepository.kt`、`HardwareRepository.kt`、`DeviceInfoRepository.kt`、`DevControlsRepository.kt`、`AudioToneGenerator.kt`: 性能遥测、温度/CPU/GPU 调频锁频、ALSA 音频节点、信号发生器、设备信息与开发者选项控制。
  - `NetworkDiagnosticsRepository.kt`、`CrashDiagnosticsRepository.kt`、`BugreportManagerHelper.kt`、`SystemPropertiesHelper.kt`: 网络、崩溃日志与系统诊断、系统属性读写。
  - `WebReportServer.kt`、`WebApiModels.kt`、`WebConsoleHtml.kt`: 基于 `ServerSocket` 的内置 HTTP 服务与序列化模型，配合 `app/src/main/assets/web_console.html` 提供支持多语言与 PIN 码保护的 Web 控制台。
  - `ToolHubPreferences.kt`: 本地偏好设置持久化。
- **开机自启与后台保活 (`com.qytech.toolhub.boot` / `com.qytech.toolhub.service`)**:
  - `BootCompletedReceiver.kt`: `directBootAware="true"`，监听 `LOCKED_BOOT_COMPLETED`、`BOOT_COMPLETED` 与 `QUICKBOOT_POWERON`，在 `Dispatchers.IO` 上通过 `goAsync()` 应用默认 OTG 策略。
  - `StressKeepAliveService.kt`: 压测与 Bugreport 收集期间的 ForegroundService + `PARTIAL_WAKE_LOCK` 保活服务。
- **UI 组件 (`com.qytech.toolhub.ui`)**:
  - `components/`: `StressTestDialog.kt`、`AgingReportDialog.kt`、`NetworkDiagnosticsDialog.kt`、`CrashDiagnosticsDialog.kt`、`WebServerDialog.kt`、`QrCodeDialog.kt`、`ScreenTouchTestDialog.kt`、`SysPropBrowserDialog.kt`、`AudioToneDialog.kt`、`AotDialog.kt`、`LiveLineChart.kt`、`ToolHubComponents.kt`。
  - `theme/`: `Color.kt`、`Theme.kt`、`Type.kt`。

## 开发约定与注意事项

1. **依赖管理**: 所有插件与第三方库版本统一维护在 `gradle/libs.versions.toml` 中，新增依赖请优先在 Version Catalog 中声明并在 `app/build.gradle.kts` 引用。
2. **代码风格与语言规范**:
   - 遵循 `gradle.properties` 中的 `kotlin.code.style=official`，Java 编译目标为 `JavaVersion.VERSION_11`。
   - 使用 Material Icons 时优先选择 `Icons.AutoMirrored.Filled.*`（避免已弃用的镜像图标警告）。
   - 多语言支持覆盖简体中文 (`values`)、繁体中文 (`values-zh-rTW` / `values-zh-rHK`)、英文 (`values-en`)、日文 (`values-ja`) 与韩文 (`values-ko`)，资源过滤器配置为 `localeFilters += listOf("zh", "en", "ja", "ko")`。
3. **签名与 ABI/打包配置**:
   - 签名配置通过根目录 `keystore.properties`（已 gitignore）或 `TOOLHUB_KEYSTORE_*` 环境变量读取，严禁将 `*.jks` 或明文口令提交到公开仓库。
   - `jniLibs.useLegacyPackaging = true` 与 `abiFilters += listOf("arm64-v8a", "armeabi-v7a")` 用于保证原生可执行文件 `libstressapptest.so` 可被正确解压并执行，请勿破坏此配置。
   - `app/src/main/baseline-prof.txt` 定义了应用专属 Baseline Profile 热点规则，`gradle.properties` 已开启 `org.gradle.configuration-cache=true`。
4. **版本发布规范**:
   - 每次上传发布到 GitHub 时，必须在 `app/build.gradle.kts` 中将 `versionCode` 与 `versionName` 补丁号同步 `+1`（例如 `10002` / `0.0.2` -> `10003` / `0.0.3`），并创建与推送对应的 annotated tag（`v<versionName>`，附带清晰的发布说明）触发 GitHub Actions 自动发布 Release。
4. **序列化与 Web API**:
   - `WebApiModels.kt` 使用 `kotlinx.serialization`。修改接口数据结构时注意保持字段兼容性，避免破坏 `app/src/main/assets/web_console.html` 的前端解析。
5. **单元测试**:
   - 纯 Kotlin/逻辑测试放在 `app/src/test/java/com/qytech/toolhub/`（参考 `OtgAndSerializationUnitTest.kt`），避免在 JVM 单元测试中直接依赖未 mock 的 Android Framework 类。
