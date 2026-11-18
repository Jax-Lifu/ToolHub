package com.qytech.toolhub.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.qytech.toolhub.R
import com.qytech.toolhub.data.AnrEntry
import com.qytech.toolhub.data.BootReasonInfo
import com.qytech.toolhub.data.BugreportManagerHelper
import com.qytech.toolhub.data.CrashDiagnosticsRepository
import com.qytech.toolhub.data.CrashDiagnosticsSummary
import com.qytech.toolhub.data.TombstoneEntry
import com.qytech.toolhub.ui.theme.semanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CrashTab(@param:androidx.annotation.StringRes val titleRes: Int) {
    TOMBSTONE(R.string.crash_tab_tombstone),
    ANR(R.string.crash_tab_anr),
    BOOT_REASON(R.string.crash_tab_boot),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashDiagnosticsDialog(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val semantic = MaterialTheme.semanticColors
    val scope = rememberCoroutineScope()
    val bugreportState by BugreportManagerHelper.state.collectAsState()

    var isLoading by remember { mutableStateOf(true) }
    var summary by remember { mutableStateOf(CrashDiagnosticsSummary()) }
    var selectedTab by remember { mutableStateOf(CrashTab.TOMBSTONE) }
    var expandedTombstoneIndex by remember { mutableIntStateOf(-1) }
    var expandedAnrIndex by remember { mutableIntStateOf(-1) }

    fun reload() {
        scope.launch {
            isLoading = true
            val res = withContext(Dispatchers.IO) {
                CrashDiagnosticsRepository.loadDiagnostics(context)
            }
            summary = res
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(min = 540.dp, max = 680.dp)
            .fillMaxWidth(0.82f)
            .padding(12.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = if (summary.totalIssuesCount > 0 || summary.bootReason?.isAbnormalReboot == true) {
                        semantic.danger
                    } else {
                        semantic.success
                    },
                )
                Text(
                    text = stringResource(R.string.dlg_crash_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (summary.bootReason?.isAbnormalReboot == true) {
                    StatusPill(
                        text = stringResource(R.string.crash_abnormal_reboot),
                        color = semantic.danger,
                        icon = Icons.Default.Warning,
                        compact = true,
                    )
                } else if (summary.totalIssuesCount > 0) {
                    StatusPill(
                        text = stringResource(
                            R.string.crash_issues_count_fmt,
                            summary.totalIssuesCount
                        ),
                        color = semantic.warning,
                        icon = Icons.Default.Warning,
                        compact = true,
                    )
                } else {
                    StatusPill(
                        text = stringResource(R.string.crash_system_stable),
                        color = semantic.success,
                        icon = Icons.Default.CheckCircle,
                        compact = true,
                    )
                }
                IconButton(
                    onClick = { reload() },
                    enabled = !isLoading,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh_crash),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 顶部分类切换 Tabs
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    CrashTab.entries.forEachIndexed { index, tab ->
                        val badge = when (tab) {
                            CrashTab.TOMBSTONE -> " (${summary.tombstones.size})"
                            CrashTab.ANR -> " (${summary.anrs.size})"
                            CrashTab.BOOT_REASON -> if (summary.bootReason?.isAbnormalReboot == true) " (!)" else ""
                        }
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = CrashTab.entries.size
                            ),
                            onClick = { selectedTab = tab },
                            selected = selectedTab == tab,
                            label = {
                                Text(
                                    text = "${stringResource(tab.titleRes)}$badge",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when (selectedTab) {
                                CrashTab.TOMBSTONE -> TombstoneListContent(
                                    tombstones = summary.tombstones,
                                    expandedIndex = expandedTombstoneIndex,
                                    onToggleExpand = { i ->
                                        expandedTombstoneIndex =
                                            if (expandedTombstoneIndex == i) -1 else i
                                    },
                                    onCopy = { label, text ->
                                        copyTextToClipboard(context, label, text) {
                                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )

                                CrashTab.ANR -> AnrListContent(
                                    anrs = summary.anrs,
                                    expandedIndex = expandedAnrIndex,
                                    onToggleExpand = { i ->
                                        expandedAnrIndex = if (expandedAnrIndex == i) -1 else i
                                    },
                                    onCopy = { label, text ->
                                        copyTextToClipboard(context, label, text) {
                                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )

                                CrashTab.BOOT_REASON -> BootReasonContent(
                                    boot = summary.bootReason,
                                    onCopy = { label, text ->
                                        copyTextToClipboard(context, label, text) {
                                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (bugreportState.isGenerating || bugreportState.lastGeneratedFile != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val statusMsg =
                            if (bugreportState.statusMessage == "就绪" || bugreportState.statusMessage == "Ready") {
                                stringResource(R.string.status_ready)
                            } else {
                                bugreportState.statusMessage
                            }
                        Text(
                            text = statusMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (bugreportState.isGenerating) MaterialTheme.colorScheme.primary else semantic.success,
                        )
                        if (bugreportState.isGenerating) {
                            if (bugreportState.isIndeterminate) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(
                                    progress = {
                                        (bugreportState.progressPercent / 100f).coerceIn(
                                            0f,
                                            1f
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val result =
                                CrashDiagnosticsRepository.exportDiagnosticsReport(context, summary)
                            Toast.makeText(
                                context,
                                result.displayMessage(context),
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.crash_export_err_fmt, it.message ?: ""),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !isLoading,
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.dlg_crash_export_summary))
                }

                Button(
                    onClick = { BugreportManagerHelper.startSystemBugreport(context) },
                    enabled = !bugreportState.isGenerating,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    if (bugreportState.isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            Icons.Default.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (bugreportState.isGenerating) {
                            if (bugreportState.isIndeterminate) "ZIP (${bugreportState.elapsedSeconds}s)"
                            else "ZIP ${bugreportState.progressPercent.toInt()}% (${bugreportState.elapsedSeconds}s)"
                        } else {
                            stringResource(R.string.dlg_crash_gen_zip)
                        }
                    )
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

@Composable
private fun TombstoneListContent(
    tombstones: List<TombstoneEntry>,
    expandedIndex: Int,
    onToggleExpand: (Int) -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val semantic = MaterialTheme.semanticColors

    if (tombstones.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = semantic.success,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.crash_no_tombstone),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.crash_native_stable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    tombstones.forEachIndexed { index, item ->
        val isExpanded = expandedIndex == index
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, semantic.danger.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = semantic.danger,
                    )
                    Text(
                        text = "· ${item.timestampText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { onCopy(item.fileName, item.fullExcerpt) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(stringResource(R.string.action_copy), fontSize = 11.sp)
                    }
                }

                val procDisplay =
                    if (item.processName == "未知进程" || item.processName == "未知应用" || item.processName == "unknown") {
                        stringResource(R.string.unknown_app)
                    } else {
                        item.processName
                    }
                Text(
                    text = stringResource(
                        R.string.crash_proc_fmt,
                        procDisplay,
                        item.pid,
                        item.tid
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.crash_signal_fmt, item.signalText),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = semantic.danger,
                )

                if (!item.topStackFrame.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.crash_top_stack_fmt, item.topStackFrame),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExpand(index) }
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (isExpanded) stringResource(R.string.crash_collapse_stack) else stringResource(
                            R.string.crash_expand_stack
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        Text(
                            text = item.fullExcerpt,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                            ),
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnrListContent(
    anrs: List<AnrEntry>,
    expandedIndex: Int,
    onToggleExpand: (Int) -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val semantic = MaterialTheme.semanticColors

    if (anrs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = semantic.success,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.crash_no_anr),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.crash_anr_stable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    anrs.forEachIndexed { index, item ->
        val isExpanded = expandedIndex == index
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, semantic.warning.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "[${item.tag}]",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = semantic.warning,
                    )
                    val anrProcDisplay =
                        if (item.processName == "未知进程" || item.processName == "未知应用" || item.processName == "unknown") {
                            stringResource(R.string.unknown_app)
                        } else {
                            item.processName
                        }
                    Text(
                        text = anrProcDisplay,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = item.timestampText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { onCopy(item.processName, item.excerpt) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(stringResource(R.string.action_copy), fontSize = 11.sp)
                    }
                }

                Text(
                    text = stringResource(R.string.crash_reason_fmt, item.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExpand(index) }
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (isExpanded) stringResource(R.string.crash_collapse_trace) else stringResource(
                            R.string.crash_expand_trace
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        Text(
                            text = item.excerpt,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                            ),
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BootReasonContent(
    boot: BootReasonInfo?,
    onCopy: (String, String) -> Unit,
) {
    val semantic = MaterialTheme.semanticColors

    if (boot == null) {
        Text(
            stringResource(R.string.crash_no_boot_info),
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 重启原因属性卡片
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                if (boot.isAbnormalReboot) semantic.danger else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.crash_boot_diag),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    StatusPill(
                        text = if (boot.isAbnormalReboot) stringResource(R.string.crash_boot_panic) else stringResource(
                            R.string.crash_boot_normal
                        ),
                        color = if (boot.isAbnormalReboot) semantic.danger else semantic.success,
                        icon = if (boot.isAbnormalReboot) Icons.Default.Warning else Icons.Default.CheckCircle,
                        compact = true,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "sys.boot.reason",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        boot.sysBootReason,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "ro.boot.bootreason",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        boot.roBootReason,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.crash_pstore_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (boot.pstoreAvailable) stringResource(R.string.crash_pstore_found) else stringResource(
                            R.string.crash_pstore_none
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (boot.pstoreAvailable) semantic.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 内核崩溃日志上下文
        if (!boot.lastKernelExcerpt.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, semantic.danger.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.crash_kernel_excerpt_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = semantic.danger,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        val kernelLogLabel = stringResource(R.string.crash_kernel_log_label)
                        TextButton(
                            onClick = { onCopy(kernelLogLabel, boot.lastKernelExcerpt) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(stringResource(R.string.crash_copy_kernel_log), fontSize = 11.sp)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = boot.lastKernelExcerpt,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                            ),
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = stringResource(R.string.crash_pstore_note_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.crash_pstore_note_body),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}
