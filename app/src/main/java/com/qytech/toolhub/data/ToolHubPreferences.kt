package com.qytech.toolhub.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BootSettings(
    val autoOtgOnBoot: Boolean = true,
    val autoAdbOnBoot: Boolean = true,
    val syncAdbWhenSwitchOtg: Boolean = true,
)

@Serializable
data class BootExecutionRecord(
    val timestampMillis: Long,
    val trigger: String,
    val success: Boolean,
    val nodeSummaries: List<String> = emptyList(),
    val adbSummary: String = "",
    val errorMessage: String? = null,
)

class ToolHubPreferences(context: Context) {

    // 使用设备保护存储，确保 Direct Boot (LOCKED_BOOT_COMPLETED) 阶段即可安全读写
    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBootSettings(): BootSettings = BootSettings(
        autoOtgOnBoot = prefs.getBoolean(KEY_AUTO_OTG_ON_BOOT, true),
        autoAdbOnBoot = prefs.getBoolean(KEY_AUTO_ADB_ON_BOOT, true),
        syncAdbWhenSwitchOtg = prefs.getBoolean(KEY_SYNC_ADB_WHEN_OTG, true),
    )

    fun setAutoOtgOnBoot(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_OTG_ON_BOOT, enabled).apply()
    }

    fun setAutoAdbOnBoot(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ADB_ON_BOOT, enabled).apply()
    }

    fun setSyncAdbWhenSwitchOtg(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ADB_WHEN_OTG, enabled).apply()
    }

    fun getBootHistory(): List<BootExecutionRecord> =
        decodeHistory(prefs.getString(KEY_BOOT_HISTORY, null))

    fun appendBootRecord(record: BootExecutionRecord) {
        val updated = (listOf(record) + getBootHistory()).take(MAX_HISTORY_SIZE)
        prefs.edit().putString(KEY_BOOT_HISTORY, encodeHistory(updated)).apply()
    }

    fun clearBootHistory() {
        prefs.edit().remove(KEY_BOOT_HISTORY).apply()
    }

    fun getCustomSysfsPaths(): List<String> =
        decodeStringList(prefs.getString(KEY_CUSTOM_SYSFS_PATHS, null))

    fun addCustomSysfsPath(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        val current = getCustomSysfsPaths()
        if (trimmed !in current) {
            prefs.edit()
                .putString(KEY_CUSTOM_SYSFS_PATHS, encodeStringList(listOf(trimmed) + current))
                .apply()
        }
    }

    fun removeCustomSysfsPath(path: String) {
        val updated = getCustomSysfsPaths().filterNot { it == path }
        prefs.edit().putString(KEY_CUSTOM_SYSFS_PATHS, encodeStringList(updated)).apply()
    }

    companion object {
        private const val PREFS_NAME = "toolhub_boot_prefs"
        private const val KEY_AUTO_OTG_ON_BOOT = "auto_otg_on_boot"
        private const val KEY_AUTO_ADB_ON_BOOT = "auto_adb_on_boot"
        private const val KEY_SYNC_ADB_WHEN_OTG = "sync_adb_when_otg"
        private const val KEY_BOOT_HISTORY = "boot_history"
        private const val KEY_CUSTOM_SYSFS_PATHS = "custom_sysfs_paths"
        private const val MAX_HISTORY_SIZE = 10

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        internal fun encodeHistory(records: List<BootExecutionRecord>): String =
            json.encodeToString(records)

        internal fun decodeHistory(raw: String?): List<BootExecutionRecord> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                json.decodeFromString<List<BootExecutionRecord>>(raw)
            }.getOrDefault(emptyList())
        }

        internal fun encodeStringList(items: List<String>): String =
            json.encodeToString(items)

        internal fun decodeStringList(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                json.decodeFromString<List<String>>(raw)
            }.getOrDefault(emptyList())
        }
    }
}
