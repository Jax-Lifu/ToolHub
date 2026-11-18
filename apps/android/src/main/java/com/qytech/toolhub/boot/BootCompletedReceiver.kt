package com.qytech.toolhub.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.qytech.toolhub.data.OtgRepository
import com.qytech.toolhub.data.WebReportServer
import com.qytech.toolhub.service.FleetKeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val appContext = context.applicationContext ?: context
        val pendingResult = goAsync()
        val shortTrigger = action.substringAfterLast('.')
        Log.i(TAG, "Received boot broadcast: $action")

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val record = OtgRepository.applyBootDefaults(
                    context = appContext,
                    trigger = shortTrigger,
                    forceApply = false,
                )
                Log.i(
                    TAG,
                    "Boot defaults applied (success=${record.success}): nodes=${record.nodeSummaries}, adb=${record.adbSummary}",
                )
                if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                    WebReportServer.start(appContext)
                    FleetKeepAliveService.startIfEnabled(appContext)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed applying boot defaults on $action", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ToolHubBoot"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
