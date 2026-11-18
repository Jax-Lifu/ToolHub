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
import android.util.Log
import com.qytech.toolhub.MainActivity
import com.qytech.toolhub.R
import com.qytech.toolhub.data.RemoteFleetRepository
import com.qytech.toolhub.data.WebReportServer

/**
 * 远程管理与 WebSocket 反向隧道后台保活前台服务：
 * 确保 App 退居后台或开机自启后，WebReportServer、心跳上报与 WS 反向隧道持续在线。
 */
class FleetKeepAliveService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundSafely()

        runCatching {
            WebReportServer.start(applicationContext)
            if (RemoteFleetRepository.state.value.deviceMac.isBlank()) {
                RemoteFleetRepository.init(applicationContext)
            }
        }.onFailure {
            Log.w(TAG, "Failed to ensure fleet workers in service", it)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "ToolHub Remote Fleet Service",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keep ToolHub Web console and remote tunnel connected in background"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val serverUrl = RemoteFleetRepository.state.value.config.fleetServerUrl
            val text = if (serverUrl.isNotBlank()) {
                "远程管理隧道运行中 · $serverUrl"
            } else {
                "远程管理服务运行中"
            }

            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_toolhub)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
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
        }.onFailure {
            Log.w(TAG, "startForeground failed", it)
        }
    }

    companion object {
        private const val TAG = "FleetKeepAliveService"
        private const val CHANNEL_ID = "toolhub_fleet_channel"
        private const val NOTIFICATION_ID = 10087
        private const val ACTION_START = "com.qytech.toolhub.action.START_FLEET_KEEPALIVE"
        private const val ACTION_STOP = "com.qytech.toolhub.action.STOP_FLEET_KEEPALIVE"

        fun startIfEnabled(context: Context) {
            runCatching {
                val appCtx = context.applicationContext
                if (RemoteFleetRepository.state.value.deviceMac.isBlank()) {
                    RemoteFleetRepository.init(appCtx)
                }
                if (RemoteFleetRepository.state.value.config.enabled) {
                    val intent = Intent(appCtx, FleetKeepAliveService::class.java).apply {
                        action = ACTION_START
                    }
                    appCtx.startForegroundService(intent)
                }
            }.onFailure {
                Log.w(TAG, "startIfEnabled failed", it)
            }
        }

        fun stop(context: Context) {
            runCatching {
                val appCtx = context.applicationContext
                appCtx.stopService(Intent(appCtx, FleetKeepAliveService::class.java))
            }
        }
    }
}
