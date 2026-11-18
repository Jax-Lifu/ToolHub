package com.qytech.toolhub.data

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.view.WindowManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import android.net.wifi.WifiInfo as AndroidWifiInfo

data class NetworkSnapshot(
    val network: Network,
    val capabilities: NetworkCapabilities?,
    val linkProperties: LinkProperties?,
)

data class DeviceBasicInfo(
    val brand: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val buildNumber: String,
    val serialNumber: String,
    val boardName: String,
    val socPlatform: String,
    val cpuAbi: String,
    val uidSummary: String,
)

data class DeviceRuntimeInfo(
    val ramSummary: String,
    val storageSummary: String,
    val displaySummary: String,
    val uptimeSummary: String,
)

data class WifiStatusInfo(
    val ssid: String,
    val bssid: String,
    val ipAddress: String,
    val gateway: String,
    val dns: String,
    val macAddress: String,
    val signalStrength: Int,
    val linkSpeed: Int,
    val frequency: Int,
    val isConnected: Boolean,
)

data class EthernetStatusInfo(
    val interfaceName: String,
    val ipAddress: String,
    val gateway: String,
    val dns: String,
    val macAddress: String,
    val isConnected: Boolean,
)

object DeviceInfoRepository {

    @SuppressLint("HardwareIds")
    fun readBasicInfo(
        props: Map<String, String>? = null,
        context: Context? = null,
    ): DeviceBasicInfo {
        val unknownText = if (context != null) AppLanguageManager.getString(
            context,
            com.qytech.toolhub.R.string.val_unknown
        ) else "Unknown"
        val serial = runCatching { Build.getSerial() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ?: (props?.get("ro.serialno") ?: SystemPropertiesHelper.get("ro.serialno")).ifBlank {
                (props?.get("ro.boot.serialno")
                    ?: SystemPropertiesHelper.get("ro.boot.serialno")).ifBlank { unknownText }
            }

        val uid = Process.myUid()
        val uidSummary = if (uid == Process.SYSTEM_UID) {
            "uid=$uid (android.uid.system)"
        } else {
            "uid=$uid"
        }

        val platform = (props?.get("ro.board.platform")
            ?: SystemPropertiesHelper.get("ro.board.platform")).ifBlank { Build.HARDWARE }

        return DeviceBasicInfo(
            brand = Build.BRAND,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            buildNumber = Build.DISPLAY,
            serialNumber = serial,
            boardName = Build.BOARD,
            socPlatform = platform,
            cpuAbi = Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "unknown" },
            uidSummary = uidSummary,
        )
    }

    fun readRuntimeInfo(context: Context): DeviceRuntimeInfo {
        val am = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo()
        val unknownText =
            AppLanguageManager.getString(context, com.qytech.toolhub.R.string.val_unknown)
        val ramSummary = if (am != null) {
            am.getMemoryInfo(memInfo)
            val usedBytes = (memInfo.totalMem - memInfo.availMem).coerceAtLeast(0L)
            AppLanguageManager.getString(
                context,
                com.qytech.toolhub.R.string.ram_summary_fmt,
                formatBytes(memInfo.availMem),
                formatBytes(memInfo.totalMem),
                formatBytes(usedBytes),
            )
        } else {
            unknownText
        }

        val storageSummary = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val avail = stat.availableBytes
            val total = stat.totalBytes
            AppLanguageManager.getString(
                context,
                com.qytech.toolhub.R.string.storage_summary_fmt,
                formatBytes(avail),
                formatBytes(total),
            )
        }.getOrDefault(unknownText)

        val displaySummary = runCatching {
            val wm = context.getSystemService(WindowManager::class.java)
            val dm = context.resources.displayMetrics
            val bounds = wm?.currentWindowMetrics?.bounds
            val widthPx = bounds?.width() ?: dm.widthPixels
            val heightPx = bounds?.height() ?: dm.heightPixels
            val densityDpi = dm.densityDpi
            val widthDp = (widthPx / dm.density).toInt()
            val heightDp = (heightPx / dm.density).toInt()
            "$widthPx × $heightPx px (${widthDp} × ${heightDp} dp @ ${densityDpi} dpi)"
        }.getOrDefault(unknownText)

        val uptimeSummary = formatDuration(SystemClock.elapsedRealtime(), context)

        return DeviceRuntimeInfo(
            ramSummary = ramSummary,
            storageSummary = storageSummary,
            displaySummary = displaySummary,
            uptimeSummary = uptimeSummary,
        )
    }

    fun buildWifiStatus(
        snapshots: Collection<NetworkSnapshot>,
        context: Context? = null,
    ): WifiStatusInfo {
        val wifiSnapshot = snapshots.firstOrNull { snapshot ->
            val caps = snapshot.capabilities ?: return@firstOrNull false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false

            val ifName = snapshot.linkProperties?.interfaceName
            val isEthernetIf =
                ifName != null && (ifName.startsWith("eth") || ifName.startsWith("en"))
            val hasWifiTransportInfo = caps.transportInfo is AndroidWifiInfo
            val isWifiIf = ifName?.startsWith("wlan") == true || ifName?.startsWith("wifi") == true
            !isEthernetIf && (hasWifiTransportInfo || isWifiIf || !caps.hasTransport(
                NetworkCapabilities.TRANSPORT_ETHERNET
            ))
        }
        val macFallback = readInterfaceMac("wlan0") ?: "unknown"
        val disconnectedText = if (context != null) AppLanguageManager.getString(
            context,
            com.qytech.toolhub.R.string.status_disconnected
        ) else "Disconnected"
        val noIpText = if (context != null) AppLanguageManager.getString(
            context,
            com.qytech.toolhub.R.string.val_no_ip
        ) else "No IP"
        val hiddenSsidText = if (context != null) AppLanguageManager.getString(
            context,
            com.qytech.toolhub.R.string.val_ssid_hidden
        ) else "Connected (Hidden)"
        if (wifiSnapshot == null) {
            return WifiStatusInfo(
                ssid = disconnectedText,
                bssid = "-",
                ipAddress = "-",
                gateway = "-",
                dns = "-",
                macAddress = macFallback,
                signalStrength = 0,
                linkSpeed = 0,
                frequency = 0,
                isConnected = false,
            )
        }

        val wifiInfo = wifiSnapshot.capabilities?.transportInfo as? AndroidWifiInfo
        val lp = wifiSnapshot.linkProperties
        val ipv4 = extractIpv4(lp) ?: noIpText
        val gateway = extractGateway(lp) ?: "-"
        val dns = extractDns(lp) ?: "-"

        val rawSsid = wifiInfo?.ssid?.removeSurrounding("\"")
        val ssid = when {
            rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>" -> hiddenSsidText
            else -> rawSsid
        }

        @Suppress("DEPRECATION")
        val mac = macFallback.takeIf { it != "unknown" }
            ?: wifiInfo?.macAddress
            ?: "unknown"

        return WifiStatusInfo(
            ssid = ssid,
            bssid = wifiInfo?.bssid?.takeIf { it.isNotBlank() } ?: "-",
            ipAddress = ipv4,
            gateway = gateway,
            dns = dns,
            macAddress = mac,
            signalStrength = wifiInfo?.rssi ?: 0,
            linkSpeed = wifiInfo?.linkSpeed ?: 0,
            frequency = wifiInfo?.frequency ?: 0,
            isConnected = true,
        )
    }

    fun buildEthernetStatuses(
        snapshots: Collection<NetworkSnapshot>,
        context: Context? = null,
    ): List<EthernetStatusInfo> {
        val noIpText = if (context != null) AppLanguageManager.getString(
            context,
            com.qytech.toolhub.R.string.val_no_ip
        ) else "No IP"
        val ethSnapshots = snapshots.filter { snapshot ->
            snapshot.capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
        val activeByIfName = ethSnapshots.mapNotNull { snapshot ->
            val ifName = snapshot.linkProperties?.interfaceName ?: return@mapNotNull null
            ifName to snapshot
        }.toMap()

        val result = mutableListOf<EthernetStatusInfo>()
        val seenNames = HashSet<String>()

        runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.name.matches(Regex("eth\\d+|en[a-z0-9]+"))) continue
                seenNames.add(intf.name)

                val matchedSnapshot = activeByIfName[intf.name]
                val lp = matchedSnapshot?.linkProperties
                val ipv4FromLp = extractIpv4(lp)
                val ipv4FromNic = Collections.list(intf.inetAddresses)
                    .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                    ?.hostAddress
                val ipv4 = ipv4FromLp ?: ipv4FromNic ?: noIpText
                val mac = intf.hardwareAddress?.joinToString(":") { "%02X".format(it) } ?: "unknown"
                val isConnected = matchedSnapshot != null && ipv4 != noIpText

                result += EthernetStatusInfo(
                    interfaceName = intf.name,
                    ipAddress = ipv4,
                    gateway = extractGateway(lp) ?: "-",
                    dns = extractDns(lp) ?: "-",
                    macAddress = mac,
                    isConnected = isConnected,
                )
            }
        }

        // 兜底：若 ConnectivityManager 报告了以太网接口但 NetworkInterface 列表未包含
        activeByIfName.forEach { (ifName, snapshot) ->
            if (ifName !in seenNames) {
                val lp = snapshot.linkProperties
                val ipv4 = extractIpv4(lp) ?: noIpText
                result += EthernetStatusInfo(
                    interfaceName = ifName,
                    ipAddress = ipv4,
                    gateway = extractGateway(lp) ?: "-",
                    dns = extractDns(lp) ?: "-",
                    macAddress = readInterfaceMac(ifName) ?: "unknown",
                    isConnected = ipv4 != noIpText,
                )
            }
        }

        return result.sortedBy { it.interfaceName }
    }

    private fun extractIpv4(lp: LinkProperties?): String? {
        val linkAddr = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return null
        return linkAddr.address.hostAddress
    }

    private fun extractGateway(lp: LinkProperties?): String? {
        val route = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
        return route?.gateway?.hostAddress
    }

    private fun extractDns(lp: LinkProperties?): String? {
        val servers = lp?.dnsServers
            ?.filterIsInstance<Inet4Address>()
            ?.mapNotNull { it.hostAddress }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return servers.joinToString(", ")
    }

    private fun readInterfaceMac(interfaceName: String): String? = runCatching {
        NetworkInterface.getByName(interfaceName)
            ?.hardwareAddress
            ?.joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    private fun formatBytes(bytes: Long): String {
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes / mb)
            else -> "${bytes / 1024} KB"
        }
    }

    private fun formatDuration(millis: Long, context: Context): String {
        val totalSeconds = millis / 1000
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            days > 0 -> AppLanguageManager.getString(
                context,
                com.qytech.toolhub.R.string.uptime_dhm_fmt,
                days,
                hours,
                minutes
            )

            hours > 0 -> AppLanguageManager.getString(
                context,
                com.qytech.toolhub.R.string.uptime_hm_fmt,
                hours,
                minutes
            )

            else -> AppLanguageManager.getString(
                context,
                com.qytech.toolhub.R.string.uptime_m_fmt,
                minutes
            )
        }
    }
}
