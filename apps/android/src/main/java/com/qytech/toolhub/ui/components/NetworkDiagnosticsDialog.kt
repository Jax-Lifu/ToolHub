package com.qytech.toolhub.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qytech.toolhub.R
import com.qytech.toolhub.data.AdbPortStatus
import com.qytech.toolhub.data.NetworkDiagnosticsRepository
import com.qytech.toolhub.data.PingResult
import com.qytech.toolhub.ui.theme.semanticColors
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkDiagnosticsDialog(
    defaultGateway: String? = null,
    onDismissRequest: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val semantic = MaterialTheme.semanticColors

    var targetHost by remember { mutableStateOf(defaultGateway ?: "223.5.5.5") }
    var isPinging by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<PingResult?>(null) }
    var adbPortStatus by remember { mutableStateOf<AdbPortStatus?>(null) }
    var isCheckingPort by remember { mutableStateOf(false) }

    fun refreshPortStatus() {
        scope.launch {
            isCheckingPort = true
            adbPortStatus = NetworkDiagnosticsRepository.checkAdbTcpListening(5555, context)
            isCheckingPort = false
        }
    }

    LaunchedEffect(Unit) {
        refreshPortStatus()
    }

    fun runPing() {
        if (isPinging) return
        scope.launch {
            isPinging = true
            pingResult = NetworkDiagnosticsRepository.executePing(targetHost)
            isPinging = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Router,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.net_diag_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 1. ADB TCP 端口监听检测
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.net_adb_check),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            IconButton(
                                onClick = { refreshPortStatus() },
                                modifier = Modifier.size(24.dp),
                            ) {
                                if (isCheckingPort) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.cd_refresh_generic),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        val status = adbPortStatus
                        if (status != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = if (status.isListening) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (status.isListening) semantic.success else semantic.danger,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = status.details,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (status.isListening) semantic.success else semantic.danger,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // 2. Ping ICMP 连通性测试
                Text(
                    text = stringResource(R.string.net_ping_targets),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!defaultGateway.isNullOrBlank()) {
                        FilterChip(
                            selected = targetHost == defaultGateway,
                            onClick = { targetHost = defaultGateway },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.net_ping_gateway_fmt,
                                        defaultGateway
                                    )
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                    FilterChip(
                        selected = targetHost == "223.5.5.5",
                        onClick = { targetHost = "223.5.5.5" },
                        label = { Text(stringResource(R.string.net_ping_alidns)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                    FilterChip(
                        selected = targetHost == "8.8.8.8",
                        onClick = { targetHost = "8.8.8.8" },
                        label = { Text(stringResource(R.string.net_ping_googledns)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }

                OutlinedTextField(
                    value = targetHost,
                    onValueChange = { targetHost = it.trim() },
                    label = { Text(stringResource(R.string.net_target_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Ping 结果卡片
                val res = pingResult
                if (res != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (res.isSuccess) semantic.success.copy(alpha = 0.08f) else semantic.danger.copy(
                            alpha = 0.08f
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (res.isSuccess) semantic.success.copy(alpha = 0.4f) else semantic.danger.copy(
                                alpha = 0.4f
                            ),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = if (res.isSuccess) stringResource(
                                        R.string.net_ping_pass_fmt,
                                        res.host
                                    ) else stringResource(R.string.net_ping_fail),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (res.isSuccess) semantic.success else semantic.danger,
                                )
                                StatusPill(
                                    text = stringResource(
                                        R.string.net_ping_loss_fmt,
                                        res.lossPercent
                                    ),
                                    color = if (res.lossPercent == 0) semantic.success else if (res.lossPercent <= 25) semantic.warning else semantic.danger,
                                    compact = true,
                                )
                            }
                            if (res.avgRttMs != null) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        stringResource(R.string.net_ping_rtt_fmt),
                                        res.minRttMs ?: 0f,
                                        res.avgRttMs,
                                        res.maxRttMs ?: 0f,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            if (!res.error.isNullOrBlank()) {
                                Text(
                                    text = stringResource(
                                        R.string.net_ping_err_fmt,
                                        res.error
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = semantic.danger,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { runPing() },
                enabled = !isPinging && targetHost.isNotBlank(),
            ) {
                if (isPinging) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.net_testing))
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.net_start_ping))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}
