package com.qytech.toolhub

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qytech.toolhub.data.AlsaCardInfo
import com.qytech.toolhub.data.AudioOutputDeviceSummary
import com.qytech.toolhub.data.AudioSystemProperties
import com.qytech.toolhub.data.BoardSysfsNode
import com.qytech.toolhub.data.CpuPolicyInfo
import com.qytech.toolhub.data.GpuDevfreqInfo
import com.qytech.toolhub.data.HardwareRepository
import com.qytech.toolhub.data.ThermalZoneInfo
import com.qytech.toolhub.data.ToolHubPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

data class HardwareUiState(
    val alsaCards: List<AlsaCardInfo> = emptyList(),
    val audioOutputs: List<AudioOutputDeviceSummary> = emptyList(),
    val audioProperties: AudioSystemProperties = AudioSystemProperties(),
    val boardNodes: List<BoardSysfsNode> = emptyList(),
    val thermalZones: List<ThermalZoneInfo> = emptyList(),
    val cpuPolicies: List<CpuPolicyInfo> = emptyList(),
    val gpuDevfreqNodes: List<GpuDevfreqInfo> = emptyList(),
    val isLoading: Boolean = true,
    val autoRefresh: Boolean = false,
)

class HardwareViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val prefs = ToolHubPreferences(appContext)

    private val _uiState = MutableStateFlow(HardwareUiState())
    val uiState: StateFlow<HardwareUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var pollingJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.alsaCards.isEmpty() && it.thermalZones.isEmpty()) }
            val snapshot = withContext(Dispatchers.IO) { loadSnapshot() }
            _uiState.update {
                it.copy(
                    alsaCards = snapshot.alsaCards,
                    audioOutputs = snapshot.audioOutputs,
                    audioProperties = snapshot.audioProperties,
                    boardNodes = snapshot.boardNodes,
                    thermalZones = snapshot.thermalZones,
                    cpuPolicies = snapshot.cpuPolicies,
                    gpuDevfreqNodes = snapshot.gpuDevfreqNodes,
                    isLoading = false,
                )
            }
        }
    }

    fun setAutoRefresh(enabled: Boolean) {
        _uiState.update { it.copy(autoRefresh = enabled) }
        pollingJob?.cancel()
        if (enabled) {
            pollingJob = viewModelScope.launch {
                while (isActive) {
                    delay(2000L.milliseconds)
                    val snapshot = withContext(Dispatchers.IO) { loadSnapshot() }
                    _uiState.update {
                        it.copy(
                            alsaCards = snapshot.alsaCards,
                            audioOutputs = snapshot.audioOutputs,
                            audioProperties = snapshot.audioProperties,
                            boardNodes = snapshot.boardNodes,
                            thermalZones = snapshot.thermalZones,
                            cpuPolicies = snapshot.cpuPolicies,
                            gpuDevfreqNodes = snapshot.gpuDevfreqNodes,
                        )
                    }
                }
            }
        }
    }

    fun setCpuGovernor(policyName: String, governor: String) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                HardwareRepository.setCpuGovernor(policyName, governor)
            }
            res.onSuccess {
                refresh()
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_ok_fmt,
                        "$policyName/scaling_governor",
                        governor,
                    )
                )
            }.onFailure { ex ->
                val noPerm = com.qytech.toolhub.data.AppLanguageManager.getString(
                    getApplication(),
                    R.string.err_no_permission
                )
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_fail_fmt,
                        "$policyName/scaling_governor",
                        ex.message ?: noPerm,
                    )
                )
            }
        }
    }

    fun setAllCpuGovernors(governor: String) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                HardwareRepository.setAllCpuGovernors(governor)
            }
            res.onSuccess { count ->
                refresh()
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_ok_fmt,
                        "CPU ($count policies)",
                        governor,
                    )
                )
            }.onFailure { ex ->
                val noPerm = com.qytech.toolhub.data.AppLanguageManager.getString(
                    getApplication(),
                    R.string.err_no_permission
                )
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_fail_fmt,
                        "CPU scaling_governor",
                        ex.message ?: noPerm,
                    )
                )
            }
        }
    }

    fun setCpuFreqBounds(policyName: String, minKHz: Long, maxKHz: Long) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                HardwareRepository.setCpuFreqBounds(policyName, minKHz, maxKHz)
            }
            res.onSuccess { updated ->
                refresh()
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_ok_fmt,
                        policyName,
                        updated.rangeText,
                    )
                )
            }.onFailure { ex ->
                val noPerm = com.qytech.toolhub.data.AppLanguageManager.getString(
                    getApplication(),
                    R.string.err_no_permission
                )
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_fail_fmt,
                        policyName,
                        ex.message ?: noPerm,
                    )
                )
            }
        }
    }

    fun setGpuGovernor(nodeName: String, governor: String) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                HardwareRepository.setGpuGovernor(nodeName, governor)
            }
            res.onSuccess {
                refresh()
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_ok_fmt,
                        "$nodeName/governor",
                        governor,
                    )
                )
            }.onFailure { ex ->
                val noPerm = com.qytech.toolhub.data.AppLanguageManager.getString(
                    getApplication(),
                    R.string.err_no_permission
                )
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_fail_fmt,
                        "$nodeName/governor",
                        ex.message ?: noPerm,
                    )
                )
            }
        }
    }

    fun setGpuFreqBounds(nodeName: String, minHz: Long, maxHz: Long) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                HardwareRepository.setGpuFreqBounds(nodeName, minHz, maxHz)
            }
            res.onSuccess { updated ->
                refresh()
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_ok_fmt,
                        nodeName,
                        updated.rangeText,
                    )
                )
            }.onFailure { ex ->
                val noPerm = com.qytech.toolhub.data.AppLanguageManager.getString(
                    getApplication(),
                    R.string.err_no_permission
                )
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_fail_fmt,
                        nodeName,
                        ex.message ?: noPerm,
                    )
                )
            }
        }
    }

    fun writeSysfsNode(node: BoardSysfsNode, newValue: String) {
        viewModelScope.launch {
            val trimmed = newValue.trim()
            if (trimmed.isEmpty()) {
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_empty
                    )
                )
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                HardwareRepository.writeSysfsNode(node.file, trimmed, node.group, node.isCustom)
            }
            result.onSuccess { updated ->
                _uiState.update { state ->
                    state.copy(
                        boardNodes = state.boardNodes.map {
                            if (it.path == updated.path) updated else it
                        }
                    )
                }
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_ok_fmt,
                        updated.name,
                        updated.value ?: trimmed,
                    )
                )
            }.onFailure { ex ->
                val noPerm = com.qytech.toolhub.data.AppLanguageManager.getString(
                    getApplication(),
                    R.string.err_no_permission
                )
                _events.tryEmit(
                    com.qytech.toolhub.data.AppLanguageManager.getString(
                        getApplication(),
                        R.string.hw_write_fail_fmt,
                        node.name,
                        ex.message ?: noPerm,
                    )
                )
            }
        }
    }

    fun addCustomSysfsPath(path: String) {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/sys/") && !trimmed.startsWith("/proc/")) {
            _events.tryEmit(
                com.qytech.toolhub.data.AppLanguageManager.getString(
                    getApplication(),
                    R.string.hw_invalid_path
                )
            )
            return
        }
        prefs.addCustomSysfsPath(trimmed)
        refresh()
        _events.tryEmit(
            com.qytech.toolhub.data.AppLanguageManager.getString(
                getApplication(),
                R.string.hw_custom_added_fmt,
                trimmed
            )
        )
    }

    fun removeCustomSysfsPath(path: String) {
        prefs.removeCustomSysfsPath(path)
        refresh()
        _events.tryEmit(
            com.qytech.toolhub.data.AppLanguageManager.getString(
                getApplication(),
                R.string.hw_custom_removed
            )
        )
    }

    private fun loadSnapshot(): Snapshot {
        val customPaths = prefs.getCustomSysfsPaths()
        return Snapshot(
            alsaCards = HardwareRepository.readAlsaCards(),
            audioOutputs = HardwareRepository.readAudioOutputDevices(appContext),
            audioProperties = HardwareRepository.readAudioProperties(),
            boardNodes = HardwareRepository.discoverBoardSysfsNodes(customPaths),
            thermalZones = HardwareRepository.readThermalZones(),
            cpuPolicies = HardwareRepository.readCpuPolicies(),
            gpuDevfreqNodes = HardwareRepository.readGpuDevfreqNodes(),
        )
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }

    private data class Snapshot(
        val alsaCards: List<AlsaCardInfo>,
        val audioOutputs: List<AudioOutputDeviceSummary>,
        val audioProperties: AudioSystemProperties,
        val boardNodes: List<BoardSysfsNode>,
        val thermalZones: List<ThermalZoneInfo>,
        val cpuPolicies: List<CpuPolicyInfo>,
        val gpuDevfreqNodes: List<GpuDevfreqInfo>,
    )
}
