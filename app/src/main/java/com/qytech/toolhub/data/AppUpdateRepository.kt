package com.qytech.toolhub.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.qytech.toolhub.BuildConfig
import com.qytech.toolhub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class GitHubAssetDto(
    val name: String = "",
    val size: Long = 0L,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList(),
)

data class AppReleaseInfo(
    val versionName: String,
    val tagName: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkName: String?,
    val apkUrl: String?,
    val apkSizeBytes: Long,
)

enum class AppUpdateStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    INSTALLING,
    ERROR,
}

data class AppUpdateState(
    val status: AppUpdateStatus = AppUpdateStatus.IDLE,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val latestRelease: AppReleaseInfo? = null,
    val downloadProgressPct: Int = 0,
    val message: String? = null,
)

object AppUpdateRepository {
    private const val TAG = "AppUpdateRepository"
    private const val GITEE_LATEST_RELEASE_API = "https://gitee.com/api/v5/repos/qytech/ToolHub/releases/latest"
    private const val GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/qytech/ToolHub/releases/latest"
    private const val ACTION_INSTALL_RESULT = "com.qytech.toolhub.ACTION_SELF_UPDATE_RESULT"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /**
     * Compare two semantic version strings (e.g. "v0.0.10" vs "0.0.2").
     * Returns > 0 if [remote] is newer than [current], 0 if equal, < 0 if older.
     */
    fun compareVersions(remote: String, current: String): Int {
        val rParts = parseVersionParts(remote)
        val cParts = parseVersionParts(current)
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val rv = rParts.getOrElse(i) { 0 }
            val cv = cParts.getOrElse(i) { 0 }
            if (rv != cv) return rv.compareTo(cv)
        }
        return 0
    }

    private fun parseVersionParts(raw: String): List<Int> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        return cleaned.split('.').map { it.toIntOrNull() ?: 0 }
    }

    fun parseReleaseDto(dto: GitHubReleaseDto): AppReleaseInfo {
        val cleanVer = dto.tagName.trim().removePrefix("v").removePrefix("V")
            .ifBlank { dto.name.trim().removePrefix("v").removePrefix("V") }
        val apkAsset = dto.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        return AppReleaseInfo(
            versionName = cleanVer,
            tagName = dto.tagName,
            releaseNotes = dto.body.trim(),
            htmlUrl = dto.htmlUrl,
            apkName = apkAsset?.name,
            apkUrl = apkAsset?.browserDownloadUrl,
            apkSizeBytes = apkAsset?.size ?: 0L,
        )
    }

    fun checkForUpdate(context: Context, silent: Boolean = false) {
        if (activeJob?.isActive == true) return
        val appCtx = context.applicationContext
        activeJob = scope.launch {
            if (!silent) {
                _state.update {
                    it.copy(
                        status = AppUpdateStatus.CHECKING,
                        message = AppLanguageManager.getString(appCtx, R.string.update_checking),
                    )
                }
            }
            val result = runCatching { fetchLatestRelease() }
            result.onSuccess { release ->
                val isNewer = compareVersions(release.versionName, BuildConfig.VERSION_NAME) > 0
                if (isNewer) {
                    _state.update {
                        it.copy(
                            status = AppUpdateStatus.UPDATE_AVAILABLE,
                            latestRelease = release,
                            downloadProgressPct = 0,
                            message = AppLanguageManager.getString(
                                appCtx,
                                R.string.update_new_found_fmt,
                                release.versionName,
                            ),
                        )
                    }
                } else if (!silent) {
                    _state.update {
                        it.copy(
                            status = AppUpdateStatus.UP_TO_DATE,
                            latestRelease = release,
                            message = AppLanguageManager.getString(appCtx, R.string.update_up_to_date),
                        )
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Update check failed: ${err.message}")
                if (!silent) {
                    _state.update {
                        it.copy(
                            status = AppUpdateStatus.ERROR,
                            message = AppLanguageManager.getString(
                                appCtx,
                                R.string.update_failed_fmt,
                                err.message ?: "Network error",
                            ),
                        )
                    }
                }
            }
        }
    }

    fun downloadAndInstall(context: Context, release: AppReleaseInfo) {
        if (activeJob?.isActive == true) return
        val appCtx = context.applicationContext
        val apkUrl = release.apkUrl
        if (apkUrl.isNullOrBlank()) {
            _state.update {
                it.copy(
                    status = AppUpdateStatus.ERROR,
                    message = AppLanguageManager.getString(appCtx, R.string.update_no_apk),
                )
            }
            return
        }

        activeJob = scope.launch {
            _state.update {
                it.copy(
                    status = AppUpdateStatus.DOWNLOADING,
                    downloadProgressPct = 0,
                    message = AppLanguageManager.getString(appCtx, R.string.update_downloading_fmt, 0),
                )
            }

            val apkResult = runCatching {
                val updatesDir = File(appCtx.cacheDir, "updates").apply { mkdirs() }
                updatesDir.listFiles()?.forEach { old -> old.delete() }
                val outFile = File(updatesDir, release.apkName ?: "toolhub-update.apk")
                downloadFile(apkUrl, outFile, release.apkSizeBytes) { pct ->
                    _state.update {
                        it.copy(
                            status = AppUpdateStatus.DOWNLOADING,
                            downloadProgressPct = pct,
                            message = AppLanguageManager.getString(appCtx, R.string.update_downloading_fmt, pct),
                        )
                    }
                }
                outFile
            }

            val apkFile = apkResult.getOrElse { err ->
                Log.e(TAG, "Download failed", err)
                _state.update {
                    it.copy(
                        status = AppUpdateStatus.ERROR,
                        message = AppLanguageManager.getString(
                            appCtx,
                            R.string.update_failed_fmt,
                            err.message ?: "Download error",
                        ),
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    status = AppUpdateStatus.INSTALLING,
                    downloadProgressPct = 100,
                    message = AppLanguageManager.getString(appCtx, R.string.update_installing),
                )
            }

            runCatching {
                installViaPackageInstaller(appCtx, apkFile)
            }.onFailure { err ->
                Log.e(TAG, "Install failed", err)
                _state.update {
                    it.copy(
                        status = AppUpdateStatus.ERROR,
                        message = AppLanguageManager.getString(
                            appCtx,
                            R.string.update_failed_fmt,
                            err.message ?: "Install error",
                        ),
                    )
                }
            }
        }
    }

    private fun fetchLatestRelease(): AppReleaseInfo {
        val sources = listOf(GITEE_LATEST_RELEASE_API, GITHUB_LATEST_RELEASE_API)
        var lastError: Throwable? = null
        for (apiUrl in sources) {
            val res = runCatching { fetchReleaseFromUrl(apiUrl) }
            val info = res.getOrNull()
            if (info != null && !info.apkUrl.isNullOrBlank()) {
                return info
            }
            lastError = res.exceptionOrNull() ?: lastError
        }
        throw (lastError ?: IllegalStateException("No release source available"))
    }

    private fun fetchReleaseFromUrl(apiUrl: String): AppReleaseInfo {
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ToolHub-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val dto = json.decodeFromString<GitHubReleaseDto>(body)
            return parseReleaseDto(dto)
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(
        urlStr: String,
        target: File,
        expectedBytes: Long,
        onProgress: (Int) -> Unit,
    ) {
        var currentUrl = urlStr
        var redirects = 0
        var conn: HttpURLConnection? = null
        try {
            while (redirects < 5) {
                val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "ToolHub-Android/${BuildConfig.VERSION_NAME}")
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) throw IllegalStateException("Empty redirect")
                    currentUrl = loc
                    redirects++
                } else {
                    conn = c
                    break
                }
            }
            val activeConn = conn ?: throw IllegalStateException("Too many redirects")
            if (activeConn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${activeConn.responseCode}")
            }
            val total = activeConn.contentLengthLong.takeIf { it > 0L } ?: expectedBytes
            val buf = ByteArray(16 * 1024)
            var downloaded = 0L
            var lastPct = -1
            activeConn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0L) {
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun installViaPackageInstaller(appCtx: Context, apkFile: File) {
        val installer = appCtx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apkFile.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input ->
                    input.copyTo(out)
                }
                session.fsync(out)
            }

            val action = "$ACTION_INSTALL_RESULT.$sessionId"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val status = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE,
                    )
                    when (status) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION")
                            val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            } else {
                                intent.getParcelableExtra(Intent.EXTRA_INTENT)
                            }
                            if (confirmIntent != null) {
                                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(confirmIntent)
                            }
                        }
                        PackageInstaller.STATUS_SUCCESS -> {
                            runCatching { appCtx.unregisterReceiver(this) }
                            _state.update { it.copy(status = AppUpdateStatus.UP_TO_DATE, message = null) }
                        }
                        else -> {
                            runCatching { appCtx.unregisterReceiver(this) }
                            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Status $status"
                            _state.update {
                                it.copy(
                                    status = AppUpdateStatus.ERROR,
                                    message = AppLanguageManager.getString(appCtx, R.string.update_failed_fmt, msg),
                                )
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appCtx.registerReceiver(receiver, filter)
            }

            val callbackIntent = Intent(action).setPackage(appCtx.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(appCtx, sessionId, callbackIntent, flags)
            session.commit(pendingIntent.intentSender)
        } catch (t: Throwable) {
            runCatching { session.abandon() }
            throw t
        } finally {
            session.close()
        }
    }
}
