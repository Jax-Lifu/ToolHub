package com.qytech.toolhub.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

enum class AotMode(val rawValue: String, val label: String) {
    SPEED("speed", "全量 AOT (speed)"),
    SPEED_PROFILE("speed-profile", "热点 AOT (speed-profile)"),
    EVERYTHING("everything", "极客全编 (everything)"),
    VERIFY("verify", "仅校验 (verify)"),
    RESET("reset", "重置 (reset)"),
}

data class AppAotInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val abi: String,
    val status: String,
    val reason: String,
    val odexSizeBytes: Long,
) {
    val isSpeedCompiled: Boolean
        get() = status == "speed" || status == "everything"
}

data class AotBatchState(
    val isRunning: Boolean = false,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val currentPackage: String = "",
    val statusMessage: String = "Ready",
)

object AotRepository {

    private val _batchState = MutableStateFlow(AotBatchState())
    val batchState: StateFlow<AotBatchState> = _batchState.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()

    private data class ParsedDexopt(
        val abi: String = "-",
        val status: String = "unknown",
        val reason: String = "-",
        val odexSizeBytes: Long = 0L,
    )

    fun loadInstalledAppsAot(context: Context): List<AppAotInfo> {
        val pm = context.packageManager
        val installed = runCatching {
            pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        }.getOrDefault(emptyList())

        val dexoptMap = readDumpsysDexopt()

        return installed.map { app ->
            val pkg = app.packageName
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg)
            val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val dex = dexoptMap[pkg] ?: ParsedDexopt()
            AppAotInfo(
                packageName = pkg,
                appName = label,
                isSystemApp = isSys,
                abi = dex.abi,
                status = dex.status,
                reason = dex.reason,
                odexSizeBytes = dex.odexSizeBytes,
            )
        }.sortedWith(
            compareBy<AppAotInfo> { it.packageName != context.packageName }
                .thenBy { it.isSystemApp }
                .thenBy { it.appName.lowercase() }
        )
    }

    private fun readDumpsysDexopt(): Map<String, ParsedDexopt> {
        val map = mutableMapOf<String, ParsedDexopt>()
        runCatching {
            val proc = ProcessBuilder("dumpsys", "package", "dexopt")
                .redirectErrorStream(true)
                .start()
            val lines = proc.inputStream.bufferedReader().readLines()
            proc.waitFor()

            var currentPkg: String? = null
            var curAbi = "-"
            var curStatus = "unknown"
            var curReason = "-"
            var curSize = 0L

            fun flush() {
                val p = currentPkg ?: return
                if (!map.containsKey(p) || curStatus != "unknown") {
                    map[p] = ParsedDexopt(curAbi, curStatus, curReason, curSize)
                }
            }

            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("[") && line.endsWith("]") && !line.contains(" ")) {
                    flush()
                    currentPkg = line.substring(1, line.length - 1)
                    curAbi = "-"
                    curStatus = "unknown"
                    curReason = "-"
                    curSize = 0L
                } else if (currentPkg != null) {
                    if (line.contains("[status=") && line.contains(":")) {
                        curAbi = line.substringBefore(":").trim()
                        curStatus = line.substringAfter("[status=").substringBefore("]")
                        if (line.contains("[reason=")) {
                            curReason = line.substringAfter("[reason=").substringBefore("]")
                        }
                    } else if (line.startsWith("[location is ") && line.endsWith("]")) {
                        val odexPath = line.removePrefix("[location is ").removeSuffix("]").trim()
                        curSize = runCatching {
                            File(odexPath).takeIf { it.exists() }?.length() ?: 0L
                        }.getOrDefault(0L)
                    }
                }
            }
            flush()
        }
        return map
    }

    @SuppressLint("PrivateApi")
    fun compilePackage(packageName: String, mode: AotMode): Result<String> {
        // 1. 优先尝试 `cmd package compile`
        val cmdArgs = if (mode == AotMode.RESET) {
            listOf("cmd", "package", "compile", "--reset", packageName)
        } else {
            listOf("cmd", "package", "compile", "-m", mode.rawValue, "-f", packageName)
        }

        val cliResult = runCatching {
            val proc = ProcessBuilder(cmdArgs)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = proc.waitFor()
            if (code == 0 && (out.contains("Success", ignoreCase = true) || out.isBlank())) {
                out.ifBlank { "Success" }
            } else {
                throw IllegalStateException("cmd exit=$code: $out")
            }
        }
        if (cliResult.isSuccess) return cliResult

        // 2. 若 system_app 受限于 shell cmd SELinux，则通过 IPackageManager Binder 反射调用
        return runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "package") as? IBinder
                ?: throw IllegalStateException("无法获取 package service binder")

            val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val ipm = asInterface.invoke(null, binder)!!

            if (mode == AotMode.RESET) {
                val clearMethod =
                    ipm.javaClass.methods.firstOrNull { it.name == "clearApplicationProfileData" }
                clearMethod?.invoke(ipm, packageName)
            }

            val targetFilter = if (mode == AotMode.RESET) "verify" else mode.rawValue
            val performMethod = ipm.javaClass.methods.firstOrNull {
                it.name == "performDexOptMode" && it.parameterTypes.size >= 5
            } ?: throw IllegalStateException("未找到 IPackageManager.performDexOptMode")

            val ok = when (performMethod.parameterTypes.size) {
                5 -> performMethod.invoke(
                    ipm,
                    packageName,
                    mode == AotMode.SPEED_PROFILE,
                    targetFilter,
                    true,
                    true
                ) as Boolean

                6 -> performMethod.invoke(
                    ipm,
                    packageName,
                    mode == AotMode.SPEED_PROFILE,
                    targetFilter,
                    true,
                    true,
                    null
                ) as Boolean

                else -> throw IllegalStateException("不支持的 performDexOptMode 参数个数")
            }

            if (ok) "Binder AOT ($targetFilter) Success" else throw IllegalStateException("performDexOptMode 返回 false (可能已处于目标状态或无需编译)")
        }.recoverCatching { binderErr ->
            throw IllegalStateException("${cliResult.exceptionOrNull()?.message}; ${binderErr.message}")
        }
    }

    fun ensureSelfAotOptimized(context: Context) {
        val pkg = context.packageName
        executor.submit {
            runCatching {
                Thread.sleep(3_000L)
                val dexopt = readDumpsysDexopt()[pkg]
                if (dexopt == null || (dexopt.status != "speed" && dexopt.status != "everything")) {
                    compilePackage(pkg, AotMode.SPEED)
                }
            }
        }
    }

    fun startBatchCompile(packages: List<String>, mode: AotMode, context: Context? = null) {
        if (_batchState.value.isRunning || packages.isEmpty()) return

        _batchState.update {
            AotBatchState(
                isRunning = true,
                currentIndex = 0,
                totalCount = packages.size,
                currentPackage = packages.first(),
                statusMessage = if (context != null) {
                    AppLanguageManager.getString(
                        context,
                        com.qytech.toolhub.R.string.aot_batch_preparing_fmt,
                        packages.size,
                        mode.label
                    )
                } else {
                    "Preparing ${mode.label} for ${packages.size} apps..."
                },
            )
        }

        executor.submit {
            var successCount = 0
            var failCount = 0
            var lastErr = ""
            packages.forEachIndexed { idx, pkg ->
                _batchState.update {
                    it.copy(
                        isRunning = true,
                        currentIndex = idx + 1,
                        totalCount = packages.size,
                        currentPackage = pkg,
                        statusMessage = if (context != null) {
                            AppLanguageManager.getString(
                                context,
                                com.qytech.toolhub.R.string.aot_batch_compiling_fmt,
                                idx + 1,
                                packages.size,
                                pkg
                            )
                        } else {
                            "Compiling (${idx + 1}/${packages.size}): $pkg"
                        },
                    )
                }
                val r = compilePackage(pkg, mode)
                if (r.isSuccess) {
                    successCount++
                } else {
                    failCount++
                    lastErr = r.exceptionOrNull()?.message.orEmpty()
                }
            }
            val msg = if (successCount == 0 && failCount > 0) {
                if (lastErr.contains("need root or shell access", ignoreCase = true)) {
                    if (context != null) AppLanguageManager.getString(
                        context,
                        com.qytech.toolhub.R.string.aot_batch_restricted
                    )
                    else "Android 14+ ART Service requires adb shell / root"
                } else {
                    if (context != null) AppLanguageManager.getString(
                        context,
                        com.qytech.toolhub.R.string.aot_batch_fail_fmt,
                        failCount,
                        lastErr.take(90)
                    )
                    else "Batch AOT failed ($failCount): ${lastErr.take(90)}"
                }
            } else {
                if (context != null) {
                    val skipSuffix = if (failCount > 0) AppLanguageManager.getString(
                        context,
                        com.qytech.toolhub.R.string.aot_batch_skip_fmt,
                        failCount
                    ) else ""
                    AppLanguageManager.getString(
                        context,
                        com.qytech.toolhub.R.string.aot_batch_done_fmt,
                        successCount,
                        skipSuffix
                    )
                } else {
                    "Batch AOT done: $successCount succeeded" + if (failCount > 0) " ($failCount failed)" else ""
                }
            }
            _batchState.update {
                AotBatchState(
                    isRunning = false,
                    currentIndex = packages.size,
                    totalCount = packages.size,
                    currentPackage = "",
                    statusMessage = msg,
                )
            }
        }
    }
}
