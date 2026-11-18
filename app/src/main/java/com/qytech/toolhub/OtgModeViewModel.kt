package com.qytech.toolhub

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qytech.toolhub.data.BootExecutionRecord
import com.qytech.toolhub.data.BootSettings
import com.qytech.toolhub.data.ConnectedUsbDevice
import com.qytech.toolhub.data.DevControlsRepository
import com.qytech.toolhub.data.DevHealthCheckItem
import com.qytech.toolhub.data.NetworkDiagnosticsRepository
import com.qytech.toolhub.data.OtgMode
import com.qytech.toolhub.data.OtgNodeInfo
import com.qytech.toolhub.data.OtgRepository
import com.qytech.toolhub.data.ToolHubPreferences
import com.qytech.toolhub.data.UsbSystemProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class OtgModeUiState(
    val nodes: List<OtgNodeInfo> = emptyList(),
    val isScanning: Boolean = true,
    val isApplyingBatch: Boolean = false,
    val adbEnabled: Boolean = false,
    val isAdbUpdating: Boolean = false,
    val wifiAdbEnabled: Boolean = false,
    val isWifiAdbUpdating: Boolean = false,
    val stayAwakeEnabled: Boolean = false,
    val isStayAwakeUpdating: Boolean = false,
    val usbProperties: UsbSystemProperties = UsbSystemProperties(),
    val connectedUsbDevices: List<ConnectedUsbDevice> = emptyList(),
    val bootSettings: BootSettings = BootSettings(),
    val bootHistory: List<BootExecutionRecord> = emptyList(),
    val healthChecks: List<DevHealthCheckItem> = emptyList(),
) {
    val controllableNodes: List<OtgNodeInfo>
        get() = nodes.filter { it.isWritable }

    val readOnlyNodes: List<OtgNodeInfo>
        get() = nodes.filter { !it.isWritable }

    val aggregateModeLabel: String
        get() {
            val controllable = controllableNodes
            if (controllable.isEmpty()) return if (nodes.isEmpty()) "No nodes" else "Read-only"
            val distinct =
                controllable.map { it.currentMode?.label ?: it.rawValue?.uppercase() ?: "Unknown" }
                    .distinct()
            return if (distinct.size == 1) distinct.first() else "Mixed (${distinct.joinToString("/")})"
        }

    fun aggregateModeLabel(context: Context): String {
        val controllable = controllableNodes
        if (controllable.isEmpty()) {
            return com.qytech.toolhub.data.AppLanguageManager.getString(
                context,
                if (nodes.isEmpty()) R.string.otg_state_no_nodes else R.string.otg_state_no_writable,
            )
        }
        val unknownText =
            com.qytech.toolhub.data.AppLanguageManager.getString(context, R.string.val_unknown)
        val distinct =
            controllable.map { it.currentMode?.label ?: it.rawValue?.uppercase() ?: unknownText }
                .distinct()
        return if (distinct.size == 1) {
            distinct.first()
        } else {
            com.qytech.toolhub.data.AppLanguageManager.getString(
                context,
                R.string.otg_state_mixed_fmt,
                distinct.joinToString("/"),
            )
        }
    }
}

class OtgModeViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val prefs = ToolHubPreferences(appContext)

    private val _uiState = MutableStateFlow(OtgModeUiState())
    val uiState: StateFlow<OtgModeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshAdbAndUsbInfo()
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshAdbAndUsbInfo()
        }
    }

    private var receiversRegistered = false

    init {
        registerObservers()
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }
            val snapshot = withContext(Dispatchers.IO) {
                val nodes = OtgRepository.discoverOtgNodes()
                val bootSettings = prefs.getBootSettings()
                Snapshot(
                    nodes = nodes,
                    adbEnabled = OtgRepository.isAdbEnabled(appContext),
                    wifiAdbEnabled = DevControlsRepository.isWifiAdbEnabled(appContext),
                    stayAwakeEnabled = DevControlsRepository.isStayAwakeEnabled(appContext),
                    usbProperties = OtgRepository.getUsbSystemProperties(),
                    connectedUsbDevices = OtgRepository.getConnectedUsbDevices(appContext),
                    bootSettings = bootSettings,
                    bootHistory = prefs.getBootHistory(),
                    healthChecks = DevControlsRepository.runHealthChecks(
                        context = appContext,
                        controllableOtgCount = nodes.count { it.isWritable },
                        totalOtgCount = nodes.size,
                        bootSettings = bootSettings,
                    ),
                )
            }
            _uiState.update {
                it.copy(
                    nodes = snapshot.nodes,
                    isScanning = false,
                    adbEnabled = snapshot.adbEnabled,
                    wifiAdbEnabled = snapshot.wifiAdbEnabled,
                    stayAwakeEnabled = snapshot.stayAwakeEnabled,
                    usbProperties = snapshot.usbProperties,
                    connectedUsbDevices = snapshot.connectedUsbDevices,
                    bootSettings = snapshot.bootSettings,
                    bootHistory = snapshot.bootHistory,
                    healthChecks = snapshot.healthChecks,
                )
            }
        }
    }

    fun refreshUsbDevicesOnly() {
        refreshAdbAndUsbInfo()
    }

    fun setNodeMode(file: File, mode: OtgMode) {
        viewModelScope.launch {
            updateNode(file) { it.copy(isLoading = true, writeError = null) }
            val syncAdb = prefs.getBootSettings().syncAdbWhenSwitchOtg

            val result = withContext(Dispatchers.IO) {
                OtgRepository.setNodeMode(appContext, file, mode, syncAdb)
            }
            val adbNow = withContext(Dispatchers.IO) { OtgRepository.isAdbEnabled(appContext) }
            val propsNow = withContext(Dispatchers.IO) { OtgRepository.getUsbSystemProperties() }
            val usbDevicesNow =
                withContext(Dispatchers.IO) { OtgRepository.getConnectedUsbDevices(appContext) }

            result.onSuccess { updatedNode ->
                updateNode(file) { updatedNode }
                _uiState.update {
                    it.copy(
                        adbEnabled = adbNow,
                        usbProperties = propsNow,
                        connectedUsbDevices = usbDevicesNow,
                    )
                }
                val actualLabel =
                    updatedNode.currentMode?.label ?: updatedNode.rawValue ?: mode.label
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_node_switched_fmt,
                        updatedNode.nodeName,
                        actualLabel
                    )
                )
                if (mode == OtgMode.HOST) {
                    ensureWifiAdbWhenHostMode()
                }
            }.onFailure { ex ->
                val unknownErr = com.qytech.toolhub.data.AppLanguageManager.getString(
                    appContext,
                    R.string.err_unknown
                )
                updateNode(file) {
                    it.copy(
                        isLoading = false,
                        writeError = com.qytech.toolhub.data.AppLanguageManager.getString(
                            appContext,
                            R.string.otg_write_err_fmt,
                            ex.message ?: unknownErr
                        )
                    )
                }
            }
        }
    }

    fun setAllControllableMode(mode: OtgMode) {
        viewModelScope.launch {
            val targets = _uiState.value.controllableNodes
            if (targets.isEmpty()) {
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_no_writable_nodes
                    )
                )
                return@launch
            }
            _uiState.update { it.copy(isApplyingBatch = true) }
            val syncAdb = prefs.getBootSettings().syncAdbWhenSwitchOtg

            val failures = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                targets.forEach { node ->
                    OtgRepository.setNodeMode(appContext, node.file, mode, syncAdb)
                        .onFailure { ex -> failures += "${node.nodeName}: ${ex.message}" }
                }
            }

            val updatedNodes = withContext(Dispatchers.IO) { OtgRepository.discoverOtgNodes() }
            val adbNow = withContext(Dispatchers.IO) { OtgRepository.isAdbEnabled(appContext) }
            val propsNow = withContext(Dispatchers.IO) { OtgRepository.getUsbSystemProperties() }
            val usbDevicesNow =
                withContext(Dispatchers.IO) { OtgRepository.getConnectedUsbDevices(appContext) }

            _uiState.update {
                it.copy(
                    nodes = updatedNodes,
                    isApplyingBatch = false,
                    adbEnabled = adbNow,
                    usbProperties = propsNow,
                    connectedUsbDevices = usbDevicesNow,
                )
            }

            if (failures.isEmpty()) {
                val suffix =
                    if (mode == OtgMode.OTG && syncAdb) com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_adb_enabled_suffix
                    ) else ""
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_batch_switched_fmt,
                        targets.size,
                        mode.label,
                        suffix
                    )
                )
                if (mode == OtgMode.HOST) {
                    ensureWifiAdbWhenHostMode()
                }
            } else {
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_batch_partial_fail_fmt,
                        failures.joinToString("; ")
                    )
                )
            }
        }
    }

    private suspend fun ensureWifiAdbWhenHostMode() {
        withContext(Dispatchers.IO) {
            val currentWifiAdb = DevControlsRepository.isWifiAdbEnabled(appContext)
            if (!currentWifiAdb) {
                DevControlsRepository.setWifiAdbEnabled(appContext, true)
                val updatedWifiAdb = DevControlsRepository.isWifiAdbEnabled(appContext)
                _uiState.update { it.copy(wifiAdbEnabled = updatedWifiAdb) }
                val activeIp = NetworkDiagnosticsRepository.getActiveIpv4Address()
                val ipText = activeIp ?: com.qytech.toolhub.data.AppLanguageManager.getString(
                    appContext,
                    R.string.otg_device_ip_placeholder
                )
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_host_wifi_adb_fmt,
                        ipText
                    )
                )
            }
        }
    }

    fun toggleAdbEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAdbUpdating = true) }
            val result = withContext(Dispatchers.IO) {
                OtgRepository.setAdbEnabled(appContext, enabled)
            }
            val propsNow = withContext(Dispatchers.IO) { OtgRepository.getUsbSystemProperties() }
            result.onSuccess { actual ->
                _uiState.update {
                    it.copy(
                        adbEnabled = actual,
                        isAdbUpdating = false,
                        usbProperties = propsNow,
                    )
                }
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        if (actual) R.string.otg_adb_on else R.string.otg_adb_off
                    )
                )
            }.onFailure { ex ->
                _uiState.update { it.copy(isAdbUpdating = false) }
                val noPerm = com.qytech.toolhub.data.AppLanguageManager.getString(
                    appContext,
                    R.string.err_no_permission
                )
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_adb_fail_fmt,
                        ex.message ?: noPerm
                    )
                )
            }
        }
    }

    fun toggleWifiAdb(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isWifiAdbUpdating = true) }
            val result = withContext(Dispatchers.IO) {
                DevControlsRepository.setWifiAdbEnabled(appContext, enabled)
            }
            val actual =
                withContext(Dispatchers.IO) { DevControlsRepository.isWifiAdbEnabled(appContext) }
            _uiState.update { it.copy(wifiAdbEnabled = actual, isWifiAdbUpdating = false) }
            result.onSuccess { on ->
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        if (on) R.string.otg_wifi_adb_on else R.string.otg_wifi_adb_off
                    )
                )
            }.onFailure { ex ->
                _events.tryEmit(
                    ex.message ?: com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_wifi_adb_fail
                    )
                )
            }
        }
    }

    fun toggleStayAwake(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isStayAwakeUpdating = true) }
            val result = withContext(Dispatchers.IO) {
                DevControlsRepository.setStayAwakeEnabled(appContext, enabled)
            }
            val actual =
                withContext(Dispatchers.IO) { DevControlsRepository.isStayAwakeEnabled(appContext) }
            _uiState.update { it.copy(stayAwakeEnabled = actual, isStayAwakeUpdating = false) }
            result.onSuccess { on ->
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        if (on) R.string.otg_stay_awake_on else R.string.otg_stay_awake_off
                    )
                )
            }.onFailure { ex ->
                val noPerm = com.qytech.toolhub.data.AppLanguageManager.getString(
                    appContext,
                    R.string.err_no_permission
                )
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_stay_awake_fail_fmt,
                        ex.message ?: noPerm
                    )
                )
            }
        }
    }

    fun rebootDevice() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                DevControlsRepository.rebootDevice(appContext)
            }
            result.onFailure { ex ->
                val noPerm = com.qytech.toolhub.data.AppLanguageManager.getString(
                    appContext,
                    R.string.err_no_permission
                )
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_reboot_fail_fmt,
                        ex.message ?: noPerm
                    )
                )
            }
        }
    }

    fun setAutoOtgOnBoot(enabled: Boolean) {
        prefs.setAutoOtgOnBoot(enabled)
        updateBootSettingsAndHealth()
        _events.tryEmit(
            com.qytech.toolhub.data.AppLanguageManager.getString(
                appContext,
                if (enabled) R.string.otg_boot_otg_on else R.string.otg_boot_otg_off
            )
        )
    }

    fun setAutoAdbOnBoot(enabled: Boolean) {
        prefs.setAutoAdbOnBoot(enabled)
        updateBootSettingsAndHealth()
        _events.tryEmit(
            com.qytech.toolhub.data.AppLanguageManager.getString(
                appContext,
                if (enabled) R.string.otg_boot_adb_on else R.string.otg_boot_adb_off
            )
        )
    }

    fun setSyncAdbWhenSwitchOtg(enabled: Boolean) {
        prefs.setSyncAdbWhenSwitchOtg(enabled)
        updateBootSettingsAndHealth()
    }

    fun applyBootPolicyNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingBatch = true) }
            val record = withContext(Dispatchers.IO) {
                OtgRepository.applyBootDefaults(
                    context = appContext,
                    trigger = "MANUAL_EXEC",
                    forceApply = false,
                )
            }
            val nodesNow = withContext(Dispatchers.IO) { OtgRepository.discoverOtgNodes() }
            val adbNow = withContext(Dispatchers.IO) { OtgRepository.isAdbEnabled(appContext) }
            val propsNow = withContext(Dispatchers.IO) { OtgRepository.getUsbSystemProperties() }
            val usbDevicesNow =
                withContext(Dispatchers.IO) { OtgRepository.getConnectedUsbDevices(appContext) }

            _uiState.update {
                it.copy(
                    nodes = nodesNow,
                    isApplyingBatch = false,
                    adbEnabled = adbNow,
                    usbProperties = propsNow,
                    connectedUsbDevices = usbDevicesNow,
                    bootHistory = prefs.getBootHistory(),
                )
            }
            _events.tryEmit(
                if (record.success) {
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_policy_ok_fmt,
                        record.nodeSummaries.joinToString(" | ")
                    )
                } else {
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        appContext,
                        R.string.otg_policy_warn_fmt,
                        record.errorMessage ?: ""
                    )
                }
            )
        }
    }

    fun clearBootHistory() {
        prefs.clearBootHistory()
        _uiState.update { it.copy(bootHistory = emptyList()) }
        _events.tryEmit(
            com.qytech.toolhub.data.AppLanguageManager.getString(
                appContext,
                R.string.otg_boot_logs_cleared
            )
        )
    }

    fun clearNodeError(file: File) {
        updateNode(file) { it.copy(writeError = null) }
    }

    private fun updateBootSettingsAndHealth() {
        val currentSettings = prefs.getBootSettings()
        val currentNodes = _uiState.value.nodes
        _uiState.update {
            it.copy(
                bootSettings = currentSettings,
                healthChecks = DevControlsRepository.runHealthChecks(
                    context = appContext,
                    controllableOtgCount = currentNodes.count { node -> node.isWritable },
                    totalOtgCount = currentNodes.size,
                    bootSettings = currentSettings,
                ),
            )
        }
    }

    private fun refreshAdbAndUsbInfo() {
        viewModelScope.launch {
            val adb = withContext(Dispatchers.IO) { OtgRepository.isAdbEnabled(appContext) }
            val wifiAdb =
                withContext(Dispatchers.IO) { DevControlsRepository.isWifiAdbEnabled(appContext) }
            val stayAwake =
                withContext(Dispatchers.IO) { DevControlsRepository.isStayAwakeEnabled(appContext) }
            val props = withContext(Dispatchers.IO) { OtgRepository.getUsbSystemProperties() }
            val devices =
                withContext(Dispatchers.IO) { OtgRepository.getConnectedUsbDevices(appContext) }
            _uiState.update {
                it.copy(
                    adbEnabled = adb,
                    wifiAdbEnabled = wifiAdb,
                    stayAwakeEnabled = stayAwake,
                    usbProperties = props,
                    connectedUsbDevices = devices,
                )
            }
        }
    }

    private fun updateNode(file: File, transform: (OtgNodeInfo) -> OtgNodeInfo) {
        _uiState.update { state ->
            state.copy(
                nodes = state.nodes.map { if (it.file == file) transform(it) else it }
            )
        }
    }

    private fun registerObservers() {
        runCatching {
            val cr = appContext.contentResolver
            cr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                false,
                settingsObserver,
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"),
                false,
                settingsObserver,
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.STAY_ON_WHILE_PLUGGED_IN),
                false,
                settingsObserver,
            )
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                appContext,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiversRegistered = true
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { appContext.contentResolver.unregisterContentObserver(settingsObserver) }
        if (receiversRegistered) {
            runCatching { appContext.unregisterReceiver(usbReceiver) }
            receiversRegistered = false
        }
    }

    private data class Snapshot(
        val nodes: List<OtgNodeInfo>,
        val adbEnabled: Boolean,
        val wifiAdbEnabled: Boolean,
        val stayAwakeEnabled: Boolean,
        val usbProperties: UsbSystemProperties,
        val connectedUsbDevices: List<ConnectedUsbDevice>,
        val bootSettings: BootSettings,
        val bootHistory: List<BootExecutionRecord>,
        val healthChecks: List<DevHealthCheckItem>,
    )
}
