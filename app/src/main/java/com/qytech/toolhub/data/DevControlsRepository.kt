package com.qytech.toolhub.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.qytech.toolhub.R

data class DevHealthCheckItem(
    val title: String,
    val detail: String,
    val passed: Boolean,
)

object DevControlsRepository {

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val STAY_ON_MASK_ALL =
        BatteryManager.BATTERY_PLUGGED_AC or
                BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS

    fun isWifiAdbEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    fun setWifiAdbEnabled(context: Context, enabled: Boolean): Result<Boolean> = runCatching {
        val target = if (enabled) 1 else 0
        val ok = Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, target)
        if (!ok) {
            throw IllegalStateException("Settings.Global.putInt($ADB_WIFI_ENABLED=$target) 返回 false")
        }
        if (enabled) {
            Thread.sleep(150L)
        }
        val actual = isWifiAdbEnabled(context)
        if (enabled && !actual) {
            throw IllegalStateException(
                AppLanguageManager.getString(
                    context,
                    R.string.wifi_required_for_adb
                )
            )
        }
        actual
    }

    fun isStayAwakeEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            0,
        ) != 0
    }.getOrDefault(false)

    fun setStayAwakeEnabled(context: Context, enabled: Boolean): Result<Boolean> = runCatching {
        val target = if (enabled) STAY_ON_MASK_ALL else 0
        val ok = Settings.Global.putInt(
            context.contentResolver,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            target,
        )
        if (!ok) {
            throw IllegalStateException("Settings.Global.putInt(STAY_ON_WHILE_PLUGGED_IN=$target) 返回 false")
        }
        isStayAwakeEnabled(context)
    }

    fun rebootDevice(context: Context): Result<Unit> = runCatching {
        val pm = context.getSystemService(PowerManager::class.java)
            ?: throw IllegalStateException("无法获取 PowerManager")
        pm.reboot(null)
    }

    fun runHealthChecks(
        context: Context,
        controllableOtgCount: Int,
        totalOtgCount: Int,
        bootSettings: BootSettings,
    ): List<DevHealthCheckItem> {
        val isSystemUid = Process.myUid() == Process.SYSTEM_UID
        val hasSecureSettings = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED
        val hasBootPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
        ) == PackageManager.PERMISSION_GRANTED
        val hasRebootPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.REBOOT,
        ) == PackageManager.PERMISSION_GRANTED
        val bootReady = bootSettings.autoOtgOnBoot && bootSettings.autoAdbOnBoot

        val missing = AppLanguageManager.getString(context, R.string.health_perm_missing)
        return listOf(
            DevHealthCheckItem(
                title = AppLanguageManager.getString(context, R.string.health_uid_title),
                detail = if (isSystemUid) "uid=1000 (android.uid.system)" else AppLanguageManager.getString(
                    context,
                    R.string.health_uid_fail_fmt,
                    Process.myUid()
                ),
                passed = isSystemUid,
            ),
            DevHealthCheckItem(
                title = AppLanguageManager.getString(context, R.string.health_perm_title),
                detail = buildString {
                    append("SECURE_SETTINGS=${if (hasSecureSettings) "OK" else missing}")
                    append(" · BOOT=${if (hasBootPerm) "OK" else missing}")
                    append(" · REBOOT=${if (hasRebootPerm) "OK" else missing}")
                },
                passed = hasSecureSettings && hasBootPerm,
            ),
            DevHealthCheckItem(
                title = AppLanguageManager.getString(context, R.string.health_otg_title),
                detail = if (controllableOtgCount > 0) {
                    AppLanguageManager.getString(
                        context,
                        R.string.health_otg_ok_fmt,
                        controllableOtgCount,
                        totalOtgCount - controllableOtgCount
                    )
                } else {
                    AppLanguageManager.getString(context, R.string.health_otg_fail)
                },
                passed = controllableOtgCount > 0,
            ),
            DevHealthCheckItem(
                title = AppLanguageManager.getString(context, R.string.health_boot_title),
                detail = if (bootReady) AppLanguageManager.getString(
                    context,
                    R.string.health_boot_ok
                ) else AppLanguageManager.getString(context, R.string.health_boot_fail),
                passed = bootReady,
            ),
        )
    }
}
