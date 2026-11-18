package com.qytech.toolhub

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qytech.toolhub.data.DeviceBasicInfo
import com.qytech.toolhub.data.DeviceInfoRepository
import com.qytech.toolhub.data.DeviceRuntimeInfo
import com.qytech.toolhub.data.EthernetStatusInfo
import com.qytech.toolhub.data.NetworkSnapshot
import com.qytech.toolhub.data.WifiStatusInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class DeviceInfoUiState(
    val basicInfo: DeviceBasicInfo? = null,
    val runtimeInfo: DeviceRuntimeInfo? = null,
    val wifiInfo: WifiStatusInfo? = null,
    val ethernetList: List<EthernetStatusInfo> = emptyList(),
    val isLoading: Boolean = true,
    val noNetworkLabel: String = "",
) {
    val primaryIpSummary: String
        get() {
            val ethIp = ethernetList.firstOrNull { it.isConnected }
                ?.let { "${it.interfaceName}: ${it.ipAddress}" }
            val wifiIp = wifiInfo?.takeIf { it.isConnected }?.let { "Wi-Fi: ${it.ipAddress}" }
            return ethIp ?: wifiIp ?: noNetworkLabel.ifBlank { "Not Connected" }
        }

    val primaryIpAddress: String?
        get() = ethernetList.firstOrNull { it.isConnected }?.ipAddress
            ?: wifiInfo?.takeIf { it.isConnected }?.ipAddress
}

class DeviceInfoViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val networkSnapshots = ConcurrentHashMap<Network, NetworkSnapshot>()

    private val _uiState = MutableStateFlow(DeviceInfoUiState())
    val uiState: StateFlow<DeviceInfoUiState> = _uiState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback(
        FLAG_INCLUDE_LOCATION_INFO
    ) {
        override fun onAvailable(network: Network) {
            updateNetworkSnapshot(network, null, null)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            updateNetworkSnapshot(network, networkCapabilities, null)
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties,
        ) {
            updateNetworkSnapshot(network, null, linkProperties)
        }

        override fun onLost(network: Network) {
            networkSnapshots.remove(network)
            recomputeNetworkState()
        }
    }

    private var callbackRegistered = false

    init {
        registerNetworkMonitor()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.basicInfo == null) }
            val result = withContext(Dispatchers.IO) {
                seedActiveNetworkSnapshot()
                val basic = DeviceInfoRepository.readBasicInfo(context = appContext)
                val runtime = DeviceInfoRepository.readRuntimeInfo(appContext)
                val snapshots = networkSnapshots.values.toList()
                val wifi = DeviceInfoRepository.buildWifiStatus(snapshots, appContext)
                val ethernet = DeviceInfoRepository.buildEthernetStatuses(snapshots, appContext)
                Quadruple(basic, runtime, wifi, ethernet)
            }
            val noNet = com.qytech.toolhub.data.AppLanguageManager.getString(
                appContext,
                R.string.val_no_network
            )
            _uiState.update {
                it.copy(
                    basicInfo = result.first,
                    runtimeInfo = result.second,
                    wifiInfo = result.third,
                    ethernetList = result.fourth,
                    isLoading = false,
                    noNetworkLabel = noNet,
                )
            }
        }
    }

    private fun seedActiveNetworkSnapshot() {
        val cm = connectivityManager ?: return
        val active = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(active)
        val lp = cm.getLinkProperties(active)
        if (caps != null || lp != null) {
            networkSnapshots[active] = NetworkSnapshot(active, caps, lp)
        }
    }

    private fun updateNetworkSnapshot(
        network: Network,
        capabilities: NetworkCapabilities?,
        linkProperties: LinkProperties?,
    ) {
        val cm = connectivityManager
        val existing = networkSnapshots[network]
        val resolvedCaps = capabilities
            ?: existing?.capabilities
            ?: runCatching { cm?.getNetworkCapabilities(network) }.getOrNull()
        val resolvedLp = linkProperties
            ?: existing?.linkProperties
            ?: runCatching { cm?.getLinkProperties(network) }.getOrNull()

        networkSnapshots[network] = NetworkSnapshot(
            network = network,
            capabilities = resolvedCaps,
            linkProperties = resolvedLp,
        )
        recomputeNetworkState()
    }

    private fun recomputeNetworkState() {
        viewModelScope.launch {
            val (wifi, ethernet) = withContext(Dispatchers.IO) {
                val snapshots = networkSnapshots.values.toList()
                DeviceInfoRepository.buildWifiStatus(snapshots) to
                        DeviceInfoRepository.buildEthernetStatuses(snapshots)
            }
            _uiState.update {
                it.copy(
                    wifiInfo = wifi,
                    ethernetList = ethernet,
                )
            }
        }
    }

    private fun registerNetworkMonitor() {
        val cm = connectivityManager ?: return
        runCatching {
            val request = NetworkRequest.Builder().build()
            cm.registerNetworkCallback(request, networkCallback)
            callbackRegistered = true
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (callbackRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
            callbackRegistered = false
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
