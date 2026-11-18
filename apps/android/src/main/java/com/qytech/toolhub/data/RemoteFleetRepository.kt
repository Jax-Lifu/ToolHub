package com.qytech.toolhub.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.qytech.toolhub.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URI
import java.net.URL
import kotlin.math.abs

@Serializable
data class FleetHeartbeatPayload(
    val mac: String,
    val serial: String,
    val model: String,
    val brand: String,
    val androidVersion: String,
    val sdkInt: Int,
    val socPlatform: String,
    val lanIp: String,
    val appVersion: String,
    val webPort: Int,
    val adbPort: Int,
    val tunnelWebPort: Int,
    val tunnelAdbPort: Int,
    val tunnelConnected: Boolean,
    val stressRunning: Boolean,
    val socTempC: Float?,
    val uptime: String,
    val sshPubKey: String? = null,
)

data class RemoteFleetConfig(
    val enabled: Boolean = false,
    val fleetServerUrl: String = "",
    val deviceToken: String = "",
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "toolhub-relay",
    val remoteWebPort: Int = 18001,
    val remoteAdbPort: Int = 25001,
)

enum class TunnelConnectionStatus {
    DISABLED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

data class RemoteFleetState(
    val config: RemoteFleetConfig = RemoteFleetConfig(),
    val deviceMac: String = "-",
    val deviceSerial: String = "-",
    val publicKeyOpenSsh: String = "",
    val tunnelStatus: TunnelConnectionStatus = TunnelConnectionStatus.DISABLED,
    val lastHeartbeatOk: Boolean = false,
    val lastHeartbeatTimeMillis: Long = 0L,
    val statusMessage: String? = null,
)

object RemoteFleetRepository {
    private const val TAG = "RemoteFleetRepository"
    private const val PREFS_NAME = "toolhub_remote_fleet"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVER_URL = "fleet_server_url"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_SSH_HOST = "ssh_host"
    private const val KEY_SSH_PORT = "ssh_port"
    private const val KEY_SSH_USER = "ssh_user"
    private const val KEY_WEB_PORT = "remote_web_port"
    private const val KEY_ADB_PORT = "remote_adb_port"

    private const val PRIV_KEY_NAME = "remote_fleet_ed25519"
    private const val PUB_KEY_NAME = "remote_fleet_ed25519.pub"
    private const val LAN_DISCOVERY_PORT = 18088
    private const val DISCOVERY_MAGIC = "TOOLHUB_DISCOVER"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tunnelJob: Job? = null
    private var wsTunnelJob: Job? = null
    private var heartbeatJob: Job? = null
    private var discoveryJob: Job? = null
    @Volatile
    private var activeSession: Session? = null
    @Volatile
    private var networkCallbackRegistered = false

    private val _state = MutableStateFlow(RemoteFleetState())
    val state: StateFlow<RemoteFleetState> = _state.asStateFlow()

    fun init(context: Context) {
        val appCtx = context.applicationContext
        val mac = resolveDeviceMac(appCtx)
        val serial = resolveDeviceSerial(appCtx)
        val defaultIdx = abs("$mac-$serial".hashCode()) % 1000
        val defaultWebPort = 18000 + defaultIdx
        val defaultAdbPort = 25000 + defaultIdx

        val prefs = appCtx.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasBuildDefault = BuildConfig.DEFAULT_FLEET_URL.isNotBlank()
        val serverUrl = prefs.getString(KEY_SERVER_URL, null)
            ?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_FLEET_URL
        val configuredSshHost = prefs.getString(KEY_SSH_HOST, "") ?: ""
        val derivedSshHost = if (configuredSshHost.isBlank() && serverUrl.isNotBlank()) {
            runCatching { URI(serverUrl).host ?: "" }.getOrDefault("")
        } else {
            configuredSshHost
        }
        val cfg = RemoteFleetConfig(
            enabled = if (prefs.contains(KEY_ENABLED)) prefs.getBoolean(KEY_ENABLED, false) else hasBuildDefault,
            fleetServerUrl = serverUrl,
            deviceToken = prefs.getString(KEY_DEVICE_TOKEN, null)
                ?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_DEVICE_TOKEN,
            sshHost = derivedSshHost,
            sshPort = prefs.getInt(KEY_SSH_PORT, 22),
            sshUser = prefs.getString(KEY_SSH_USER, "toolhub-relay") ?: "toolhub-relay",
            remoteWebPort = prefs.getInt(KEY_WEB_PORT, defaultWebPort),
            remoteAdbPort = prefs.getInt(KEY_ADB_PORT, defaultAdbPort),
        )

        val pubKey = ensureKeyPair(appCtx)
        _state.update {
            it.copy(
                config = cfg,
                deviceMac = mac,
                deviceSerial = serial,
                publicKeyOpenSsh = pubKey,
                tunnelStatus = if (cfg.enabled) TunnelConnectionStatus.CONNECTING else TunnelConnectionStatus.DISABLED,
            )
        }

        registerNetworkCallback(appCtx)
        startLanDiscoveryResponder(appCtx)
        if (cfg.enabled) {
            startWorkers(appCtx)
        }
    }

    fun updateConfig(context: Context, newConfig: RemoteFleetConfig) {
        val appCtx = context.applicationContext
        val sanitized = newConfig.copy(
            fleetServerUrl = newConfig.fleetServerUrl.trim().trimEnd('/'),
            deviceToken = newConfig.deviceToken.trim(),
            sshHost = newConfig.sshHost.trim(),
            sshUser = newConfig.sshUser.trim().ifBlank { "toolhub-relay" },
            sshPort = newConfig.sshPort.coerceIn(1, 65535),
            remoteWebPort = newConfig.remoteWebPort.coerceIn(1024, 65535),
            remoteAdbPort = newConfig.remoteAdbPort.coerceIn(1024, 65535),
        )
        val prefs = appCtx.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, sanitized.enabled)
            .putString(KEY_SERVER_URL, sanitized.fleetServerUrl)
            .putString(KEY_DEVICE_TOKEN, sanitized.deviceToken)
            .putString(KEY_SSH_HOST, sanitized.sshHost)
            .putInt(KEY_SSH_PORT, sanitized.sshPort)
            .putString(KEY_SSH_USER, sanitized.sshUser)
            .putInt(KEY_WEB_PORT, sanitized.remoteWebPort)
            .putInt(KEY_ADB_PORT, sanitized.remoteAdbPort)
            .apply()

        _state.update { it.copy(config = sanitized) }
        stopWorkers()
        if (sanitized.enabled) {
            startWorkers(appCtx)
        } else {
            _state.update {
                it.copy(
                    tunnelStatus = TunnelConnectionStatus.DISABLED,
                    statusMessage = null,
                )
            }
        }
    }

    fun regenerateKeyPair(context: Context): String {
        val appCtx = context.applicationContext
        File(appCtx.filesDir, PRIV_KEY_NAME).delete()
        File(appCtx.filesDir, PUB_KEY_NAME).delete()
        val pub = ensureKeyPair(appCtx)
        _state.update { it.copy(publicKeyOpenSsh = pub) }
        if (_state.value.config.enabled) {
            stopWorkers()
            startWorkers(appCtx)
        }
        return pub
    }

    private fun ensureKeyPair(appCtx: Context): String {
        val privFile = File(appCtx.filesDir, PRIV_KEY_NAME)
        val pubFile = File(appCtx.filesDir, PUB_KEY_NAME)
        val existingPub = runCatching { pubFile.readText(Charsets.UTF_8).trim() }.getOrDefault("")
        val needsRegen = !privFile.exists() || !pubFile.exists() || !existingPub.startsWith("ecdsa-sha2-")
        if (needsRegen) {
            runCatching {
                val jsch = JSch()
                val kp = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
                kp.writePrivateKey(privFile.absolutePath)
                kp.writePublicKey(pubFile.absolutePath, "toolhub-${resolveDeviceSerial(appCtx)}")
                kp.dispose()
            }.onFailure { err ->
                Log.e(TAG, "Failed to generate ECDSA keypair", err)
            }
        }
        return runCatching { pubFile.readText(Charsets.UTF_8).trim() }.getOrDefault("")
    }

    @Synchronized
    private fun startWorkers(appCtx: Context) {
        stopWorkers()
        val cfg = _state.value.config
        if (cfg.fleetServerUrl.isNotBlank()) {
            wsTunnelJob = scope.launch { runWsTunnelLoop(appCtx) }
            heartbeatJob = scope.launch { runHeartbeatLoop(appCtx) }
        }
        if (cfg.sshHost.isNotBlank()) {
            tunnelJob = scope.launch { runTunnelLoop(appCtx) }
        }
    }

    private fun ensureAdbTcpEnabled() {
        runCatching {
            val current = SystemPropertiesHelper.get("service.adb.tcp.port").trim()
            if (current.isEmpty() || current == "-1" || current == "0") {
                SystemPropertiesHelper.set("service.adb.tcp.port", "5555")
                SystemPropertiesHelper.set("ctl.restart", "adbd")
                Log.i(TAG, "Enabled TCP ADB on port 5555 for remote tunnel")
            }
        }
    }

    @Synchronized
    private fun stopWorkers() {
        tunnelJob?.cancel()
        tunnelJob = null
        wsTunnelJob?.cancel()
        wsTunnelJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { activeSession?.disconnect() }
        activeSession = null
    }

    private suspend fun runWsTunnelLoop(appCtx: Context) {
        var backoffMs = 3_000L
        while (scope.isActive && _state.value.config.enabled) {
            val cfg = _state.value.config
            if (cfg.fleetServerUrl.isBlank()) break
            if (_state.value.tunnelStatus != TunnelConnectionStatus.CONNECTED) {
                _state.update {
                    it.copy(
                        tunnelStatus = if (backoffMs == 3_000L) TunnelConnectionStatus.CONNECTING else TunnelConnectionStatus.RECONNECTING,
                        statusMessage = "WS -> ${cfg.fleetServerUrl}",
                    )
                }
            }

            val result = runCatching {
                WsReverseTunnelClient.runSession(
                    serverHttpOrWsUrl = cfg.fleetServerUrl,
                    deviceToken = cfg.deviceToken,
                    scope = scope,
                    buildHeartbeatJson = { json.encodeToString(buildPayload(appCtx, _state.value.config)) },
                    localWebPortProvider = { WebReportServer.state.value.port },
                    onConnected = {
                        backoffMs = 3_000L
                        _state.update {
                            it.copy(
                                tunnelStatus = TunnelConnectionStatus.CONNECTED,
                                lastHeartbeatOk = true,
                                lastHeartbeatTimeMillis = System.currentTimeMillis(),
                                statusMessage = "WS 隧道已连接",
                            )
                        }
                    },
                )
            }

            if (!scope.isActive || !_state.value.config.enabled) break
            val err = result.exceptionOrNull()
            if (err != null) {
                Log.w(TAG, "WS tunnel disconnected: ${err.message}")
                if (activeSession == null) {
                    _state.update {
                        it.copy(
                            tunnelStatus = TunnelConnectionStatus.ERROR,
                            statusMessage = err.message ?: "WS disconnected",
                        )
                    }
                }
            }
            val waitMs = if (err?.message?.contains("Auth fail") == true ||
                err?.message?.contains("forwarding failed") == true
            ) {
                maxOf(backoffMs, 15_000L)
            } else {
                backoffMs
            }
            delay(waitMs)
            backoffMs = (waitMs * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun runTunnelLoop(appCtx: Context) {
        var backoffMs = 3_000L
        while (scope.isActive && _state.value.config.enabled) {
            val cfg = _state.value.config
            if (cfg.sshHost.isBlank()) break
            _state.update {
                it.copy(
                    tunnelStatus = if (backoffMs == 3_000L) TunnelConnectionStatus.CONNECTING else TunnelConnectionStatus.RECONNECTING,
                    statusMessage = "${cfg.sshUser}@${cfg.sshHost}:${cfg.sshPort}",
                )
            }

            val result = runCatching {
                val privFile = File(appCtx.filesDir, PRIV_KEY_NAME)
                val jsch = JSch()
                jsch.addIdentity(privFile.absolutePath)
                val session = jsch.getSession(cfg.sshUser, cfg.sshHost, cfg.sshPort).apply {
                    setConfig("StrictHostKeyChecking", "no")
                    setConfig("PreferredAuthentications", "publickey")
                    serverAliveInterval = 15_000
                    serverAliveCountMax = 3
                    timeout = 15_000
                }
                session.connect(15_000)
                activeSession = session

                val localWebPort = WebReportServer.state.value.port
                ensureAdbTcpEnabled()
                session.setPortForwardingR("127.0.0.1", cfg.remoteWebPort, "127.0.0.1", localWebPort)
                session.setPortForwardingR("127.0.0.1", cfg.remoteAdbPort, "127.0.0.1", 5555)

                backoffMs = 3_000L
                _state.update {
                    it.copy(
                        tunnelStatus = TunnelConnectionStatus.CONNECTED,
                        statusMessage = "Web:${cfg.remoteWebPort} · ADB:${cfg.remoteAdbPort}",
                    )
                }

                while (scope.isActive && _state.value.config.enabled && session.isConnected) {
                    delay(5_000L)
                }
                session.disconnect()
            }

            activeSession = null
            if (!scope.isActive || !_state.value.config.enabled) break

            val err = result.exceptionOrNull()
            if (err != null) {
                Log.w(TAG, "SSH tunnel disconnected: ${err.message}")
                _state.update {
                    it.copy(
                        tunnelStatus = TunnelConnectionStatus.ERROR,
                        statusMessage = err.message ?: "Tunnel disconnected",
                    )
                }
            }
            val waitMs = if (err?.message?.contains("Auth fail") == true ||
                err?.message?.contains("forwarding failed") == true
            ) {
                maxOf(backoffMs, 15_000L)
            } else {
                backoffMs
            }
            delay(waitMs)
            backoffMs = (waitMs * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun runHeartbeatLoop(appCtx: Context) {
        while (scope.isActive && _state.value.config.enabled) {
            val cfg = _state.value.config
            if (cfg.fleetServerUrl.isBlank()) break
            val ok = runCatching { sendHeartbeat(appCtx, cfg) }.getOrDefault(false)
            _state.update {
                it.copy(
                    lastHeartbeatOk = ok,
                    lastHeartbeatTimeMillis = if (ok) System.currentTimeMillis() else it.lastHeartbeatTimeMillis,
                )
            }
            delay(15_000L)
        }
    }

    private fun startLanDiscoveryResponder(appCtx: Context) {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            while (scope.isActive) {
                runCatching {
                    DatagramSocket(LAN_DISCOVERY_PORT).use { socket ->
                        socket.broadcast = true
                        val buf = ByteArray(512)
                        while (scope.isActive) {
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                            if (msg.startsWith(DISCOVERY_MAGIC)) {
                                val payload = buildPayload(appCtx, _state.value.config)
                                val replyBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
                                val reply = DatagramPacket(
                                    replyBytes,
                                    replyBytes.size,
                                    packet.address,
                                    packet.port,
                                )
                                socket.send(reply)
                            }
                        }
                    }
                }.onFailure { err ->
                    Log.w(TAG, "LAN discovery responder error: ${err.message}")
                }
                delay(5_000L)
            }
        }
    }

    private fun buildPayload(appCtx: Context, cfg: RemoteFleetConfig): FleetHeartbeatPayload {
        val basic = DeviceInfoRepository.readBasicInfo(context = appCtx)
        val lanIp = NetworkDiagnosticsRepository.getActiveIpv4Address()?.takeIf { it.isNotBlank() } ?: "-"
        val thermalSample = TelemetryRepository.readThermalSample()
        val uptimeMs = SystemClock.elapsedRealtime()
        val uptimeStr = "${uptimeMs / 3_600_000}h ${(uptimeMs % 3_600_000) / 60_000}m"

        return FleetHeartbeatPayload(
            mac = _state.value.deviceMac,
            serial = _state.value.deviceSerial,
            model = basic.model,
            brand = basic.brand,
            androidVersion = basic.androidVersion,
            sdkInt = basic.sdkInt,
            socPlatform = basic.socPlatform,
            lanIp = lanIp,
            appVersion = BuildConfig.VERSION_NAME,
            webPort = WebReportServer.state.value.port,
            adbPort = 5555,
            tunnelWebPort = cfg.remoteWebPort,
            tunnelAdbPort = cfg.remoteAdbPort,
            tunnelConnected = _state.value.tunnelStatus == TunnelConnectionStatus.CONNECTED,
            stressRunning = StressTestRepository.state.value.isRunning,
            socTempC = thermalSample.socCelsius,
            uptime = uptimeStr,
            sshPubKey = _state.value.publicKeyOpenSsh.takeIf { it.isNotBlank() },
        )
    }

    private fun sendHeartbeat(appCtx: Context, cfg: RemoteFleetConfig): Boolean {
        val payload = buildPayload(appCtx, cfg)

        val url = URL("${cfg.fleetServerUrl}/api/devices/heartbeat")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            if (cfg.deviceToken.isNotBlank()) {
                setRequestProperty("X-Device-Token", cfg.deviceToken)
            }
        }
        return try {
            val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }

    private fun registerNetworkCallback(appCtx: Context) {
        if (networkCallbackRegistered) return
        val cm = appCtx.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val cfg = _state.value.config
                    if (cfg.enabled && _state.value.tunnelStatus != TunnelConnectionStatus.CONNECTED) {
                        stopWorkers()
                        startWorkers(appCtx)
                    }
                }
            })
            networkCallbackRegistered = true
        }
    }

    private fun resolveDeviceMac(ctx: Context): String {
        val candidates = listOf("eth0", "wlan0", "eth1")
        for (name in candidates) {
            val mac = runCatching {
                val f = File("/sys/class/net/$name/address")
                if (f.exists()) f.readText().trim().uppercase() else null
            }.getOrNull()
            if (!mac.isNullOrBlank() && mac != "02:00:00:00:00:00" && mac != "00:00:00:00:00:00") {
                return mac
            }
        }
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in ifaces) {
                if (ni.isLoopback) continue
                val hw = ni.hardwareAddress ?: continue
                if (hw.size == 6) {
                    val formatted = hw.joinToString(":") { "%02X".format(it) }
                    if (formatted != "02:00:00:00:00:00" && formatted != "00:00:00:00:00:00") {
                        return formatted
                    }
                }
            }
        }
        return Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?.uppercase()
            ?: "UNKNOWN"
    }

    private fun resolveDeviceSerial(ctx: Context): String {
        val prop = SystemPropertiesHelper.get("ro.serialno", "")
        if (prop.isNotBlank() && !prop.equals("unknown", ignoreCase = true)) return prop
        val buildSer = runCatching { Build.getSerial() }.getOrNull()
        if (!buildSer.isNullOrBlank() && !buildSer.equals("unknown", ignoreCase = true)) return buildSer
        return Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }
}
