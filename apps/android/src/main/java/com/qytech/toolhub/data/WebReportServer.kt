package com.qytech.toolhub.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.qytech.toolhub.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

data class WebServerState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val lastError: String? = null,
    val isPinRequired: Boolean = false,
    val pinCode: String = "1688",
)

object WebReportServer {

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workerPool: ExecutorService? = null
    private var appContext: Context? = null

    private var prevCpuTick: CpuTickSnapshot? = null
    private var prevNetSnap: NetworkCountersSnapshot? = null
    private var prevDiskSnap: DiskCountersSnapshot? = null

    fun start(context: Context, preferredPort: Int = 8080) {
        if (running.get()) return
        appContext = context.applicationContext

        val socket = openServerSocket(preferredPort)
        if (socket == null) {
            val err = appContext?.let { ctx ->
                AppLanguageManager.getString(
                    ctx,
                    R.string.web_port_busy_fmt,
                    preferredPort,
                    preferredPort + 10
                )
            } ?: "Ports $preferredPort~${preferredPort + 10} busy"
            _state.update { it.copy(isRunning = false, lastError = err) }
            return
        }

        serverSocket = socket
        val boundPort = socket.localPort
        running.set(true)
        workerPool = Executors.newFixedThreadPool(8)
        _state.update { it.copy(isRunning = true, port = boundPort, lastError = null) }

        acceptThread = Thread({
            while (running.get() && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    workerPool?.submit { handleClient(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }, "ToolHub-WebServer").apply {
            isDaemon = true
            start()
        }
    }

    fun setPinProtection(enabled: Boolean) {
        _state.update { it.copy(isPinRequired = enabled) }
    }

    fun regeneratePin(): String {
        val nextPin = (1000..9999).random().toString()
        _state.update { it.copy(pinCode = nextPin) }
        return nextPin
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        workerPool?.shutdownNow()
        workerPool = null
        acceptThread = null
        _state.update { it.copy(isRunning = false) }
    }

    private fun openServerSocket(preferredPort: Int): ServerSocket? {
        for (p in preferredPort..(preferredPort + 10)) {
            val s = runCatching {
                ServerSocket(p, 50, InetAddress.getByName("0.0.0.0")).apply {
                    reuseAddress = true
                }
            }.getOrNull()
            if (s != null) return s
        }
        return null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()

            val requestLine = readLineAscii(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val rawTarget = parts[1]

            var contentLength = 0
            var acceptsGzip = false
            var clientPin = ""
            while (true) {
                val headerLine = readLineAscii(input) ?: break
                if (headerLine.isEmpty()) break
                val colon = headerLine.indexOf(':')
                if (colon > 0) {
                    val name = headerLine.substring(0, colon).trim().lowercase(Locale.US)
                    val value = headerLine.substring(colon + 1).trim()
                    if (name == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    } else if (name == "accept-encoding" && value.contains(
                            "gzip",
                            ignoreCase = true
                        )
                    ) {
                        acceptsGzip = true
                    } else if (name == "x-toolhub-pin") {
                        clientPin = value
                    }
                }
            }

            var bodyText = ""
            if (method == "POST" && contentLength > 0 && contentLength <= 1024 * 1024) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                bodyText = String(buf, 0, read, StandardCharsets.UTF_8)
            }

            val qIdx = rawTarget.indexOf('?')
            val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
            val queryParams =
                if (qIdx >= 0) parseQuery(rawTarget.substring(qIdx + 1)) else emptyMap()
            val ctx = appContext ?: return
            val isHead = method == "HEAD"

            when {
                method == "GET" || method == "HEAD" -> handleGet(
                    ctx,
                    path,
                    queryParams,
                    out,
                    isHead,
                    acceptsGzip
                )

                method == "POST" -> handlePost(ctx, path, bodyText, clientPin, out)
                else -> sendTextResponse(
                    out,
                    405,
                    "Method Not Allowed",
                    "text/plain; charset=utf-8",
                    "Method Not Allowed",
                    false
                )
            }
        }
    }

    private fun handleGet(
        ctx: Context,
        path: String,
        queryParams: Map<String, String>,
        out: OutputStream,
        isHead: Boolean,
        acceptsGzip: Boolean,
    ) {
        when (path) {
            "/", "/index.html" -> {
                sendTextResponse(
                    out,
                    200,
                    "OK",
                    "text/html; charset=utf-8",
                    WebConsoleHtml.buildAppHtml(ctx),
                    isHead,
                    acceptsGzip
                )
            }

            "/api/status" -> sendJson(out, jsonCodec.encodeToString(buildLegacyStatus(ctx)), isHead)
            "/api/overview" -> sendJson(out, jsonCodec.encodeToString(buildOverview(ctx)), isHead)
            "/api/telemetry" -> sendJson(out, jsonCodec.encodeToString(buildTelemetry(ctx)), isHead)
            "/api/audio" -> sendJson(out, jsonCodec.encodeToString(buildAudio(ctx)), isHead)
            "/api/sysprops" -> sendJson(out, jsonCodec.encodeToString(buildSysProps()), isHead)
            "/api/aot" -> sendJson(out, jsonCodec.encodeToString(buildAot(ctx)), isHead)
            "/api/reports" -> sendJson(out, jsonCodec.encodeToString(buildReportsList(ctx)), isHead)
            "/api/crash" -> sendJson(out, jsonCodec.encodeToString(buildCrash(ctx)), isHead)
            "/api/snapshot" -> {
                val resp = WebSnapshotResponse(snapshot = buildTextSnapshot(ctx))
                sendJson(out, jsonCodec.encodeToString(resp), isHead)
            }

            "/api/report-content" -> {
                val fileName = queryParams["file"].orEmpty()
                val report =
                    ReportStorageManager.listAllReports(ctx).firstOrNull { it.fileName == fileName }
                val resp = if (report == null || !report.file.exists()) {
                    WebTextContentResponse(error = "Report not found: $fileName")
                } else {
                    val content = runCatching { report.file.readText() }.getOrElse {
                        AppLanguageManager.getString(
                            ctx,
                            R.string.web_msg_read_failed_fmt,
                            it.message ?: ""
                        )
                    }
                    WebTextContentResponse(content = content)
                }
                sendJson(out, jsonCodec.encodeToString(resp), isHead)
            }

            "/api/tombstone-content" -> {
                val name = queryParams["name"].orEmpty()
                val file = resolveSafeTombstoneFile(name)
                val resp = if (file == null) {
                    WebTextContentResponse(error = "Tombstone not found")
                } else {
                    val content = runCatching { file.readText() }.getOrElse {
                        AppLanguageManager.getString(
                            ctx,
                            R.string.web_msg_read_failed_fmt,
                            it.message ?: ""
                        )
                    }
                    WebTextContentResponse(content = content)
                }
                sendJson(out, jsonCodec.encodeToString(resp), isHead)
            }

            "/download" -> {
                val fileName = queryParams["file"].orEmpty()
                val report =
                    ReportStorageManager.listAllReports(ctx).firstOrNull { it.fileName == fileName }
                if (report == null || !report.file.exists()) {
                    sendTextResponse(
                        out,
                        404,
                        "Not Found",
                        "text/plain; charset=utf-8",
                        "File not found: $fileName",
                        isHead
                    )
                } else {
                    sendFileDownload(out, report.file, isHead)
                }
            }

            "/tombstone/download" -> {
                val name = queryParams["name"].orEmpty()
                val file = resolveSafeTombstoneFile(name)
                if (file == null) {
                    sendTextResponse(
                        out,
                        404,
                        "Not Found",
                        "text/plain; charset=utf-8",
                        "Tombstone not found",
                        isHead
                    )
                } else {
                    sendFileDownload(out, file, isHead)
                }
            }

            "/generate-crash" -> {
                runCatching {
                    val summary = CrashDiagnosticsRepository.loadDiagnostics(ctx)
                    CrashDiagnosticsRepository.exportDiagnosticsReport(ctx, summary)
                }
                sendRedirect(out, "/")
            }

            else -> {
                sendTextResponse(
                    out,
                    404,
                    "Not Found",
                    "text/plain; charset=utf-8",
                    "404 Not Found: $path",
                    isHead
                )
            }
        }
    }

    private inline fun <reified T> decodeBody(bodyText: String, defaultValue: T): T {
        if (bodyText.isBlank()) return defaultValue
        return runCatching { jsonCodec.decodeFromString<T>(bodyText) }.getOrDefault(defaultValue)
    }

    private fun handlePost(ctx: Context, path: String, bodyText: String, clientPin: String, out: OutputStream) {
        val srvState = _state.value
        if (srvState.isPinRequired && clientPin.trim() != srvState.pinCode) {
            val pinErr = WebApiActionResponse(
                ok = false,
                message = AppLanguageManager.getString(ctx, R.string.web_pin_error),
            )
            sendJson(out, jsonCodec.encodeToString(pinErr), false)
            return
        }

        val resp: WebApiActionResponse = when (path) {
            "/api/cpufreq/governor" -> {
                val req = decodeBody(bodyText, WebModeRequest())
                val gov = req.mode.trim().ifEmpty { "schedutil" }
                val r = HardwareRepository.setAllCpuGovernors(gov)
                WebApiActionResponse(
                    ok = r.isSuccess,
                    message = if (r.isSuccess) {
                        AppLanguageManager.getString(ctx, R.string.hw_write_ok_fmt, "CPU scaling_governor", gov)
                    } else {
                        AppLanguageManager.getString(ctx, R.string.action_failed_fmt, r.exceptionOrNull()?.message ?: "")
                    },
                )
            }

            "/api/cpufreq/policy-gov" -> {
                val req = decodeBody(bodyText, WebPolicyGovRequest())
                val r = HardwareRepository.setCpuGovernor(req.policy.trim(), req.governor.trim())
                WebApiActionResponse(
                    ok = r.isSuccess,
                    message = if (r.isSuccess) {
                        AppLanguageManager.getString(ctx, R.string.hw_write_ok_fmt, "${req.policy}/scaling_governor", req.governor)
                    } else {
                        AppLanguageManager.getString(ctx, R.string.action_failed_fmt, r.exceptionOrNull()?.message ?: "")
                    },
                )
            }

            "/api/cpufreq/policy-freq" -> {
                val req = decodeBody(bodyText, WebPolicyFreqRequest())
                val r = HardwareRepository.setCpuFreqBounds(req.policy.trim(), req.minFreq, req.maxFreq)
                WebApiActionResponse(
                    ok = r.isSuccess,
                    message = if (r.isSuccess) {
                        val updated = r.getOrNull()
                        AppLanguageManager.getString(ctx, R.string.hw_write_ok_fmt, req.policy, updated?.rangeText ?: "")
                    } else {
                        AppLanguageManager.getString(ctx, R.string.action_failed_fmt, r.exceptionOrNull()?.message ?: "")
                    },
                )
            }

            "/api/gpufreq/gov" -> {
                val req = decodeBody(bodyText, WebPolicyGovRequest())
                val r = HardwareRepository.setGpuGovernor(req.policy.trim(), req.governor.trim())
                WebApiActionResponse(
                    ok = r.isSuccess,
                    message = if (r.isSuccess) {
                        AppLanguageManager.getString(ctx, R.string.hw_write_ok_fmt, "${req.policy}/governor", req.governor)
                    } else {
                        AppLanguageManager.getString(ctx, R.string.action_failed_fmt, r.exceptionOrNull()?.message ?: "")
                    },
                )
            }

            "/api/gpufreq/freq" -> {
                val req = decodeBody(bodyText, WebPolicyFreqRequest())
                val r = HardwareRepository.setGpuFreqBounds(req.policy.trim(), req.minFreq, req.maxFreq)
                WebApiActionResponse(
                    ok = r.isSuccess,
                    message = if (r.isSuccess) {
                        val updated = r.getOrNull()
                        AppLanguageManager.getString(ctx, R.string.hw_write_ok_fmt, req.policy, updated?.rangeText ?: "")
                    } else {
                        AppLanguageManager.getString(ctx, R.string.action_failed_fmt, r.exceptionOrNull()?.message ?: "")
                    },
                )
            }

            "/api/audio/tone" -> {
                val req = decodeBody(bodyText, WebAudioToneRequest())
                val wf = ToneWaveform.entries.firstOrNull { it.name == req.waveform } ?: ToneWaveform.SINE_1K
                val ch = ToneChannelMode.entries.firstOrNull { it.name == req.channelMode } ?: ToneChannelMode.STEREO
                val rate = if (req.sampleRateHz in AudioToneGenerator.SUPPORTED_SAMPLE_RATES) req.sampleRateHz else 48000
                val db = if (req.attenuationDb in AudioToneGenerator.SUPPORTED_DB_LEVELS) req.attenuationDb else -6
                AudioToneGenerator.updateConfig(wf, ch, rate, db)
                when (req.action.lowercase(Locale.US)) {
                    "start" -> {
                        AudioToneGenerator.start()
                        WebApiActionResponse(ok = true, message = "${AppLanguageManager.getString(ctx, R.string.tone_btn_start)} ($rate Hz)")
                    }
                    "stop" -> {
                        AudioToneGenerator.stop()
                        WebApiActionResponse(ok = true, message = AppLanguageManager.getString(ctx, R.string.tone_btn_stop))
                    }
                    else -> {
                        WebApiActionResponse(ok = true, message = "OK ($rate Hz · $db dBFS)")
                    }
                }
            }

            "/api/sysfs/custom-add" -> {
                val req = decodeBody(bodyText, WebSysfsWriteRequest())
                val trimmed = req.path.trim()
                if (!trimmed.startsWith("/sys/") && !trimmed.startsWith("/proc/")) {
                    WebApiActionResponse(ok = false, message = AppLanguageManager.getString(ctx, R.string.hw_invalid_path))
                } else {
                    ToolHubPreferences(ctx).addCustomSysfsPath(trimmed)
                    WebApiActionResponse(ok = true, message = AppLanguageManager.getString(ctx, R.string.hw_custom_added_fmt, trimmed))
                }
            }

            "/api/sysfs/custom-remove" -> {
                val req = decodeBody(bodyText, WebSysfsWriteRequest())
                ToolHubPreferences(ctx).removeCustomSysfsPath(req.path.trim())
                WebApiActionResponse(ok = true, message = AppLanguageManager.getString(ctx, R.string.hw_custom_removed))
            }

            "/api/sysprops/set" -> {
                val req = decodeBody(bodyText, WebSysPropSetRequest())
                val r = SystemPropertiesHelper.set(req.key.trim(), req.value.trim())
                WebApiActionResponse(
                    ok = r.isSuccess,
                    message = if (r.isSuccess) {
                        "✓ ${req.key.trim()} = [${r.getOrNull().orEmpty()}]"
                    } else {
                        "✗ ${req.key.trim()}: ${r.exceptionOrNull()?.message ?: "failed"}"
                    },
                )
            }

            "/api/aot/compile" -> {
                val req = decodeBody(bodyText, WebAotCompileRequest())
                if (req.mode == "BATCH_SPEED") {
                    val apps = AotRepository.loadInstalledAppsAot(ctx)
                    val targets = apps.filter { !it.isSpeedCompiled && (!it.isSystemApp || it.packageName == ctx.packageName) }.map { it.packageName }
                    if (targets.isEmpty()) {
                        WebApiActionResponse(ok = true, message = "All target apps are already speed-compiled")
                    } else {
                        AotRepository.startBatchCompile(targets, AotMode.SPEED, ctx)
                        WebApiActionResponse(ok = true, message = AppLanguageManager.getString(ctx, R.string.aot_batch_preparing_fmt, targets.size, "speed"))
                    }
                } else {
                    val mode = AotMode.entries.firstOrNull { it.name == req.mode } ?: AotMode.SPEED
                    val pkg = req.pkg.trim().ifEmpty { ctx.packageName }
                    val r = AotRepository.compilePackage(pkg, mode)
                    WebApiActionResponse(
                        ok = r.isSuccess,
                        message = if (r.isSuccess) {
                            "✓ $pkg -> ${mode.rawValue}"
                        } else {
                            "✗ $pkg: ${r.exceptionOrNull()?.message ?: "failed"}"
                        },
                    )
                }
            }

            "/api/otg/mode" -> {
                val req = decodeBody(bodyText, WebModeRequest())
                val mode = OtgMode.entries.firstOrNull { it.name == req.mode } ?: OtgMode.OTG
                val nodes = OtgRepository.discoverOtgNodes().filter { it.isWritable }
                var okCount = 0
                nodes.forEach { node ->
                    if (OtgRepository.setNodeMode(
                            ctx,
                            File(node.path),
                            mode,
                            syncAdbIfOtg = true
                        ).isSuccess
                    ) okCount++
                }
                WebApiActionResponse(
                    ok = okCount > 0,
                    message = if (okCount > 0) {
                        AppLanguageManager.getString(
                            ctx,
                            R.string.web_msg_switch_nodes_fmt,
                            okCount,
                            mode.name
                        )
                    } else {
                        AppLanguageManager.getString(ctx, R.string.web_msg_no_writable_otg)
                    },
                )
            }

            "/api/otg/node" -> {
                val req = decodeBody(bodyText, WebNodeModeRequest())
                val mode = OtgMode.entries.firstOrNull { it.name == req.mode } ?: OtgMode.OTG
                val valid =
                    OtgRepository.discoverOtgNodes().any { it.path == req.path && it.isWritable }
                if (!valid) {
                    WebApiActionResponse(
                        ok = false,
                        message = AppLanguageManager.getString(ctx, R.string.web_msg_node_invalid)
                    )
                } else {
                    val r =
                        OtgRepository.setNodeMode(ctx, File(req.path), mode, syncAdbIfOtg = true)
                    WebApiActionResponse(
                        ok = r.isSuccess,
                        message = if (r.isSuccess) {
                            AppLanguageManager.getString(
                                ctx,
                                R.string.web_msg_node_switched_fmt,
                                mode.name
                            )
                        } else {
                            AppLanguageManager.getString(
                                ctx,
                                R.string.web_msg_node_switch_failed_fmt,
                                r.exceptionOrNull()?.message ?: ""
                            )
                        },
                    )
                }
            }

            "/api/controls/adb" -> {
                val req = decodeBody(bodyText, WebToggleRequest(true))
                val r = OtgRepository.setAdbEnabled(ctx, req.enabled)
                val actionStr = if (req.enabled) AppLanguageManager.getString(
                    ctx,
                    R.string.action_enabled
                ) else AppLanguageManager.getString(ctx, R.string.action_disabled)
                WebApiActionResponse(
                    ok = r.isSuccess,
                    message = if (r.isSuccess) {
                        AppLanguageManager.getString(
                            ctx,
                            R.string.web_msg_adb_toggled_fmt,
                            actionStr
                        )
                    } else {
                        AppLanguageManager.getString(
                            ctx,
                            R.string.action_failed_fmt,
                            r.exceptionOrNull()?.message ?: ""
                        )
                    },
                )
            }

            "/api/controls/wifi-adb" -> {
                val req = decodeBody(bodyText, WebToggleRequest(false))
                val r = DevControlsRepository.setWifiAdbEnabled(ctx, req.enabled)
                val actionStr = if (req.enabled) AppLanguageManager.getString(
                    ctx,
                    R.string.action_enabled
                ) else AppLanguageManager.getString(ctx, R.string.action_disabled)
                WebApiActionResponse(
                    ok = r.isSuccess,
                    message = if (r.isSuccess) {
                        AppLanguageManager.getString(
                            ctx,
                            R.string.web_msg_wifi_adb_toggled_fmt,
                            actionStr
                        )
                    } else {
                        AppLanguageManager.getString(
                            ctx,
                            R.string.action_failed_fmt,
                            r.exceptionOrNull()?.message ?: ""
                        )
                    },
                )
            }

            "/api/controls/stay-awake" -> {
                val req = decodeBody(bodyText, WebToggleRequest(false))
                val r = DevControlsRepository.setStayAwakeEnabled(ctx, req.enabled)
                val actionStr = if (req.enabled) AppLanguageManager.getString(
                    ctx,
                    R.string.action_enabled
                ) else AppLanguageManager.getString(ctx, R.string.action_disabled)
                WebApiActionResponse(
                    ok = r.isSuccess,
                    message = if (r.isSuccess) {
                        AppLanguageManager.getString(
                            ctx,
                            R.string.web_msg_stay_awake_toggled_fmt,
                            actionStr
                        )
                    } else {
                        AppLanguageManager.getString(
                            ctx,
                            R.string.action_failed_fmt,
                            r.exceptionOrNull()?.message ?: ""
                        )
                    },
                )
            }

            "/api/controls/boot-policy" -> {
                val req = decodeBody(bodyText, WebBootPolicyRequest())
                val prefs = ToolHubPreferences(ctx)
                prefs.setAutoOtgOnBoot(req.autoOtg)
                prefs.setAutoAdbOnBoot(req.autoAdb)
                WebApiActionResponse(
                    ok = true,
                    message = AppLanguageManager.getString(
                        ctx,
                        R.string.web_msg_boot_policy_updated
                    )
                )
            }

            "/api/sysfs/write" -> {
                val req = decodeBody(bodyText, WebSysfsWriteRequest())
                val prefs = ToolHubPreferences(ctx)
                val node = HardwareRepository.discoverBoardSysfsNodes(prefs.getCustomSysfsPaths())
                    .firstOrNull { it.path == req.path && it.isWritable }
                if (node == null) {
                    WebApiActionResponse(
                        ok = false,
                        message = AppLanguageManager.getString(ctx, R.string.web_msg_sysfs_invalid)
                    )
                } else {
                    val r = HardwareRepository.writeSysfsNode(
                        node.file,
                        req.value,
                        node.group,
                        node.isCustom
                    )
                    WebApiActionResponse(
                        ok = r.isSuccess,
                        message = if (r.isSuccess) {
                            AppLanguageManager.getString(
                                ctx,
                                R.string.web_msg_sysfs_written_fmt,
                                node.name,
                                req.value
                            )
                        } else {
                            AppLanguageManager.getString(
                                ctx,
                                R.string.web_msg_sysfs_write_failed_fmt,
                                r.exceptionOrNull()?.message ?: ""
                            )
                        },
                    )
                }
            }

            "/api/stress/start" -> {
                val req = decodeBody(bodyText, WebStressStartRequest())
                val type =
                    StressTestType.entries.firstOrNull { it.name == req.type } ?: StressTestType.SAT
                StressTestRepository.startStressTest(
                    ctx,
                    type,
                    req.threads,
                    req.ramMB,
                    req.duration
                )
                val testLabel = when (type) {
                    StressTestType.SAT -> AppLanguageManager.getString(
                        ctx,
                        R.string.stress_mode_sat
                    )

                    StressTestType.CPU -> AppLanguageManager.getString(
                        ctx,
                        R.string.stress_mode_cpu
                    )

                    StressTestType.MEMORY -> AppLanguageManager.getString(
                        ctx,
                        R.string.stress_mode_mem
                    )

                    StressTestType.COMBINED -> "Combined"
                }
                WebApiActionResponse(
                    ok = true,
                    message = AppLanguageManager.getString(
                        ctx,
                        R.string.web_msg_stress_started_fmt,
                        testLabel
                    )
                )
            }

            "/api/stress/stop" -> {
                StressTestRepository.stopStressTest()
                WebApiActionResponse(
                    ok = true,
                    message = AppLanguageManager.getString(ctx, R.string.web_msg_stress_stopped)
                )
            }

            "/api/stress/export" -> {
                val report = StressTestRepository.state.value.lastAgingReport
                if (report == null) {
                    WebApiActionResponse(
                        ok = false,
                        message = AppLanguageManager.getString(
                            ctx,
                            R.string.web_msg_no_aging_report
                        )
                    )
                } else {
                    val saved = StressTestRepository.exportAgingReportToFile(ctx, report)
                    WebApiActionResponse(ok = true, message = saved.displayMessage)
                }
            }

            "/api/crash/export" -> {
                val summary = CrashDiagnosticsRepository.loadDiagnostics(ctx)
                val saved = CrashDiagnosticsRepository.exportDiagnosticsReport(ctx, summary)
                WebApiActionResponse(ok = true, message = saved.displayMessage)
            }

            "/api/bugreport/start" -> {
                BugreportManagerHelper.startSystemBugreport(ctx)
                WebApiActionResponse(
                    ok = true,
                    message = AppLanguageManager.getString(ctx, R.string.web_msg_bugreport_started)
                )
            }

            "/api/device/ping" -> {
                val req = decodeBody(bodyText, WebPingRequest())
                val pingRes = runBlocking {
                    NetworkDiagnosticsRepository.executePing(
                        req.host.trim(),
                        count = 4
                    )
                }
                WebApiActionResponse(
                    ok = pingRes.isSuccess,
                    output = pingRes.rawOutput.ifBlank {
                        pingRes.error ?: AppLanguageManager.getString(
                            ctx,
                            R.string.web_msg_no_output
                        )
                    },
                )
            }

            "/api/device/reboot" -> {
                val rebootResp = WebApiActionResponse(
                    ok = true,
                    message = AppLanguageManager.getString(ctx, R.string.web_msg_rebooting)
                )
                sendJson(out, jsonCodec.encodeToString(rebootResp), false)
                Thread {
                    Thread.sleep(300)
                    DevControlsRepository.rebootDevice(ctx)
                }.start()
                return
            }

            else -> {
                sendTextResponse(
                    out,
                    404,
                    "Not Found",
                    "application/json",
                    "{\"error\":\"Unknown endpoint\"}",
                    false
                )
                return
            }
        }
        sendJson(out, jsonCodec.encodeToString(resp), false)
    }

    private fun buildLegacyStatus(ctx: Context): WebLegacyStatusResponse {
        val stress = StressTestRepository.state.value
        val usbVolumes = ReportStorageManager.findUsbStorageVolumes(ctx)
        val reports = ReportStorageManager.listAllReports(ctx)
        return WebLegacyStatusResponse(
            model = Build.MODEL,
            android = Build.VERSION.RELEASE,
            isStressRunning = stress.isRunning,
            stressElapsedSec = stress.elapsedSeconds,
            stressTempCelsius = stress.currentTempCelsius ?: 0f,
            usbAttached = usbVolumes.isNotEmpty(),
            usbVolume = usbVolumes.firstOrNull()?.volumeLabel.orEmpty(),
            reportCount = reports.size,
        )
    }

    private fun buildOverview(ctx: Context): WebOverviewResponse {
        val basic = DeviceInfoRepository.readBasicInfo()
        val runtime = DeviceInfoRepository.readRuntimeInfo(ctx)
        val boot = CrashDiagnosticsRepository.readBootReason()
        val uptimeSec = SystemClock.elapsedRealtime() / 1000L
        val uptimeStr = "${uptimeSec / 3600}h ${(uptimeSec % 3600) / 60}m ${uptimeSec % 60}s"

        val otgNodes = OtgRepository.discoverOtgNodes()
        val usbDevices = OtgRepository.getConnectedUsbDevices(ctx)
        val usbProps = OtgRepository.getUsbSystemProperties()
        val prefs = ToolHubPreferences(ctx)
        val bootPrefs = prefs.getBootSettings()
        val bootHistory = prefs.getBootHistory()
        val healthChecks = DevControlsRepository.runHealthChecks(
            context = ctx,
            controllableOtgCount = otgNodes.count { it.isWritable },
            totalOtgCount = otgNodes.size,
            bootSettings = bootPrefs,
        )
        val usbVolumes = ReportStorageManager.findUsbStorageVolumes(ctx)
        val stress = StressTestRepository.state.value
        val activeIp = NetworkDiagnosticsRepository.getActiveIpv4Address()
        val br = BugreportManagerHelper.state.value

        return WebOverviewResponse(
            device = WebDeviceDto(
                model = basic.model,
                brand = basic.brand,
                manufacturer = basic.manufacturer,
                androidVersion = basic.androidVersion,
                sdkInt = basic.sdkInt,
                socPlatform = basic.socPlatform,
                abi = basic.cpuAbi,
                kernelVersion = System.getProperty("os.version") ?: "-",
                buildDisplay = basic.buildNumber,
                uidSummary = basic.uidSummary,
                ramSummary = runtime.ramSummary,
                storageSummary = runtime.storageSummary,
                displaySummary = runtime.displaySummary,
                uptime = uptimeStr,
                activeIp = activeIp ?: "-",
                adbPort = usbProps.adbTcpPort.ifBlank { "5555" },
                sysBootReason = boot.sysBootReason,
                isAbnormalReboot = boot.isAbnormalReboot,
            ),
            otg = WebOtgDto(
                usbConfig = usbProps.usbConfig,
                usbState = usbProps.usbState,
                usbController = usbProps.usbController,
                adbTcpPort = usbProps.adbTcpPort.ifBlank { "5555" },
                nodes = otgNodes.map { n ->
                    WebOtgNodeDto(
                        path = n.path,
                        name = n.nodeName,
                        mode = n.currentMode?.name ?: "UNKNOWN",
                        writable = n.isWritable,
                    )
                },
                usbDevices = usbDevices.map { d ->
                    WebUsbDeviceDto(
                        name = d.productName ?: d.deviceName,
                        classLabel = d.classLabel,
                        vidPid = "%04x:%04x".format(Locale.US, d.vendorId, d.productId),
                    )
                },
            ),
            controls = WebControlsDto(
                adbEnabled = OtgRepository.isAdbEnabled(ctx),
                wifiAdbEnabled = DevControlsRepository.isWifiAdbEnabled(ctx),
                stayAwakeEnabled = DevControlsRepository.isStayAwakeEnabled(ctx),
                autoOtgOnBoot = bootPrefs.autoOtgOnBoot,
                autoAdbOnBoot = bootPrefs.autoAdbOnBoot,
            ),
            healthChecks = healthChecks.map { h ->
                WebHealthCheckDto(title = h.title, detail = h.detail, passed = h.passed)
            },
            bootHistory = bootHistory.take(5).map { b ->
                WebBootHistoryDto(
                    trigger = b.trigger,
                    success = b.success,
                    adbSummary = b.adbSummary,
                    timestampMillis = b.timestampMillis,
                )
            },
            usbStorage = WebUsbStorageDto(
                attached = usbVolumes.isNotEmpty(),
                volumeLabel = usbVolumes.firstOrNull()?.volumeLabel.orEmpty(),
                reportsDir = usbVolumes.firstOrNull()?.reportsDir?.absolutePath.orEmpty(),
            ),
            stress = WebStressDto(
                isRunning = stress.isRunning,
                type = stress.type.name,
                elapsedSeconds = stress.elapsedSeconds,
                durationSeconds = stress.durationSeconds,
                currentTempCelsius = stress.currentTempCelsius,
                memThroughputMBs = stress.memThroughputMBs,
                memErrors = stress.memErrors,
                statusMessage = stress.statusMessage,
                hasAgingReport = stress.lastAgingReport != null,
                recentLogs = stress.recentLogs,
                lastReport = stress.lastAgingReport?.let { rep ->
                    WebLastAgingReportDto(
                        isPass = rep.isPass,
                        elapsedSeconds = rep.elapsedSeconds.toLong(),
                        peakTemp = rep.peakTempCelsius,
                        errors = rep.hardwareErrors,
                        markdown = StressTestRepository.formatAgingReportMarkdown(rep),
                    )
                },
            ),
            bugreport = WebBugreportStatusDto(
                isGenerating = br.isGenerating,
                isIndeterminate = br.isIndeterminate,
                progress = br.progressPercent,
                elapsedSeconds = br.elapsedSeconds,
                statusMessage = br.statusMessage,
            ),
            pinRequired = _state.value.isPinRequired,
        )
    }

    @Synchronized
    private fun buildTelemetry(ctx: Context): WebTelemetryResponse {
        val currCpuTick = TelemetryRepository.readCpuTickSnapshot()
        val cpu = TelemetryRepository.readCpuSample(prevCpuTick, currCpuTick)
        prevCpuTick = currCpuTick
        val gpu = TelemetryRepository.readGpuSample()
        val mem = TelemetryRepository.readMemorySample(ctx)

        val currNet = TelemetryRepository.readNetworkCountersSnapshot()
        val netRate = TelemetryRepository.computeNetworkRate(prevNetSnap, currNet)
        prevNetSnap = currNet

        val currDisk = TelemetryRepository.readDiskCountersSnapshot()
        val diskRate = TelemetryRepository.computeDiskRate(prevDiskSnap, currDisk)
        prevDiskSnap = currDisk

        val space = TelemetryRepository.readStorageSpaceInfo()
        val gc = TelemetryRepository.readGcSample()
        val zones = HardwareRepository.readThermalZonesFast()
        val policies = HardwareRepository.readCpuPolicies()
        val gpuNodes = HardwareRepository.readGpuDevfreqNodes()

        return WebTelemetryResponse(
            cpuPercent = cpu.usagePercent,
            loadAvg = cpu.loadAvg1m,
            gpuAvailable = gpu.available,
            gpuPercent = gpu.usagePercent,
            gpuName = gpu.nodeName,
            gpuFreqMhz = gpu.curFreqMHz ?: 0L,
            memPercent = mem.usedPercent,
            memUsedBytes = mem.usedBytes,
            memTotalBytes = mem.totalBytes,
            zramUsedBytes = mem.zramUsedBytes ?: 0L,
            zramTotalBytes = mem.zramTotalBytes ?: 0L,
            netRxBytesPerSec = netRate.rxBytesPerSec,
            netTxBytesPerSec = netRate.txBytesPerSec,
            diskReadBytesPerSec = diskRate.readBytesPerSec,
            diskWriteBytesPerSec = diskRate.writeBytesPerSec,
            dataFreeBytes = space.availBytes,
            dataTotalBytes = space.totalBytes,
            dataUsedPercent = space.usedPercent,
            gc = WebGcDto(
                gcCount = gc.gcCount,
                gcTimeMs = gc.gcTimeMs,
                blockingGcCount = gc.blockingGcCount,
                jvmUsedBytes = gc.jvmUsedBytes,
                jvmMaxBytes = gc.jvmMaxBytes,
            ),
            thermalZones = zones.map { z ->
                WebThermalZoneDto(
                    zone = z.name,
                    type = z.type,
                    celsius = z.celsiusApprox,
                    raw = z.rawTemp
                )
            },
            cpuPolicies = policies.map { p ->
                WebCpuPolicyDto(
                    name = p.policyName,
                    affectedCpus = p.relatedCpus,
                    curFreq = p.currentFreqText,
                    rangeFreq = p.rangeText,
                    governor = p.governor,
                    minKHz = p.minKHz ?: 0L,
                    maxKHz = p.maxKHz ?: 0L,
                    availableGovernors = p.availableGovernors,
                    availableFrequenciesKHz = p.availableFrequenciesKHz,
                )
            },
            gpuDevfreqs = gpuNodes.map { g ->
                WebGpuDevfreqDto(
                    name = g.nodeName,
                    curFreq = g.currentFreqText,
                    rangeFreq = g.rangeText,
                    governor = g.governor,
                    minHz = g.minHz ?: 0L,
                    maxHz = g.maxHz ?: 0L,
                    availableGovernors = g.availableGovernors,
                    availableFrequenciesHz = g.availableFrequenciesHz,
                )
            },
        )
    }

    private fun buildAudio(ctx: Context): WebAudioResponse {
        val cards = HardwareRepository.readAlsaCards()
        val outputDevices = HardwareRepository.readAudioOutputDevices(ctx)
        val audioProps = HardwareRepository.readAudioProperties()
        val customPaths = ToolHubPreferences(ctx).getCustomSysfsPaths()
        val sysfsNodes = HardwareRepository.discoverBoardSysfsNodes(customPaths)
        val tone = AudioToneGenerator.state.value

        return WebAudioResponse(
            cards = cards.map { c ->
                WebAlsaCardDto(
                    index = c.index,
                    id = c.id,
                    name = c.name,
                    description = c.description,
                    active = c.hasActiveStream,
                    streams = c.streams.map { s ->
                        WebAlsaStreamDto(
                            pcmId = s.pcmId,
                            streamName = s.streamName,
                            active = s.isActive,
                            statusText = s.statusText,
                            format = s.format ?: "-",
                            rate = s.rate ?: "-",
                            channels = s.channels ?: "-",
                        )
                    },
                )
            },
            outputDevices = outputDevices.map { d ->
                WebAudioDeviceDto(
                    id = d.id,
                    typeLabel = d.typeLabel,
                    productName = d.productName,
                    sampleRates = d.sampleRates,
                    encodings = d.encodings,
                )
            },
            audioProps = WebAudioPropsDto(
                usbAudioInfo = audioProps.usbAudioInfo,
                usbExclusive = audioProps.usbExclusive,
                audioDirectDevice = audioProps.audioDirectDevice,
                i2sMode = audioProps.i2sMode,
            ),
            sysfsNodes = sysfsNodes.map { n ->
                WebSysfsNodeDto(
                    path = n.path,
                    group = n.group,
                    name = n.name,
                    value = n.value ?: "-",
                    writable = n.isWritable,
                    isCustom = n.isCustom,
                )
            },
            tone = WebAudioToneDto(
                isPlaying = tone.isPlaying,
                waveform = tone.waveform.name,
                channelMode = tone.channelMode.name,
                sampleRateHz = tone.sampleRateHz,
                attenuationDb = tone.attenuationDb,
            ),
        )
    }

    private fun buildSysProps(): WebSysPropsResponse {
        val all = SystemPropertiesHelper.readAll(forceRefresh = true)
        return WebSysPropsResponse(
            props = all.entries.map { (k, v) ->
                WebSysPropItemDto(
                    key = k,
                    value = v,
                    writable = SystemPropertiesHelper.isWritableKey(k),
                )
            },
        )
    }

    private fun buildAot(ctx: Context): WebAotResponse {
        val batch = AotRepository.batchState.value
        val apps = AotRepository.loadInstalledAppsAot(ctx)
        return WebAotResponse(
            isRunning = batch.isRunning,
            currentIndex = batch.currentIndex,
            totalCount = batch.totalCount,
            statusMessage = batch.statusMessage,
            apps = apps.map { a ->
                WebAotAppDto(
                    packageName = a.packageName,
                    appLabel = a.appName,
                    isSystemApp = a.isSystemApp,
                    status = a.status,
                    reason = a.reason,
                    abi = a.abi,
                    odexSizeBytes = a.odexSizeBytes,
                    isSpeedCompiled = a.isSpeedCompiled,
                )
            },
        )
    }

    private fun buildReportsList(ctx: Context): List<WebReportItemDto> =
        ReportStorageManager.listAllReports(ctx).map { r ->
            WebReportItemDto(
                fileName = r.fileName,
                typeLabel = r.reportType.label,
                isZip = r.reportType == ReportType.BUGREPORT,
                isUsbDrive = r.isUsbDrive,
                storageLocationLabel = r.storageLocationLabel,
                formattedTime = r.formattedTime,
                sizeBytes = r.sizeBytes,
                isPass = r.isPass,
                summary = r.summaryText,
            )
        }

    private fun buildCrash(ctx: Context): WebCrashResponse {
        val summary = CrashDiagnosticsRepository.loadDiagnostics(ctx)
        return WebCrashResponse(
            bootReason = WebBootReasonDto(
                sysBootReason = summary.bootReason?.sysBootReason ?: "-",
                roBootReason = summary.bootReason?.roBootReason ?: "-",
                isAbnormal = summary.bootReason?.isAbnormalReboot == true,
                lastKernelExcerpt = summary.bootReason?.lastKernelExcerpt,
            ),
            tombstones = summary.tombstones.map { t ->
                WebTombstoneDto(
                    fileName = t.fileName,
                    timestampText = t.timestampText,
                    processName = t.processName,
                    pid = t.pid,
                    signalText = t.signalText,
                )
            },
            anrs = summary.anrs.map { a ->
                WebAnrDto(
                    tag = a.tag,
                    processName = a.processName,
                    timestampText = a.timestampText,
                    reason = a.reason,
                    excerpt = a.excerpt,
                )
            },
        )
    }

    private fun buildTextSnapshot(ctx: Context): String {
        val basic = DeviceInfoRepository.readBasicInfo()
        val runtime = DeviceInfoRepository.readRuntimeInfo(ctx)
        val otgNodes = OtgRepository.discoverOtgNodes()
        val usbProps = OtgRepository.getUsbSystemProperties()
        val zones = HardwareRepository.readThermalZones()
        val snapshotTitle = AppLanguageManager.getString(ctx, R.string.home_env_snapshot)
        val deviceLabel = AppLanguageManager.getString(ctx, R.string.device_model)
        val ramLabel = AppLanguageManager.getString(ctx, R.string.perf_ram_title)
        val storageLabel = AppLanguageManager.getString(ctx, R.string.device_storage)
        val writableLabel = AppLanguageManager.getString(ctx, R.string.otg_writable)
        val readOnlyLabel = AppLanguageManager.getString(ctx, R.string.otg_read_only)
        val thermalLabel = AppLanguageManager.getString(ctx, R.string.thermal_header_title)
        return buildString {
            appendLine("=== ToolHub $snapshotTitle ===")
            appendLine("$deviceLabel: ${basic.model} (${basic.brand}) · Android ${basic.androidVersion} (API ${basic.sdkInt})")
            appendLine("SoC: ${basic.socPlatform} · Build: ${basic.buildNumber}")
            appendLine("UID: ${basic.uidSummary}")
            appendLine("$ramLabel: ${runtime.ramSummary} · $storageLabel: ${runtime.storageSummary}")
            appendLine("ADB: enabled=${OtgRepository.isAdbEnabled(ctx)}, usbConfig=${usbProps.usbConfig}, tcpPort=${usbProps.adbTcpPort}")
            appendLine("OTG (${otgNodes.size}):")
            otgNodes.forEach { appendLine("  - ${it.path}: ${it.currentMode} (${if (it.isWritable) writableLabel else readOnlyLabel})") }
            appendLine("$thermalLabel (${zones.size}):")
            zones.take(6).forEach { appendLine("  - ${it.type}: ${it.formattedTemp}") }
        }
    }

    private fun resolveSafeTombstoneFile(name: String): File? {
        if (!name.startsWith("tombstone_") || name.contains("/") || name.contains("..")) return null
        val f = File("/data/tombstones", name)
        return if (f.exists() && f.isFile && f.canRead()) f else null
    }

    private fun parseQuery(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                result[k] = v
            }
        }
        return result
    }

    private fun readLineAscii(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) buf.write(b)
            b = input.read()
        }
        return buf.toString("ISO-8859-1")
    }

    private fun sendJson(out: OutputStream, json: String, isHead: Boolean) {
        sendTextResponse(out, 200, "OK", "application/json; charset=utf-8", json, isHead)
    }

    private fun sendTextResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String,
        isHead: Boolean,
        acceptsGzip: Boolean = false,
    ) {
        val rawBytes = body.toByteArray(StandardCharsets.UTF_8)
        val useGzip = acceptsGzip && rawBytes.size > 1024
        val payload = if (useGzip) {
            ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { it.write(rawBytes) }
                baos.toByteArray()
            }
        } else {
            rawBytes
        }
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            if (useGzip) append("Content-Encoding: gzip\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        out.write(header)
        if (!isHead) out.write(payload)
        out.flush()
    }

    private fun sendFileDownload(out: OutputStream, file: File, isHead: Boolean) {
        val isZip = file.name.endsWith(".zip", ignoreCase = true)
        val contentType = if (isZip) "application/zip" else "text/plain; charset=utf-8"
        val len = file.length()
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Disposition: attachment; filename=\"${file.name}\"\r\n")
            append("Content-Length: $len\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        out.write(header)
        if (!isHead) {
            file.inputStream().use { input ->
                input.copyTo(out, bufferSize = 32 * 1024)
            }
        }
        out.flush()
    }

    private fun sendRedirect(out: OutputStream, location: String) {
        val header = buildString {
            append("HTTP/1.1 302 Found\r\n")
            append("Location: $location\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        out.write(header)
        out.flush()
    }
}
