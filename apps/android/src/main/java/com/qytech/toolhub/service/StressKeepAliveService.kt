package com.qytech.toolhub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.qytech.toolhub.MainActivity
import com.qytech.toolhub.R
import com.qytech.toolhub.data.AppLanguageManager
import com.qytech.toolhub.data.BugreportManagerHelper
import com.qytech.toolhub.data.StressTestRepository

/**
 * 老化压测与系统 Bugreport 后台保活前台服务：
 * 持有 PARTIAL_WAKE_LOCK 防止息屏后 CPU 休眠导致压测中断或计时不准。
 */
class StressKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val reason = intent?.getStringExtra(EXTRA_REASON)
            ?: AppLanguageManager.getString(this, R.string.stress_status_running)
        startForegroundSafely(reason)
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely(contentText: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "ToolHub Hardware Diagnostics",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keep CPU awake during hardware aging stress test and bugreport"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_toolhub)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ToolHub::StressKeepAliveWakeLock",
            ).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 最长保活 24 小时安全上限
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        }
    }

    companion object {
        private const val CHANNEL_ID = "toolhub_keepalive_channel"
        private const val NOTIFICATION_ID = 10086
        private const val ACTION_START = "com.qytech.toolhub.action.START_KEEPALIVE"
        private const val ACTION_STOP = "com.qytech.toolhub.action.STOP_KEEPALIVE"
        private const val EXTRA_REASON = "extra_reason"

        fun start(context: Context, reason: String) {
            runCatching {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, StressKeepAliveService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_REASON, reason)
                }
                appCtx.startForegroundService(intent)
            }
        }

        fun stopIfIdle(context: Context) {
            runCatching {
                val stressRunning = StressTestRepository.state.value.isRunning
                val bugreportRunning = BugreportManagerHelper.state.value.isGenerating
                if (!stressRunning && !bugreportRunning) {
                    val appCtx = context.applicationContext
                    appCtx.stopService(Intent(appCtx, StressKeepAliveService::class.java))
                }
            }
        }
    }
}
