package com.qytech.toolhub.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.annotation.StringRes
import com.qytech.toolhub.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ReportType(@param:StringRes val labelRes: Int, val label: String) {
    AGING(R.string.report_type_aging, "老化稳定性测试报告"),
    CRASH_DUMP(R.string.report_type_crash, "系统异常与崩溃回溯"),
    BUGREPORT(R.string.report_type_bugreport, "系统 Bugreport ZIP"),
    TOMBSTONE(R.string.report_type_tombstone, "Native 崩溃栈 (Tombstone)"),
    UNKNOWN(R.string.report_type_unknown, "未知报告"),
}

data class StorageTarget(
    val rootDir: File,
    val reportsDir: File,
    val isUsbDrive: Boolean,
    val volumeLabel: String,
    val totalBytes: Long,
    val freeBytes: Long,
)

data class SavedReportResult(
    val primaryFile: File,
    val isSavedToUsb: Boolean,
    val usbVolumeLabel: String?,
    val secondaryBackupFile: File?,
) {
    val displayMessage: String
        get() = if (isSavedToUsb) {
            "✓ Saved to USB: ${primaryFile.absolutePath}"
        } else {
            "✓ Saved to internal storage: ${primaryFile.absolutePath}"
        }

    fun displayMessage(context: Context): String = if (isSavedToUsb) {
        AppLanguageManager.getString(
            context,
            R.string.report_saved_usb_fmt,
            primaryFile.absolutePath
        )
    } else {
        AppLanguageManager.getString(
            context,
            R.string.report_saved_local_fmt,
            primaryFile.absolutePath
        )
    }
}

data class ReportFileInfo(
    val file: File,
    val fileName: String,
    val timestampMillis: Long,
    val formattedTime: String,
    val sizeBytes: Long,
    val isUsbDrive: Boolean,
    val storageLocationLabel: String,
    val reportType: ReportType,
    val isPass: Boolean?,
    val summaryText: String,
)

object ReportStorageManager {

    private const val FOLDER_NAME = "ToolHub_Reports"

    /**
     * 扫描系统挂载的外部 U 盘 / TF 卡
     */
    fun findUsbStorageVolumes(context: Context): List<StorageTarget> {
        val targets = mutableListOf<StorageTarget>()
        val seenPaths = mutableSetOf<String>()

        // 1. StorageManager API
        runCatching {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.forEach { volume ->
                if (!volume.isPrimary && (volume.isRemovable || volume.getDescription(context)
                        .contains("USB", ignoreCase = true))
                ) {
                    val root = getVolumeDirectory(volume)
                    if (root != null && root.exists() && seenPaths.add(root.canonicalPath)) {
                        val stat = runCatching { StatFs(root.absolutePath) }.getOrNull()
                        val total = stat?.totalBytes ?: 0L
                        val free = stat?.availableBytes ?: 0L
                        val reportsDir = File(root, FOLDER_NAME)
                        targets.add(
                            StorageTarget(
                                rootDir = root,
                                reportsDir = reportsDir,
                                isUsbDrive = true,
                                volumeLabel = volume.getDescription(context).ifBlank { root.name },
                                totalBytes = total,
                                freeBytes = free,
                            )
                        )
                    }
                }
            }
        }

        // 2. 直接扫描 /storage 目录
        runCatching {
            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.canRead()) {
                val subDirs = storageDir.listFiles() ?: emptyArray()
                for (dir in subDirs) {
                    val name = dir.name
                    if (dir.isDirectory && name != "emulated" && name != "self" && !name.startsWith(
                            "knox"
                        ) && !name.startsWith(".")
                    ) {
                        val canonical = dir.canonicalPath
                        if (seenPaths.add(canonical)) {
                            val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
                            val total = stat?.totalBytes ?: 0L
                            val free = stat?.availableBytes ?: 0L
                            targets.add(
                                StorageTarget(
                                    rootDir = dir,
                                    reportsDir = File(dir, FOLDER_NAME),
                                    isUsbDrive = true,
                                    volumeLabel = AppLanguageManager.getString(
                                        context,
                                        R.string.storage_usb_label_fmt,
                                        name
                                    ),
                                    totalBytes = total,
                                    freeBytes = free,
                                )
                            )
                        }
                    }
                }
            }
        }

        // 3. 辅助扫描 /mnt/media_rw
        runCatching {
            val mediaRw = File("/mnt/media_rw")
            if (mediaRw.exists() && mediaRw.canRead()) {
                mediaRw.listFiles()?.forEach { dir ->
                    if (dir.isDirectory && !dir.name.startsWith(".")) {
                        val canonical = dir.canonicalPath
                        if (seenPaths.add(canonical)) {
                            val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
                            targets.add(
                                StorageTarget(
                                    rootDir = dir,
                                    reportsDir = File(dir, FOLDER_NAME),
                                    isUsbDrive = true,
                                    volumeLabel = AppLanguageManager.getString(
                                        context,
                                        R.string.storage_ext_label_fmt,
                                        dir.name
                                    ),
                                    totalBytes = stat?.totalBytes ?: 0L,
                                    freeBytes = stat?.availableBytes ?: 0L,
                                )
                            )
                        }
                    }
                }
            }
        }

        return targets
    }

    /**
     * 获取首选报告保存目录：
     * 若接入了可写 U 盘，优先返回 U 盘中的 ToolHub_Reports 目录；
     * 否则返回系统内部公共 Download 目录
     */
    fun getPreferredTarget(context: Context): StorageTarget {
        val usbVolumes = findUsbStorageVolumes(context)
        for (usb in usbVolumes) {
            val canWrite = runCatching {
                if (!usb.reportsDir.exists()) usb.reportsDir.mkdirs()
                val testFile = File(usb.reportsDir, ".write_test_${System.currentTimeMillis()}")
                testFile.writeText("ok")
                testFile.delete()
                true
            }.getOrDefault(false)

            if (canWrite) {
                return usb
            }
        }

        // 默认内部存储
        val downloadDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .takeIf { it.exists() || it.mkdirs() } ?: context.getExternalFilesDir(null)
            ?: context.filesDir
        val stat = runCatching { StatFs(downloadDir.absolutePath) }.getOrNull()
        return StorageTarget(
            rootDir = downloadDir,
            reportsDir = downloadDir,
            isUsbDrive = false,
            volumeLabel = AppLanguageManager.getString(context, R.string.storage_internal_label),
            totalBytes = stat?.totalBytes ?: 0L,
            freeBytes = stat?.availableBytes ?: 0L,
        )
    }

    /**
     * 保存报告内容到文件：
     * 若接入 U 盘，优先保存至 U 盘，并自动同步在内部 Download 目录创建本地副本；
     * 若未接入 U 盘，则保存至内部 Download 目录。
     */
    fun saveReport(
        context: Context,
        fileName: String,
        content: String,
    ): SavedReportResult {
        val target = getPreferredTarget(context)

        return if (target.isUsbDrive) {
            // 写入 U 盘
            target.reportsDir.mkdirs()
            val usbFile = File(target.reportsDir, fileName)
            usbFile.writeText(content)

            // 同步写入内部存储 Download 副本，便于无 U 盘时局域网 Web 服务和本机回溯
            val localDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .takeIf { it.exists() || it.mkdirs() } ?: context.getExternalFilesDir(null)
                ?: context.filesDir
            val backupFile = runCatching {
                val f = File(localDir, fileName)
                f.writeText(content)
                f
            }.getOrNull()

            SavedReportResult(
                primaryFile = usbFile,
                isSavedToUsb = true,
                usbVolumeLabel = target.volumeLabel,
                secondaryBackupFile = backupFile,
            )
        } else {
            // 写入内部存储
            val file = File(target.reportsDir, fileName)
            file.writeText(content)
            SavedReportResult(
                primaryFile = file,
                isSavedToUsb = false,
                usbVolumeLabel = null,
                secondaryBackupFile = null,
            )
        }
    }

    /**
     * 列出所有可供 Web 浏览和下载的历史报告文件（自动聚合 U 盘与本地目录，按时间倒序）
     */
    fun listAllReports(context: Context): List<ReportFileInfo> {
        val fileMap = mutableMapOf<String, ReportFileInfo>()

        fun scanDir(dir: File, isUsb: Boolean, label: String) {
            if (!dir.exists() || !dir.canRead()) return
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() > 0L && (f.name.endsWith(".txt") || f.name.endsWith(".log") || f.name.endsWith(
                        ".zip",
                        ignoreCase = true
                    ) || f.name.startsWith("tombstone_"))
                ) {
                    if (!fileMap.containsKey(f.name)) {
                        val info = parseReportFileInfo(context, f, isUsb, label)
                        fileMap[f.name] = info
                    }
                }
            }
        }

        // 1. 扫描 U 盘中的 ToolHub_Reports 目录
        findUsbStorageVolumes(context).forEach { usb ->
            scanDir(usb.reportsDir, isUsb = true, label = usb.volumeLabel)
            scanDir(
                File(usb.rootDir, "Download"),
                isUsb = true,
                label = "${usb.volumeLabel}/Download"
            )
        }

        // 2. 扫描内部存储 Download 目录
        val localDownload =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (localDownload.exists()) {
            val internalLabel = AppLanguageManager.getString(context, R.string.storage_internal)
            scanDir(localDownload, isUsb = false, label = internalLabel)
            val toolhubSub = File(localDownload, FOLDER_NAME)
            if (toolhubSub.exists()) {
                scanDir(toolhubSub, isUsb = false, label = internalLabel)
            }
        }

        // 3. 扫描系统内置 bugreports 目录（若当前 UID 有权限读取）
        scanDir(
            File("/bugreports"),
            isUsb = false,
            label = AppLanguageManager.getString(context, R.string.storage_system_bugreports)
        )
        scanDir(
            File("/data/user_de/0/com.android.shell/files/bugreports"),
            isUsb = false,
            label = AppLanguageManager.getString(context, R.string.storage_shell_bugreports)
        )

        return fileMap.values.sortedByDescending { it.timestampMillis }
    }

    /**
     * 读取并解析报告文件摘要
     */
    private fun parseReportFileInfo(
        context: Context,
        file: File,
        isUsb: Boolean,
        storageLabel: String
    ): ReportFileInfo {
        val name = file.name
        val type = when {
            name.endsWith(".zip", ignoreCase = true) -> ReportType.BUGREPORT
            name.startsWith("AgingReport_") -> ReportType.AGING
            name.startsWith("CrashDump_") -> ReportType.CRASH_DUMP
            name.startsWith("tombstone_") -> ReportType.TOMBSTONE
            else -> ReportType.UNKNOWN
        }

        var isPass: Boolean? = null
        var summary = ""

        if (type != ReportType.BUGREPORT) {
            runCatching {
                file.bufferedReader().use { reader ->
                    val lines = mutableListOf<String>()
                    var count = 0
                    while (count < 20) {
                        val line = reader.readLine() ?: break
                        lines.add(line)
                        count++
                    }

                    if (type == ReportType.AGING) {
                        val passLine = lines.firstOrNull {
                            it.contains("测试结论:") || it.contains("PASS") || it.contains("FAIL")
                        }
                        if (passLine != null) {
                            isPass = passLine.contains("PASS")
                        }
                        val durLine =
                            lines.firstOrNull { it.contains("实际运行:") || it.contains("运行:") }
                        val tempLine =
                            lines.firstOrNull { it.contains("运行峰值:") || it.contains("最高温升:") }
                        summary = listOfNotNull(
                            passLine?.trim(),
                            durLine?.trim(),
                            tempLine?.trim()
                        ).joinToString(" · ")
                    } else if (type == ReportType.CRASH_DUMP) {
                        val abnormalLine = lines.firstOrNull { it.contains("异常状态:") }
                        val statsLine = lines.firstOrNull { it.contains("统计数据:") }
                        summary = listOfNotNull(
                            abnormalLine?.trim(),
                            statsLine?.trim()
                        ).joinToString(" · ")
                    } else if (type == ReportType.TOMBSTONE) {
                        val procLine = lines.firstOrNull {
                            it.contains("Cmdline:") || (it.contains("pid:") && it.contains("name:"))
                        }
                        val sigLine = lines.firstOrNull { it.contains("signal ") }
                        summary =
                            listOfNotNull(procLine?.trim(), sigLine?.trim()).joinToString(" · ")
                    }
                }
            }
        }

        if (summary.isBlank()) {
            val typeName = AppLanguageManager.getString(context, type.labelRes)
            summary = "$typeName · ${formatBytes(file.length())}"
        }

        val formattedDate = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(file.lastModified()))

        return ReportFileInfo(
            file = file,
            fileName = file.name,
            timestampMillis = file.lastModified(),
            formattedTime = formattedDate,
            sizeBytes = file.length(),
            isUsbDrive = isUsb,
            storageLocationLabel = storageLabel,
            reportType = type,
            isPass = isPass,
            summaryText = summary,
        )
    }

    private fun getVolumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }
        return runCatching {
            val getPath = volume.javaClass.getMethod("getPath")
            File(getPath.invoke(volume) as String)
        }.getOrNull()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / (1024f * 1024f))
        bytes >= 1024 -> "%.1f KB".format(Locale.US, bytes / 1024f)
        else -> "$bytes B"
    }
}
