package com.qytech.toolhub.data

import android.content.Context
import android.os.Build
import android.os.DropBoxManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TombstoneEntry(
    val fileName: String,
    val filePath: String,
    val timestampText: String,
    val timestampMillis: Long,
    val processName: String,
    val pid: String,
    val tid: String,
    val signalText: String,
    val faultAddr: String?,
    val topStackFrame: String?,
    val fullExcerpt: String,
)

data class AnrEntry(
    val tag: String,
    val timestampText: String,
    val timestampMillis: Long,
    val processName: String,
    val reason: String,
    val excerpt: String,
)

data class BootReasonInfo(
    val sysBootReason: String,
    val roBootReason: String,
    val isAbnormalReboot: Boolean,
    val pstoreAvailable: Boolean,
    val lastKernelExcerpt: String?,
)

data class CrashDiagnosticsSummary(
    val tombstones: List<TombstoneEntry> = emptyList(),
    val anrs: List<AnrEntry> = emptyList(),
    val bootReason: BootReasonInfo? = null,
    val totalIssuesCount: Int = 0,
)

object CrashDiagnosticsRepository {

    fun loadDiagnostics(context: Context): CrashDiagnosticsSummary {
        val tombstones = readTombstones(maxCount = 20)
        val anrs = readDropBoxAnrs(context, maxCount = 15)
        val boot = readBootReason()
        val total = tombstones.size + anrs.size + (if (boot.isAbnormalReboot) 1 else 0)

        return CrashDiagnosticsSummary(
            tombstones = tombstones,
            anrs = anrs,
            bootReason = boot,
            totalIssuesCount = total,
        )
    }

    fun readTombstones(
        tombstoneDir: File = File("/data/tombstones"),
        maxCount: Int = 20,
    ): List<TombstoneEntry> = runCatching {
        if (!tombstoneDir.exists() || !tombstoneDir.canRead()) return@runCatching emptyList()

        val files = tombstoneDir.listFiles { f ->
            f.isFile && f.name.startsWith("tombstone_") && !f.name.endsWith(".pb")
        } ?: return@runCatching emptyList()

        files.sortedByDescending { it.lastModified() }
            .take(maxCount)
            .mapNotNull { parseTombstoneFile(it) }
    }.getOrDefault(emptyList())

    private fun parseTombstoneFile(file: File): TombstoneEntry? = runCatching {
        val lines = file.bufferedReader().use { reader ->
            val list = mutableListOf<String>()
            var count = 0
            while (count < 80) {
                val line = reader.readLine() ?: break
                list.add(line)
                count++
            }
            list
        }

        if (lines.isEmpty()) return@runCatching null

        var processName = "unknown"
        var timestampText = formatMillis(file.lastModified())
        var pid = "-"
        var tid = "-"
        var signalText = "Native Crash"
        var faultAddr: String? = null
        val stackFrames = mutableListOf<String>()
        var inBacktrace = false

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Timestamp:") -> {
                    timestampText = trimmed.substringAfter("Timestamp:").trim()
                }

                trimmed.startsWith("Cmdline:") -> {
                    processName = trimmed.substringAfter("Cmdline:").trim()
                }

                trimmed.startsWith("pid:") && trimmed.contains("name:") -> {
                    val pidPart = trimmed.substringAfter("pid:").substringBefore(",")
                    pid = pidPart.trim()
                    val tidPart = trimmed.substringAfter("tid:").substringBefore(",")
                    tid = tidPart.trim()
                    if (trimmed.contains(">>>") && trimmed.contains("<<<")) {
                        processName = trimmed.substringAfter(">>>").substringBefore("<<<").trim()
                    }
                }

                trimmed.startsWith("signal ") -> {
                    signalText = trimmed
                    if (trimmed.contains("fault addr")) {
                        faultAddr = trimmed.substringAfter("fault addr").trim()
                    }
                }

                trimmed.startsWith("backtrace:") -> {
                    inBacktrace = true
                }

                inBacktrace && trimmed.startsWith("#") -> {
                    if (stackFrames.size < 8) {
                        stackFrames.add(trimmed)
                    }
                }

                inBacktrace && (trimmed.startsWith("stack:") || trimmed.startsWith("memory near")) -> {
                    inBacktrace = false
                }
            }
        }

        val topFrame = stackFrames.firstOrNull()
        val fullExcerpt = lines.take(50).joinToString("\n")

        TombstoneEntry(
            fileName = file.name,
            filePath = file.absolutePath,
            timestampText = timestampText,
            timestampMillis = file.lastModified(),
            processName = processName,
            pid = pid,
            tid = tid,
            signalText = signalText,
            faultAddr = faultAddr,
            topStackFrame = topFrame,
            fullExcerpt = fullExcerpt,
        )
    }.getOrNull()

    fun readDropBoxAnrs(context: Context, maxCount: Int = 15): List<AnrEntry> = runCatching {
        val dropbox =
            context.getSystemService(DropBoxManager::class.java) ?: return@runCatching emptyList()
        val anrList = mutableListOf<AnrEntry>()
        val tags =
            listOf("system_app_anr", "data_app_anr", "system_server_anr", "system_server_crash")

        for (tag in tags) {
            var msec = 0L
            var entry = dropbox.getNextEntry(tag, msec)
            while (entry != null && anrList.size < maxCount) {
                val time = entry.timeMillis
                val text = runCatching { entry.getText(12 * 1024) }.getOrNull().orEmpty()
                entry.close()

                if (text.isNotBlank()) {
                    var proc = "unknown"
                    var reason = tag
                    for (line in text.lines().take(20)) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("Process:") || trimmed.startsWith("Package:")) {
                            proc = trimmed.substringAfter(":").trim()
                        } else if (trimmed.startsWith("Reason:") || trimmed.startsWith("Subject:")) {
                            reason = trimmed.substringAfter(":").trim()
                        }
                    }
                    anrList.add(
                        AnrEntry(
                            tag = tag,
                            timestampText = formatMillis(time),
                            timestampMillis = time,
                            processName = proc,
                            reason = reason,
                            excerpt = text.lines().take(35).joinToString("\n"),
                        )
                    )
                }
                msec = time
                entry = dropbox.getNextEntry(tag, msec)
            }
        }

        // 辅助读取 /data/anr/ 目录文件
        val anrDir = File("/data/anr")
        if (anrDir.exists() && anrDir.canRead()) {
            val files = anrDir.listFiles()
                ?.filter { it.isFile && (it.name.startsWith("anr_") || it.name == "traces.txt") }
            files?.sortedByDescending { it.lastModified() }?.take(5)?.forEach { f ->
                val lines =
                    runCatching { f.bufferedReader().use { it.readLines().take(25) } }.getOrNull()
                        ?: emptyList()
                var proc = f.name
                lines.firstOrNull { it.contains("Cmd line:") }?.let { line ->
                    proc = line.substringAfter("Cmd line:").trim()
                }
                anrList.add(
                    AnrEntry(
                        tag = "file_anr",
                        timestampText = formatMillis(f.lastModified()),
                        timestampMillis = f.lastModified(),
                        processName = proc,
                        reason = "ANR Trace (${f.name})",
                        excerpt = lines.joinToString("\n"),
                    )
                )
            }
        }

        anrList.sortedByDescending { it.timestampMillis }.take(maxCount)
    }.getOrDefault(emptyList())

    @Volatile
    private var cachedBootReason: BootReasonInfo? = null

    fun readBootReason(): BootReasonInfo {
        cachedBootReason?.let { return it }
        val sysReason = SystemPropertiesHelper.get("sys.boot.reason", "-")
        val roReason = SystemPropertiesHelper.get("ro.boot.bootreason", "-")

        val abnormalKeywords =
            listOf("panic", "watchdog", "kernel", "hw_reset", "oops", "wdog", "bl_error")
        val isAbnormal = abnormalKeywords.any {
            sysReason.contains(it, ignoreCase = true) || roReason.contains(it, ignoreCase = true)
        }

        // 读取 pstore ramoops 崩溃前内核日志
        val pstoreFile = listOf(
            File("/sys/fs/pstore/console-ramoops-0"),
            File("/sys/fs/pstore/console-ramoops"),
            File("/proc/last_kmsg"),
        ).firstOrNull { it.exists() && it.canRead() }

        var kernelExcerpt: String? = null
        if (pstoreFile != null) {
            kernelExcerpt = runCatching {
                val full = pstoreFile.readText()
                val lines = full.lines()
                lines.takeLast(40).joinToString("\n")
            }.getOrNull()
        }

        val info = BootReasonInfo(
            sysBootReason = sysReason,
            roBootReason = roReason,
            isAbnormalReboot = isAbnormal,
            pstoreAvailable = pstoreFile != null,
            lastKernelExcerpt = kernelExcerpt,
        )
        cachedBootReason = info
        return info
    }

    fun exportDiagnosticsReport(
        context: Context,
        summary: CrashDiagnosticsSummary
    ): SavedReportResult {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "CrashDump_${Build.MODEL}_$dateStr.txt"

        val sb = StringBuilder()
        sb.appendLine("=====================================================")
        sb.appendLine("       ToolHub 系统异常与崩溃回溯报告 (Crash Dump)")
        sb.appendLine("=====================================================")
        sb.appendLine(
            "导出时间: ${
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())
            }"
        )
        sb.appendLine("设备型号: ${Build.MODEL} (${Build.MANUFACTURER})")
        sb.appendLine("固件版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, Build ${Build.DISPLAY})")
        sb.appendLine(
            "SoC 平台: ${
                SystemPropertiesHelper.get(
                    "ro.board.platform",
                    Build.HARDWARE
                )
            }"
        )
        sb.appendLine("开机原因: sys.boot.reason=${summary.bootReason?.sysBootReason} · ro.boot.bootreason=${summary.bootReason?.roBootReason}")
        sb.appendLine("异常状态: ${if (summary.bootReason?.isAbnormalReboot == true) "⚠ 存在异常死机/看门狗重启记录" else "✓ 正常重启/开机"}")
        sb.appendLine("统计数据: Native 崩溃 (Tombstone): ${summary.tombstones.size} 条 · ANR 无响应: ${summary.anrs.size} 条")
        sb.appendLine()

        sb.appendLine("-----------------------------------------------------")
        sb.appendLine("【一、Native 崩溃记录 (Tombstones)】")
        sb.appendLine("-----------------------------------------------------")
        if (summary.tombstones.isEmpty()) {
            sb.appendLine("未检测到 /data/tombstones 崩溃记录。")
        } else {
            summary.tombstones.forEachIndexed { i, t ->
                sb.appendLine("[$i] ${t.fileName} · ${t.timestampText}")
                sb.appendLine("    进程: ${t.processName} (PID: ${t.pid}, TID: ${t.tid})")
                sb.appendLine("    信号: ${t.signalText}")
                if (!t.topStackFrame.isNullOrBlank()) {
                    sb.appendLine("    栈顶: ${t.topStackFrame}")
                }
                sb.appendLine("    --- 崩溃现场摘要 ---")
                sb.appendLine(t.fullExcerpt)
                sb.appendLine()
            }
        }

        sb.appendLine("-----------------------------------------------------")
        sb.appendLine("【二、应用无响应记录 (ANR & Crash Traces)】")
        sb.appendLine("-----------------------------------------------------")
        if (summary.anrs.isEmpty()) {
            sb.appendLine("未检测到系统 ANR 异常记录。")
        } else {
            summary.anrs.forEachIndexed { i, a ->
                sb.appendLine("[$i] [${a.tag}] 进程: ${a.processName} · 时间: ${a.timestampText}")
                sb.appendLine("    原因: ${a.reason}")
                sb.appendLine(a.excerpt)
                sb.appendLine()
            }
        }

        sb.appendLine("-----------------------------------------------------")
        sb.appendLine("【三、重启前内核日志 (Kernel Ramoops / Panic Log)】")
        sb.appendLine("-----------------------------------------------------")
        if (!summary.bootReason?.lastKernelExcerpt.isNullOrBlank()) {
            sb.appendLine(summary.bootReason.lastKernelExcerpt)
        } else {
            sb.appendLine("未检测到 /sys/fs/pstore/console-ramoops 日志缓存。")
        }
        sb.appendLine("=====================================================")

        return ReportStorageManager.saveReport(context, fileName, sb.toString())
    }

    private fun formatMillis(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
}
