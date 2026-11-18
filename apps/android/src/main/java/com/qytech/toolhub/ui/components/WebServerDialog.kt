package com.qytech.toolhub.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.qytech.toolhub.R
import com.qytech.toolhub.data.NetworkDiagnosticsRepository
import com.qytech.toolhub.data.ReportStorageManager
import com.qytech.toolhub.data.StorageTarget
import com.qytech.toolhub.data.WebReportServer
import com.qytech.toolhub.ui.theme.semanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WebServerDialog(
    ipAddress: String?,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val semantic = MaterialTheme.semanticColors
    val serverState by WebReportServer.state.collectAsState()

    val noIpText = stringResource(R.string.val_no_ip)
    val effectiveIp = ipAddress?.takeIf { it.isNotBlank() && it != noIpText } ?: "127.0.0.1"
    val webUrl = "http://$effectiveIp:${serverState.port}"

    var qrBitmap by remember(webUrl) { mutableStateOf<Bitmap?>(null) }
    var usbVolumes by remember { mutableStateOf<List<StorageTarget>>(emptyList()) }
    var reportCount by remember { mutableIntStateOf(0) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(webUrl) {
        qrBitmap = withContext(Dispatchers.Default) {
            NetworkDiagnosticsRepository.generateQrBitmap(webUrl, sizePx = 320)
        }
    }

    LaunchedEffect(refreshTrigger) {
        withContext(Dispatchers.IO) {
            usbVolumes = ReportStorageManager.findUsbStorageVolumes(context)
            reportCount = ReportStorageManager.listAllReports(context).size
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(min = 520.dp, max = 660.dp)
            .fillMaxWidth(0.78f)
            .padding(12.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    tint = if (serverState.isRunning) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.dlg_web_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.weight(1f))
                StatusPill(
                    text = if (serverState.isRunning) stringResource(
                        R.string.web_status_running_fmt,
                        serverState.port
                    ) else stringResource(R.string.web_status_stopped),
                    color = if (serverState.isRunning) semantic.success else semantic.warning,
                    icon = if (serverState.isRunning) Icons.Default.CheckCircle else Icons.Default.Warning,
                    compact = true,
                )
            }
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧二维码
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = qrBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.cd_web_qr),
                                modifier = Modifier.size(144.dp),
                            )
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                    Text(
                        text = stringResource(R.string.web_qr_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 右侧状态与控制面板
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 服务开关与网址
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.web_enable_switch),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Switch(
                                    checked = serverState.isRunning,
                                    onCheckedChange = { enable ->
                                        if (enable) {
                                            WebReportServer.start(context)
                                        } else {
                                            WebReportServer.stop()
                                        }
                                    },
                                )
                            }

                            Text(
                                text = webUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = if (serverState.isPinRequired) {
                                        "${stringResource(R.string.web_pin_lock)}: ${serverState.pinCode}"
                                    } else {
                                        stringResource(R.string.web_pin_lock)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (serverState.isPinRequired) {
                                        TextButton(
                                            onClick = { WebReportServer.regeneratePin() },
                                            modifier = Modifier.height(28.dp),
                                        ) {
                                            Text(stringResource(R.string.action_refresh), fontSize = 11.sp)
                                        }
                                    }
                                    Switch(
                                        checked = serverState.isPinRequired,
                                        onCheckedChange = { WebReportServer.setPinProtection(it) },
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val webUrlLabel = context.getString(R.string.web_url_label)
                                        copyTextToClipboard(context, webUrlLabel, webUrl) {
                                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.height(34.dp),
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.web_copy_link), fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = { refreshTrigger++ },
                                    modifier = Modifier.height(34.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_refresh), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // U 盘优先存储检测状态
                    val primaryUsb = usbVolumes.firstOrNull()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (primaryUsb != null) semantic.success.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = 0.25f
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (primaryUsb != null) semantic.success.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Usb,
                                    contentDescription = null,
                                    tint = if (primaryUsb != null) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = if (primaryUsb != null) stringResource(
                                        R.string.web_usb_mounted_fmt,
                                        primaryUsb.volumeLabel
                                    ) else stringResource(R.string.web_usb_none),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (primaryUsb != null) semantic.success else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = if (primaryUsb != null) {
                                    stringResource(
                                        R.string.web_usb_dir_fmt,
                                        primaryUsb.reportsDir.absolutePath
                                    )
                                } else {
                                    stringResource(R.string.web_sdcard_dir)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                            )
                            Text(
                                text = stringResource(R.string.web_report_count_fmt, reportCount),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}
