# ToolHub

面向嵌入式 Android 设备的系统级硬件调试、实时遥测监控、老化压测与跨网段车队远程运维 MonoRepo 平台（包名：`com.qytech.toolhub`）。支持简体中文、繁體中文（TW/HK）、English、日本語与 한국어。

---

## MonoRepo 架构

```text
ToolHub/
├── apps/
│   ├── android/        # 原生 Android 系统级调试客户端 (Kotlin + Jetpack Compose + Material 3)
│   ├── web/            # 车队运维工作台 (Vite + React 19 + Tailwind v4 + shadcn/ui)
│   └── server/         # 远程隧道与管理服务端 (Rust + Axum + WebSocket 反向隧道)
├── package.json        # 根 npm Workspace 与统一脚本
├── Cargo.toml          # 根 Cargo Workspace
└── settings.gradle.kts # 将 Gradle :app 映射至 apps/android
```

---

## 核心功能

### 1. Android 客户端 (`apps/android`)
- **总览与开发者控制 (`HomeScreen`)**：USB ADB、网络 ADB、充电常亮、系统重启、系统自检（UID / 权限 / OTG / 开机策略）与多语言切换。
- **OTG 节点与开机策略 (`OtgModeScreen` / `BootCompletedReceiver`)**：自动扫描 sysfs `otg_mode` 节点并切换 `otg` / `host` / `peripheral`，枚举 USB 外设（VID/PID/类别），支持 `LOCKED_BOOT_COMPLETED` / `BOOT_COMPLETED` / `QUICKBOOT_POWERON` 自动应用默认策略。
- **性能与温控调频 (`PerfScreen` / `ThermalScreen`)**：实时采集 CPU、GPU、内存/ZRAM、网络/磁盘 I/O、ART GC 与多温区曲线，支持按集群切换 CPU `cpufreq` / GPU `devfreq` 调度器与频率锁定。
- **音频与板级节点 (`AudioScreen`)**：ALSA 声卡与 PCM 流状态监控、信号发生器（正弦波 / 扫频 / 白噪声）与 Sysfs 节点读写。
- **产线诊断工具 (`ToolsScreen`)**：集成原生 `libstressapptest.so` 老化压测（含 `StressKeepAliveService` 保活）、ART AOT 批量优化、`getprop`/`setprop` 浏览器、屏幕坏点/多指触控测试、网络 Ping、Tombstone 崩溃诊断与系统 Bugreport 收集。
- **后台保活与远程隧道 (`FleetKeepAliveService` / `RemoteFleetRepository` / `WsReverseTunnelClient`)**：支持构建期注入服务端地址与 Token，App 启动或开机后静默建立 WebSocket 反向隧道与心跳上报，退居后台仍持续在线。

### 2. Web 车队工作台 (`apps/web`)
- **设备总览**：跨网段在线状态、峰值/平均 SoC 温度聚合、分组与别名标签、列表/卡片双视图、CSV 导出与右侧详情抽屉。
- **单机工作台**：直接在单页内完成硬件详情查看、30 点遥测趋势图、CPU/GPU 调频锁频、OTG/ADB/压测控制（参数自动对齐设备真实核心与安全内存上限）、音频信号发生器、`getprop`/`setprop`、AOT 编译与报告/崩溃日志查看及诊断快照下载。
- **批量运维**：多选在线设备并发下发 ADB、常亮、OTG、CPU Governor、批量压测、批量 Bugreport、批量测试音、批量 Ping 与 `setprop`。
- **团队权限与审计**：支持 `admin` / `operator` / `viewer` 三级角色、账号管理、操作审计日志与 5 语言 / 浅深色主题切换。

### 3. Rust 服务端 (`apps/server`)
- **WebSocket 反向隧道与代理**：通过 `/api/tunnel/ws` 接收设备接入（支持单帧与分片响应），经 `/proxy/{mac}/*` 将 Web 工作台指令透明转发至内网或 NAT 后的设备。
- **多账号 RBAC 与持久化**：HMAC-SHA256 签发持久化会话 Token（服务重启不掉线）、用户持久化（`fleet_users.json`）、设备元数据持久化（`fleet_devices.json`）、历史遥测（`fleet_history/*.jsonl`）与审计日志（`fleet_audit.jsonl`）。

---

## 快速开始

### 环境要求
- **Android**：JDK 17+、Android SDK（`compileSdk = 37` / `minSdk = 31`）
- **Web**：Node.js 20+
- **Server**：Rust stable toolchain（`cargo`）

### 常用开发与构建命令

```bash
# 1. 安装依赖并构建 Web 工作台
npm install
npm run build:web

# 2. 启动 Rust 服务端（自动托管 apps/web/dist）
FLEET_ADMIN_PASSWORD=your_admin_pass FLEET_DEVICE_TOKEN=your_device_token npm run dev:server

# 3. Android 单元测试与 APK 构建
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew :app:assembleRelease
```

### Android 构建期配置（签名与远程接入）
仓库不包含私有签名密钥与生产服务器配置（`*.jks`、`keystore.properties` 等已加入 `.gitignore`）。

- **本地 `keystore.properties`**（项目根目录）：
  ```properties
  storeFile=SystemSignature.jks
  storePassword=your_store_password
  keyAlias=your_key_alias
  keyPassword=your_key_password
  fleetUrl=https://your-fleet-server.example.com
  fleetToken=your_device_token
  ```
- **环境变量 / GitHub Actions Secrets**：支持通过 `TOOLHUB_KEYSTORE_*`、`TOOLHUB_FLEET_URL`、`TOOLHUB_DEVICE_TOKEN` 注入。

### 服务端部署建议
1. 使用 `cargo build --release -p toolhub-fleet` 编译服务端二进制，配合 `apps/web/dist` 部署。
2. 建议前置 **Nginx** 反向代理并配置 HTTPS/WSS，确保开启 `proxy_http_version 1.1`、`Upgrade` / `Connection` WebSocket 升级头及较长的 `proxy_read_timeout`（如 `3600s`）。
3. 如需开启 SSH 远程 ADB 中继，可在服务端运行 `apps/server/deploy/setup-relay.sh`。

---

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。
