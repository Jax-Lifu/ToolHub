package com.qytech.toolhub.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import kotlin.math.abs

enum class StressTestType(val label: String) {
    SAT("Google StressAppTest (原生 DDR/CPU 严苛压测)"),
    CPU("CPU 浮点与全核计算 (Linpack)"),
    MEMORY("内存读写与数据校验 (RAM)"),
    COMBINED("CPU + 内存混合全负荷 (Linpack+RAM)"),
}

data class AgingReport(
    val testId: String,
    val timestampMillis: Long,
    val deviceModel: String,
    val androidVersion: String,
    val socPlatform: String,
    val buildDisplay: String,
    val testType: StressTestType,
    val targetThreads: Int,
    val allocatedRamMB: Int,
    val plannedDurationSec: Int,
    val elapsedSeconds: Int,
    val startTempCelsius: Float?,
    val peakTempCelsius: Float?,
    val endTempCelsius: Float?,
    val overheatThresholdCelsius: Float,
    val overheatAborted: Boolean,
    val hardwareErrors: Long,
    val satThroughputMBs: Double,
    val cpuMflops: Double,
    val cpuIterations: Long,
    val memLoops: Long,
    val isPass: Boolean,
    val logSummary: List<String>,
)

data class StressTestState(
    val isRunning: Boolean = false,
    val type: StressTestType = StressTestType.SAT,
    val targetThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    val allocatedRamMB: Int = 256,
    val durationSeconds: Int = 60, // 0 = 持续
    val elapsedSeconds: Int = 0,
    val remainingSeconds: Int = 60,
    val cpuMflops: Double = 0.0,
    val cpuIterations: Long = 0L,
    val memThroughputMBs: Double = 0.0,
    val memErrors: Long = 0L,
    val memLoops: Long = 0L,
    val currentTempCelsius: Float? = null,
    val peakTempCelsius: Float? = null,
    val overheatAborted: Boolean = false,
    val statusMessage: String = "Ready",
    val recentLogs: List<String> = emptyList(),
    val lastAgingReport: AgingReport? = null,
)

/**
 * 嵌入式硬件压力测试引擎：
 * 1. 拥有独立的长驻协程作用域 (repositoryScope)，后台压测脱离界面生命周期，弹窗关闭后依然持续计时并刷新。
 * 2. Google stressapptest 原生编译二进制（内置 arm64-v8a 和 armeabi-v7a，通过 PackageManager 自动解压执行）。
 * 3. CPU 压测算法参考 RKDeviceTest 中的 LinpackLoop（标准高斯消元与 LU 分解双精度浮点运算，重度施压 CPU ALU/FPU/缓存）。
 * 4. 内存压测算法执行块分配与 Pattern (0x55, 0xAA) 读写校验，测试 DDR 总线吞吐与抗干扰稳定性。
 * 5. 具备高温过热自动熔断安全保护机制（>95°C 自动终止并告警），防止高温烧损机台。
 */
object StressTestRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(StressTestState())
    val state: StateFlow<StressTestState> = _state.asStateFlow()

    private var testJob: Job? = null
    private val isRunningFlag = AtomicBoolean(false)

    @Volatile
    private var lastAppContext: Context? = null
    private var nativeProcess: Process? = null

    /**
     * 定位应用原生库目录下的 stressapptest ELF 可执行文件
     */
    fun resolveNativeSatBinary(context: Context): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeDir, "libstressapptest.so")
        if (binary.exists()) {
            if (!binary.canExecute()) {
                binary.setExecutable(true, false)
            }
            return binary
        }
        return null
    }

    fun startStressTest(
        context: Context,
        type: StressTestType,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        ramMB: Int = 256,
        durationSeconds: Int = 60,
        overheatThresholdCelsius: Float = 95.0f,
    ) {
        if (isRunningFlag.getAndSet(true)) {
            return
        }
        lastAppContext = context.applicationContext
        com.qytech.toolhub.service.StressKeepAliveService.start(
            context,
            AppLanguageManager.getString(
                context,
                com.qytech.toolhub.R.string.stress_status_running
            ),
        )

        testJob?.cancel()
        testJob = repositoryScope.launch {
            val startTime = System.currentTimeMillis()
            val startTemp = HardwareRepository.readThermalZones()
                .firstOrNull { it.celsiusApprox != null }?.celsiusApprox?.toFloat()
            val totalIterations = AtomicLong(0L)
            val latestMflops = AtomicLong(0L) // Double bits
            val totalMemLoops = AtomicLong(0L)
            val totalMemBytes = AtomicLong(0L)
            val totalMemErrors = AtomicLong(0L)
            val peakTemp = AtomicLong(startTemp?.toRawBits()?.toLong() ?: 0L) // Float bits

            _state.update {
                it.copy(
                    isRunning = true,
                    type = type,
                    targetThreads = threads,
                    allocatedRamMB = ramMB,
                    durationSeconds = durationSeconds,
                    elapsedSeconds = 0,
                    remainingSeconds = if (durationSeconds > 0) durationSeconds else 0,
                    cpuMflops = 0.0,
                    cpuIterations = 0L,
                    memThroughputMBs = 0.0,
                    memErrors = 0L,
                    memLoops = 0L,
                    overheatAborted = false,
                    statusMessage = AppLanguageManager.getString(
                        context,
                        com.qytech.toolhub.R.string.stress_status_running
                    ),
                    recentLogs = emptyList(),
                )
            }

            try {
                coroutineScope {
                    // 1. 实际计算工作协程
                    val workerJob = launch(Dispatchers.IO) {
                        if (type == StressTestType.SAT || type == StressTestType.COMBINED) {
                            val nativeBin = resolveNativeSatBinary(context)
                            if (nativeBin != null && nativeBin.canExecute()) {
                                runNativeStressAppTest(
                                    bin = nativeBin,
                                    ramMB = ramMB,
                                    threads = threads,
                                    durationSeconds = durationSeconds,
                                )
                            } else {
                                _state.update {
                                    it.copy(
                                        statusMessage = AppLanguageManager.getString(
                                            context,
                                            com.qytech.toolhub.R.string.stress_status_fallback
                                        )
                                    )
                                }
                                runInProcessStressTest(
                                    type = StressTestType.COMBINED,
                                    threads = threads,
                                    ramMB = ramMB,
                                    totalIterations = totalIterations,
                                    latestMflops = latestMflops,
                                    totalMemLoops = totalMemLoops,
                                    totalMemBytes = totalMemBytes,
                                    totalMemErrors = totalMemErrors,
                                )
                            }
                        } else {
                            runInProcessStressTest(
                                type = type,
                                threads = threads,
                                ramMB = ramMB,
                                totalIterations = totalIterations,
                                latestMflops = latestMflops,
                                totalMemLoops = totalMemLoops,
                                totalMemBytes = totalMemBytes,
                                totalMemErrors = totalMemErrors,
                            )
                        }
                    }

                    // 2. 状态汇报与安全看门狗（每秒一次，持续更新 elapsedSeconds，即使在后台也能持续计时）
                    val monitorJob = launch(Dispatchers.IO) {
                        while (isActive && isRunningFlag.get()) {
                            delay(1000L)
                            val elapsed = ((System.currentTimeMillis() - startTime) / 1000L).toInt()
                            val remaining =
                                if (durationSeconds > 0) (durationSeconds - elapsed).coerceAtLeast(0) else 0

                            val curTemp = TelemetryRepository.readThermalSample().socCelsius
                            if (curTemp != null) {
                                val currentPeak = Float.fromBits(peakTemp.get().toInt())
                                if (curTemp > currentPeak) {
                                    peakTemp.set(curTemp.toRawBits().toLong())
                                }
                                // 高温安全保护
                                if (curTemp >= overheatThresholdCelsius) {
                                    _state.update {
                                        it.copy(
                                            overheatAborted = true,
                                            statusMessage = AppLanguageManager.getString(
                                                context,
                                                com.qytech.toolhub.R.string.stress_status_overheat_fmt,
                                                curTemp,
                                                overheatThresholdCelsius
                                            )
                                        )
                                    }
                                    isRunningFlag.set(false)
                                    nativeProcess?.destroyForcibly()
                                    workerJob.cancel()
                                    break
                                }
                            }

                            val throughput = if (elapsed > 0 && type != StressTestType.SAT) {
                                (totalMemBytes.get() / (1024.0 * 1024.0)) / elapsed
                            } else _state.value.memThroughputMBs

                            _state.update {
                                it.copy(
                                    elapsedSeconds = elapsed,
                                    remainingSeconds = remaining,
                                    cpuMflops = if (type == StressTestType.SAT) it.cpuMflops else Double.fromBits(
                                        latestMflops.get()
                                    ),
                                    cpuIterations = if (type == StressTestType.SAT) it.cpuIterations else totalIterations.get(),
                                    memThroughputMBs = throughput,
                                    memErrors = if (type == StressTestType.SAT) it.memErrors else totalMemErrors.get(),
                                    memLoops = if (type == StressTestType.SAT) it.memLoops else totalMemLoops.get(),
                                    currentTempCelsius = curTemp,
                                    peakTempCelsius = Float.fromBits(peakTemp.get().toInt())
                                        .takeIf { p -> p > 0f },
                                )
                            }

                            if (durationSeconds > 0 && elapsed >= durationSeconds) {
                                isRunningFlag.set(false)
                                nativeProcess?.destroyForcibly()
                                workerJob.cancel()
                                break
                            }
                        }
                    }

                    // 等待工作完成
                    workerJob.join()
                    isRunningFlag.set(false)
                    monitorJob.cancel()
                }
            } finally {
                isRunningFlag.set(false)
                nativeProcess?.destroyForcibly()
                nativeProcess = null
                val finalElapsed = ((System.currentTimeMillis() - startTime) / 1000L).toInt()
                val finalErrors =
                    if (type == StressTestType.SAT || type == StressTestType.COMBINED) _state.value.memErrors else totalMemErrors.get()
                val finalOverheat = _state.value.overheatAborted
                val isPass = (finalErrors == 0L) && !finalOverheat
                val endTemp = _state.value.currentTempCelsius

                val report = AgingReport(
                    testId = "SAT_${System.currentTimeMillis()}",
                    timestampMillis = System.currentTimeMillis(),
                    deviceModel = Build.MODEL,
                    androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    socPlatform = SystemPropertiesHelper.get("ro.board.platform", Build.HARDWARE),
                    buildDisplay = Build.DISPLAY,
                    testType = type,
                    targetThreads = threads,
                    allocatedRamMB = ramMB,
                    plannedDurationSec = durationSeconds,
                    elapsedSeconds = finalElapsed,
                    startTempCelsius = startTemp,
                    peakTempCelsius = Float.fromBits(peakTemp.get().toInt()).takeIf { it > 0f }
                        ?: startTemp,
                    endTempCelsius = endTemp,
                    overheatThresholdCelsius = overheatThresholdCelsius,
                    overheatAborted = finalOverheat,
                    hardwareErrors = finalErrors,
                    satThroughputMBs = _state.value.memThroughputMBs,
                    cpuMflops = if (type == StressTestType.SAT) _state.value.cpuMflops else Double.fromBits(
                        latestMflops.get()
                    ),
                    cpuIterations = if (type == StressTestType.SAT) _state.value.cpuIterations else totalIterations.get(),
                    memLoops = if (type == StressTestType.SAT) _state.value.memLoops else totalMemLoops.get(),
                    isPass = isPass,
                    logSummary = _state.value.recentLogs.takeLast(10),
                )

                _state.update {
                    it.copy(
                        isRunning = false,
                        elapsedSeconds = finalElapsed,
                        lastAgingReport = report,
                        statusMessage = when {
                            it.overheatAborted -> it.statusMessage
                            it.memErrors > 0 -> AppLanguageManager.getString(
                                context,
                                com.qytech.toolhub.R.string.stress_status_done_err_fmt,
                                it.memErrors
                            )

                            else -> AppLanguageManager.getString(
                                context,
                                com.qytech.toolhub.R.string.stress_status_done_ok_fmt,
                                finalElapsed
                            )
                        },
                    )
                }
                com.qytech.toolhub.service.StressKeepAliveService.stopIfIdle(context)
            }
        }
    }

    private suspend fun runNativeStressAppTest(
        bin: File,
        ramMB: Int,
        threads: Int,
        durationSeconds: Int,
    ) = withContext(Dispatchers.IO) {
        val satDuration = if (durationSeconds > 0) durationSeconds else 86400
        val cmd = listOf(
            bin.absolutePath,
            "-M", ramMB.toString(),
            "-s", satDuration.toString(),
            "-m", threads.toString(),
            "-C", threads.toString(),
        )

        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            nativeProcess = proc

            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val tpPattern = Pattern.compile("([0-9.]+)\\s*MB/s")
            val errPattern = Pattern.compile("(\\d+)\\s*(?:hardware\\s*incidents|errors)")

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (!isRunningFlag.get()) break

                var detectedTp: Double? = null
                val tpMatcher = tpPattern.matcher(currentLine)
                if (tpMatcher.find()) {
                    detectedTp = tpMatcher.group(1)?.toDoubleOrNull()
                }

                var detectedErr: Long? = null
                val errMatcher = errPattern.matcher(currentLine)
                if (errMatcher.find()) {
                    detectedErr = errMatcher.group(1)?.toLongOrNull()
                }

                val isPass = currentLine.contains("Status: PASS")
                val isFail = currentLine.contains("Status: FAIL")

                _state.update {
                    val logs = (it.recentLogs + currentLine).takeLast(10)
                    it.copy(
                        memThroughputMBs = detectedTp ?: it.memThroughputMBs,
                        memErrors = if (detectedErr != null && detectedErr > 0L) detectedErr else it.memErrors,
                        statusMessage = when {
                            isPass -> lastAppContext?.let { ctx ->
                                AppLanguageManager.getString(
                                    ctx,
                                    com.qytech.toolhub.R.string.stress_status_sat_pass
                                )
                            } ?: "SAT Passed (PASS)"

                            isFail -> lastAppContext?.let { ctx ->
                                AppLanguageManager.getString(
                                    ctx,
                                    com.qytech.toolhub.R.string.stress_status_sat_fail
                                )
                            } ?: "SAT Failed (FAIL)"

                            currentLine.contains("Starting SAT") -> lastAppContext?.let { ctx ->
                                AppLanguageManager.getString(
                                    ctx,
                                    com.qytech.toolhub.R.string.stress_status_sat_start
                                )
                            } ?: "SAT started..."

                            else -> it.statusMessage
                        },
                        recentLogs = logs,
                    )
                }
            }
            proc.waitFor()
        } catch (e: Exception) {
            _state.update {
                it.copy(statusMessage = lastAppContext?.let { ctx ->
                    AppLanguageManager.getString(
                        ctx,
                        com.qytech.toolhub.R.string.stress_status_sat_err_fmt,
                        e.message ?: ""
                    )
                } ?: "SAT error: ${e.message}")
            }
        } finally {
            nativeProcess?.destroyForcibly()
            nativeProcess = null
        }
    }

    private suspend fun runInProcessStressTest(
        type: StressTestType,
        threads: Int,
        ramMB: Int,
        totalIterations: AtomicLong,
        latestMflops: AtomicLong,
        totalMemLoops: AtomicLong,
        totalMemBytes: AtomicLong,
        totalMemErrors: AtomicLong,
    ) = withContext(Dispatchers.Default) {
        val workerJobs = mutableListOf<Job>()

        // 1. CPU Linpack 压测线程
        if (type == StressTestType.CPU || type == StressTestType.COMBINED) {
            for (workerId in 0 until threads) {
                workerJobs += launch(Dispatchers.Default) {
                    val linpack = LinpackBenchmark()
                    while (isActive && isRunningFlag.get()) {
                        val mflops = linpack.runIteration()
                        totalIterations.incrementAndGet()
                        latestMflops.set(mflops.toRawBits())
                    }
                }
            }
        }

        // 2. 内存读写校验压测线程
        if (type == StressTestType.MEMORY || type == StressTestType.COMBINED) {
            workerJobs += launch(Dispatchers.Default) {
                val blockSize = 16 * 1024 * 1024 // 16 MB chunks
                val numBlocks = (ramMB * 1024 * 1024 / blockSize).coerceIn(1, 128)
                val blocks = mutableListOf<ByteArray>()

                try {
                    for (i in 0 until numBlocks) {
                        blocks.add(ByteArray(blockSize))
                    }
                } catch (oom: OutOfMemoryError) {
                    _state.update {
                        it.copy(statusMessage = lastAppContext?.let { ctx ->
                            AppLanguageManager.getString(
                                ctx,
                                com.qytech.toolhub.R.string.stress_status_mem_cap_fmt,
                                blocks.size * 16
                            )
                        } ?: "Memory capped at ${blocks.size * 16} MB")
                    }
                }

                val patternA = 0x55.toByte()
                val patternB = 0xAA.toByte()

                while (isActive && isRunningFlag.get() && blocks.isNotEmpty()) {
                    for (block in blocks) {
                        if (!isRunningFlag.get()) break
                        // Write 0x55
                        block.fill(patternA)
                        totalMemBytes.addAndGet(block.size.toLong())

                        // Verify 0x55
                        for (byte in block) {
                            if (byte != patternA) totalMemErrors.incrementAndGet()
                        }
                        totalMemBytes.addAndGet(block.size.toLong())

                        // Write 0xAA
                        block.fill(patternB)
                        totalMemBytes.addAndGet(block.size.toLong())

                        // Verify 0xAA
                        for (byte in block) {
                            if (byte != patternB) totalMemErrors.incrementAndGet()
                        }
                        totalMemBytes.addAndGet(block.size.toLong())
                    }
                    totalMemLoops.incrementAndGet()
                }
                blocks.clear()
            }
        }

        while (isActive && isRunningFlag.get()) {
            delay(500L)
        }
        workerJobs.forEach { it.cancel() }
    }

    fun stopStressTest() {
        isRunningFlag.set(false)
        nativeProcess?.destroyForcibly()
        testJob?.cancel()
        _state.update {
            it.copy(
                isRunning = false,
                statusMessage = lastAppContext?.let { ctx ->
                    AppLanguageManager.getString(
                        ctx,
                        com.qytech.toolhub.R.string.stress_status_stopped
                    )
                } ?: "Stress test stopped")
        }
    }

    fun formatAgingReportMarkdown(report: AgingReport): String {
        val dateStr = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(report.timestampMillis))
        return buildString {
            appendLine("=====================================================")
            appendLine("       ToolHub 硬件稳定性与老化测试报告 (Aging Report)")
            appendLine("=====================================================")
            appendLine("测试结论: ${if (report.isPass) "【 PASS - 测试合格 】" else "【 FAIL - 存在异常 】"}")
            appendLine("测试时间: $dateStr")
            appendLine("设备型号: ${report.deviceModel}")
            appendLine("系统版本: ${report.androidVersion} · 固件: ${report.buildDisplay}")
            appendLine("芯片平台: ${report.socPlatform} (${Build.SUPPORTED_ABIS.firstOrNull() ?: "-"})")
            appendLine()
            appendLine("【一、测试配置】")
            appendLine("- 压测引擎: ${report.testType.label}")
            appendLine("- 并发核心: ${report.targetThreads} 核心")
            appendLine("- 内存容量: ${report.allocatedRamMB} MB")
            appendLine("- 计划时长: ${if (report.plannedDurationSec > 0) "${report.plannedDurationSec} 秒" else "持续压测"}")
            appendLine("- 实际运行: ${report.elapsedSeconds} 秒 (${report.elapsedSeconds / 60} 分 ${report.elapsedSeconds % 60} 秒)")
            appendLine()
            appendLine("【二、质量与稳定性判定】")
            appendLine("- DDR / 硬件错误: ${report.hardwareErrors} 次 ${if (report.hardwareErrors == 0L) "(合格)" else "(严重错误 - 内存位翻转)"}")
            appendLine("- 高温熔断保护: ${if (report.overheatAborted) "已触发 (安全终止)" else "正常 (未触发)"} (阈值: ${report.overheatThresholdCelsius}°C)")
            appendLine("- 最终判定: ${if (report.isPass) "PASS (出厂/研发标准通过)" else "FAIL (未达标)"}")
            appendLine()
            appendLine("【三、温升与散热监控】")
            appendLine(
                "- 初始温度: ${
                    report.startTempCelsius?.let {
                        String.format(
                            Locale.US,
                            "%.1f °C",
                            it
                        )
                    } ?: "-"
                }")
            appendLine(
                "- 运行峰值: ${
                    report.peakTempCelsius?.let {
                        String.format(
                            Locale.US,
                            "%.1f °C",
                            it
                        )
                    } ?: "-"
                }")
            appendLine(
                "- 结束温度: ${
                    report.endTempCelsius?.let {
                        String.format(
                            Locale.US,
                            "%.1f °C",
                            it
                        )
                    } ?: "-"
                }")
            if (report.startTempCelsius != null && report.peakTempCelsius != null) {
                val delta = report.peakTempCelsius - report.startTempCelsius
                appendLine("- 温升幅度: ${String.format(Locale.US, "+%.1f °C", delta)}")
            }
            appendLine()
            appendLine("【四、性能与吞吐指标】")
            if (report.testType == StressTestType.SAT || report.testType == StressTestType.COMBINED) {
                appendLine(
                    "- SAT 内存吞吐: ${
                        String.format(
                            Locale.US,
                            "%.1f MB/s",
                            report.satThroughputMBs
                        )
                    }"
                )
            }
            if (report.cpuIterations > 0L) {
                appendLine(
                    "- CPU 浮点算力: ${
                        String.format(
                            Locale.US,
                            "%.1f MFLOPS",
                            report.cpuMflops
                        )
                    }"
                )
                appendLine("- CPU 计算完成: ${report.cpuIterations} 轮 Linpack 计算")
            }
            if (report.memLoops > 0L) {
                appendLine("- 内存校验轮次: ${report.memLoops} 轮")
            }
            appendLine()
            if (report.logSummary.isNotEmpty()) {
                appendLine("【五、终端运行日志摘要】")
                report.logSummary.forEach { appendLine("  $it") }
            }
            appendLine("=====================================================")
        }
    }

    fun exportAgingReportToFile(context: Context, report: AgingReport): SavedReportResult {
        val dateStr =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(report.timestampMillis))
        val fileName = "AgingReport_${report.deviceModel}_$dateStr.txt"
        val content = formatAgingReportMarkdown(report)
        return ReportStorageManager.saveReport(context, fileName, content)
    }
}

/**
 * Linpack 100x100 双精度浮点密集消元计算
 * 移植自 Rockchip RKDeviceTest: com.rockchip.devicetest.aging.cpu.LinpackLoop
 */
class LinpackBenchmark {
    private val n = 100
    private val lda = 201
    private val ldaa = 200
    private val ops = (2.0 * (n * n * n)) / 3.0 + 2.0 * (n * n)

    private val a = Array(lda) { DoubleArray(ldaa) }
    private val b = DoubleArray(n)
    private val ipvt = IntArray(n)

    fun runIteration(): Double {
        val startNanos = System.nanoTime()
        matgen(a, lda, n, b)

        for (i in 0 until 5) {
            dgefa(a, lda, n, ipvt)
            dgesl(a, lda, n, ipvt, b, 0)
        }

        val totalSec = ((System.nanoTime() - startNanos) / 1_000_000_000.0) / 5.0
        return if (totalSec > 0.0) {
            ops / (1.0e6 * totalSec)
        } else 0.0
    }

    private fun matgen(a: Array<DoubleArray>, lda: Int, n: Int, b: DoubleArray): Double {
        var norma = 0.0
        var init = 1325
        for (i in 0 until n) {
            for (j in 0 until n) {
                init = 3125 * init % 65536
                a[j][i] = (init - 32768.0) / 16384.0
                norma = if (a[j][i] > norma) a[j][i] else norma
            }
        }
        for (i in 0 until n) {
            b[i] = 0.0
        }
        for (j in 0 until n) {
            for (i in 0 until n) {
                b[i] += a[j][i]
            }
        }
        return norma
    }

    private fun dgefa(a: Array<DoubleArray>, lda: Int, n: Int, ipvt: IntArray): Int {
        var info = 0
        val nm1 = n - 1
        if (nm1 >= 0) {
            for (k in 0 until nm1) {
                val kp1 = k + 1
                val l = idamax(n - k, a[k], k, 1) + k
                ipvt[k] = l

                if (a[k][l] != 0.0) {
                    if (l != k) {
                        val t = a[k][l]
                        a[k][l] = a[k][k]
                        a[k][k] = t
                    }
                    val t = -1.0 / a[k][k]
                    dscal(n - (k + 1), t, a[k], k + 1, 1)

                    for (j in kp1 until n) {
                        var temp = a[j][l]
                        if (l != k) {
                            a[j][l] = a[j][k]
                            a[j][k] = temp
                        }
                        daxpy(n - (k + 1), temp, a[k], k + 1, 1, a[j], k + 1, 1)
                    }
                } else {
                    info = k
                }
            }
        }
        ipvt[n - 1] = n - 1
        if (a[n - 1][n - 1] == 0.0) info = n - 1
        return info
    }

    private fun dgesl(
        a: Array<DoubleArray>,
        lda: Int,
        n: Int,
        ipvt: IntArray,
        b: DoubleArray,
        job: Int
    ) {
        val nm1 = n - 1
        if (job == 0) {
            if (nm1 >= 1) {
                for (k in 0 until nm1) {
                    val l = ipvt[k]
                    var t = b[l]
                    if (l != k) {
                        b[l] = b[k]
                        b[k] = t
                    }
                    daxpy(n - (k + 1), t, a[k], k + 1, 1, b, k + 1, 1)
                }
            }
            for (kb in 0 until n) {
                val k = n - (kb + 1)
                b[k] = b[k] / a[k][k]
                val t = -b[k]
                daxpy(k, t, a[k], 0, 1, b, 0, 1)
            }
        }
    }

    private fun daxpy(
        n: Int,
        da: Double,
        dx: DoubleArray,
        dxOff: Int,
        incx: Int,
        dy: DoubleArray,
        dyOff: Int,
        incy: Int
    ) {
        if (n <= 0 || da == 0.0) return
        var ix = dxOff
        var iy = dyOff
        for (i in 0 until n) {
            dy[iy] += da * dx[ix]
            ix += incx
            iy += incy
        }
    }

    private fun dscal(n: Int, da: Double, dx: DoubleArray, dxOff: Int, incx: Int) {
        if (n <= 0) return
        var ix = dxOff
        for (i in 0 until n) {
            dx[ix] *= da
            ix += incx
        }
    }

    private fun idamax(n: Int, dx: DoubleArray, dxOff: Int, incx: Int): Int {
        if (n <= 1) return 0
        var itemp = 0
        var dmax = abs(dx[dxOff])
        var ix = dxOff + incx
        for (i in 1 until n) {
            val v = abs(dx[ix])
            if (v > dmax) {
                itemp = i
                dmax = v
            }
            ix += incx
        }
        return itemp
    }
}
