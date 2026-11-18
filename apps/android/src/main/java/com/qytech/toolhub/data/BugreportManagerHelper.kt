package com.qytech.toolhub.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.BugreportManager
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import com.qytech.toolhub.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class BugreportUiState(
    val isGenerating: Boolean = false,
    val isIndeterminate: Boolean = false,
    val progressPercent: Float = 0f,
    val elapsedSeconds: Int = 0,
    val statusMessage: String = "Ready",
    val lastGeneratedFile: File? = null,
    val lastSavedToUsb: Boolean = false,
)

data class BugreportZipInfo(
    val file: File,
    val fileName: String,
    val sizeBytes: Long,
    val timestampMillis: Long,
    val formattedTime: String,
    val sourceLabel: String,
)

object BugreportManagerHelper {

    private val _state = MutableStateFlow(BugreportUiState())
    val state: StateFlow<BugreportUiState> = _state.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 扫描系统中可读的 Bugreport ZIP 文件（包含 U 盘、Download、/bugreports）
     */
    fun listBugreportZips(context: Context): List<BugreportZipInfo> {
        val result = mutableMapOf<String, BugreportZipInfo>()

        fun scan(dir: File, label: String) {
            if (!dir.exists() || !dir.canRead()) return
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(
                        ".zip",
                        ignoreCase = true
                    ) && f.canRead() && f.length() > 0L
                ) {
                    if (!result.containsKey(f.name)) {
                        val timeStr = SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date(f.lastModified()))
                        result[f.name] = BugreportZipInfo(
                            file = f,
                            fileName = f.name,
                            sizeBytes = f.length(),
                            timestampMillis = f.lastModified(),
                            formattedTime = timeStr,
                            sourceLabel = label,
                        )
                    }
                }
            }
        }

        ReportStorageManager.findUsbStorageVolumes(context).forEach { usb ->
            scan(
                usb.reportsDir,
                AppLanguageManager.getString(
                    context,
                    R.string.storage_usb_label_fmt,
                    usb.volumeLabel
                )
            )
        }

        val localDownload =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (localDownload.exists()) {
            scan(
                localDownload,
                AppLanguageManager.getString(context, R.string.storage_internal_label)
            )
        }

        scan(
            File("/bugreports"),
            AppLanguageManager.getString(context, R.string.storage_system_bugreports)
        )
        scan(
            File("/data/user_de/0/com.android.shell/files/bugreports"),
            AppLanguageManager.getString(context, R.string.storage_shell_bugreports)
        )

        return result.values.sortedByDescending { it.timestampMillis }
    }

    /**
     * 生成完整系统 Bugreport ZIP 压缩包：
     * 1. 优先通过系统 BugreportManager (@SystemApi 反射) 获取实时进度并写入目标文件；
     * 2. 若受限则回退至 `/system/bin/bugreportz -s` 流式导出。
     * 若接入 U 盘则优先写入 U 盘 ToolHub_Reports，并在内部 Download 同步保留副本。
     */
    fun startSystemBugreport(context: Context) {
        if (_state.value.isGenerating) return

        val target = ReportStorageManager.getPreferredTarget(context)
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Bugreport_${Build.MODEL}_$dateStr.zip"
        target.reportsDir.mkdirs()
        val outFile = File(target.reportsDir, fileName)

        _state.update {
            BugreportUiState(
                isGenerating = true,
                isIndeterminate = false,
                progressPercent = 0f,
                elapsedSeconds = 0,
                statusMessage = AppLanguageManager.getString(
                    context,
                    R.string.bugreport_collecting_pct_fmt,
                    0
                ),
            )
        }
        com.qytech.toolhub.service.StressKeepAliveService.start(
            context,
            AppLanguageManager.getString(
                context,
                R.string.bugreport_collecting_pct_fmt,
                0
            ),
        )
        startElapsedTicker(context)

        // 优先尝试 BugreportManager API
        val startedViaManager =
            tryStartViaBugreportManager(context, outFile, target.isUsbDrive, fileName)
        if (!startedViaManager) {
            // 回退至 /system/bin/bugreportz -s 流式生成
            executor.submit {
                runBugreportzCli(context, outFile, target.isUsbDrive, fileName)
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun tryStartViaBugreportManager(
        context: Context,
        outFile: File,
        isUsbDrive: Boolean,
        fileName: String,
    ): Boolean {
        val bm = context.getSystemService(BugreportManager::class.java) ?: return false
        val pfd = runCatching {
            ParcelFileDescriptor.open(
                outFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }.getOrNull() ?: return false

        return runCatching {
            val paramsClass = Class.forName("android.os.BugreportParams")
            val paramsObj = paramsClass.getConstructor(Int::class.javaPrimitiveType)
                .newInstance(1) // BUGREPORT_MODE_FULL = 1

            val callback = object : BugreportManager.BugreportCallback() {
                override fun onProgress(progress: Float) {
                    _state.update {
                        it.copy(
                            isGenerating = true,
                            progressPercent = progress.coerceIn(0f, 100f),
                            statusMessage = AppLanguageManager.getString(
                                context,
                                R.string.bugreport_collecting_pct_fmt,
                                progress.toInt()
                            ),
                        )
                    }
                }

                override fun onFinished() {
                    runCatching { pfd.close() }
                    finishSuccess(context, outFile, isUsbDrive, fileName)
                }

                override fun onError(errorCode: Int) {
                    runCatching { pfd.close() }
                    runCatching { if (outFile.exists() && outFile.length() == 0L) outFile.delete() }
                    // 若 BugreportManager 报错，自动尝试 bugreportz -s CLI 回退
                    executor.submit {
                        runBugreportzCli(context, outFile, isUsbDrive, fileName)
                    }
                }
            }

            val startMethod = bm.javaClass.getMethod(
                "startBugreport",
                ParcelFileDescriptor::class.java,
                ParcelFileDescriptor::class.java,
                paramsClass,
                Executor::class.java,
                BugreportManager.BugreportCallback::class.java,
            )
            startMethod.invoke(bm, pfd, null, paramsObj, executor, callback)
            true
        }.getOrElse {
            runCatching { pfd.close() }
            false
        }
    }

    private fun startElapsedTicker(context: Context) {
        Thread({
            val startMs = System.currentTimeMillis()
            while (_state.value.isGenerating) {
                Thread.sleep(1000L)
                if (!_state.value.isGenerating) break
                val sec = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
                _state.update { cur ->
                    if (!cur.isGenerating) cur else {
                        val msg = if (cur.isIndeterminate) {
                            AppLanguageManager.getString(
                                context,
                                R.string.bugreport_collecting_sec_fmt,
                                sec
                            )
                        } else {
                            cur.statusMessage
                        }
                        cur.copy(elapsedSeconds = sec, statusMessage = msg)
                    }
                }
            }
        }, "ToolHub-BugreportTicker").apply {
            isDaemon = true
            start()
        }
    }

    private fun runBugreportzCli(
        context: Context,
        outFile: File,
        isUsbDrive: Boolean,
        fileName: String
    ) {
        _state.update {
            it.copy(
                isGenerating = true,
                isIndeterminate = true,
                progressPercent = 0f,
                statusMessage = AppLanguageManager.getString(
                    context,
                    R.string.bugreport_collecting_sec_fmt,
                    it.elapsedSeconds
                ),
            )
        }
        val result = runCatching {
            val proc = ProcessBuilder("/system/bin/bugreportz", "-s")
                .redirectErrorStream(false)
                .start()
            outFile.outputStream().use { fos ->
                proc.inputStream.copyTo(fos, bufferSize = 32 * 1024)
            }
            val exitCode = proc.waitFor()
            if (exitCode != 0 || outFile.length() == 0L) {
                val errText = proc.errorStream.bufferedReader().use { it.readText() }.trim()
                throw IllegalStateException("bugreportz exit=$exitCode ${errText.take(120)}")
            }
        }
        result.onSuccess {
            finishSuccess(context, outFile, isUsbDrive, fileName)
        }.onFailure { err ->
            runCatching { if (outFile.exists() && outFile.length() == 0L) outFile.delete() }
            _state.update {
                BugreportUiState(
                    isGenerating = false,
                    progressPercent = 0f,
                    statusMessage = AppLanguageManager.getString(
                        context,
                        R.string.bugreport_failed_fmt,
                        err.message ?: ""
                    ),
                )
            }
            com.qytech.toolhub.service.StressKeepAliveService.stopIfIdle(context)
        }
    }

    private fun finishSuccess(
        context: Context,
        outFile: File,
        isUsbDrive: Boolean,
        fileName: String
    ) {
        if (isUsbDrive && outFile.exists() && outFile.length() > 0) {
            runCatching {
                val localDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (localDir.exists() || localDir.mkdirs()) {
                    outFile.copyTo(File(localDir, fileName), overwrite = true)
                }
            }
        }
        _state.update {
            BugreportUiState(
                isGenerating = false,
                progressPercent = 100f,
                statusMessage = if (isUsbDrive) {
                    AppLanguageManager.getString(
                        context,
                        R.string.bugreport_saved_usb_fmt,
                        outFile.absolutePath
                    )
                } else {
                    AppLanguageManager.getString(
                        context,
                        R.string.bugreport_saved_local_fmt,
                        outFile.absolutePath
                    )
                },
                lastGeneratedFile = outFile,
                lastSavedToUsb = isUsbDrive,
            )
        }
        com.qytech.toolhub.service.StressKeepAliveService.stopIfIdle(context)
    }
}
