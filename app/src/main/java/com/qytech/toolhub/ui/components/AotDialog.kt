package com.qytech.toolhub.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.qytech.toolhub.data.AotMode
import com.qytech.toolhub.data.AotRepository
import com.qytech.toolhub.data.AppAotInfo
import com.qytech.toolhub.data.TelemetryRepository
import com.qytech.toolhub.ui.theme.semanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AotFilter(@param:androidx.annotation.StringRes val labelRes: Int) {
    THIRD_PARTY(R.string.aot_filter_third_party),
    NOT_SPEED(R.string.aot_filter_not_speed),
    SPEED_ONLY(R.string.aot_filter_speed_only),
    ALL(R.string.aot_filter_all),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AotDialog(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val semantic = MaterialTheme.semanticColors
    val scope = rememberCoroutineScope()
    val batchState by AotRepository.batchState.collectAsState()

    var isLoading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<AppAotInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AotFilter.THIRD_PARTY) }
    var compilingPkg by remember { mutableStateOf<String?>(null) }
    var lastActionMessage by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            isLoading = true
            apps = withContext(Dispatchers.IO) {
                AotRepository.loadInstalledAppsAot(context)
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    LaunchedEffect(batchState.isRunning) {
        if (!batchState.isRunning && batchState.totalCount > 0) {
            reload()
        }
    }

    val filteredApps = remember(apps, query, filter) {
        apps.filter { app ->
            val matchFilter = when (filter) {
                AotFilter.ALL -> true
                AotFilter.THIRD_PARTY -> !app.isSystemApp || app.packageName == context.packageName
                AotFilter.NOT_SPEED -> !app.isSpeedCompiled && (!app.isSystemApp || app.packageName == context.packageName)
                AotFilter.SPEED_ONLY -> app.isSpeedCompiled
            }
            val q = query.trim()
            val matchQuery = q.isEmpty() || app.appName.contains(
                q,
                ignoreCase = true
            ) || app.packageName.contains(q, ignoreCase = true)
            matchFilter && matchQuery
        }
    }

    val speedCount = remember(apps) { apps.count { it.isSpeedCompiled } }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(min = 560.dp, max = 720.dp)
            .fillMaxWidth(0.84f)
            .padding(12.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.dlg_aot_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.weight(1f))
                StatusPill(
                    text = stringResource(R.string.aot_speed_count_fmt, speedCount, apps.size),
                    color = semantic.success,
                    compact = true,
                )
                IconButton(
                    onClick = { reload() },
                    enabled = !isLoading,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh_generic),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 快捷操作行
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                compilingPkg = context.packageName
                                lastActionMessage = null
                                val res = withContext(Dispatchers.IO) {
                                    AotRepository.compilePackage(context.packageName, AotMode.SPEED)
                                }
                                compilingPkg = null
                                val msg = res.fold(
                                    onSuccess = { context.getString(R.string.aot_self_ok) },
                                    onFailure = { err ->
                                        if (err.message?.contains(
                                                "need root or shell access",
                                                ignoreCase = true
                                            ) == true
                                        ) {
                                            context.getString(R.string.aot_self_restricted)
                                        } else {
                                            context.getString(
                                                R.string.aot_compile_err_fmt,
                                                err.message ?: ""
                                            )
                                        }
                                    },
                                )
                                lastActionMessage = msg
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                reload()
                            }
                        },
                        enabled = compilingPkg == null && !batchState.isRunning,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.dlg_aot_self), fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            lastActionMessage = null
                            val targets =
                                apps.filter { (!it.isSystemApp || it.packageName == context.packageName) && !it.isSpeedCompiled }
                                    .map { it.packageName }
                            if (targets.isEmpty()) {
                                val msg = context.getString(R.string.aot_all_speed_ok)
                                lastActionMessage = msg
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            } else {
                                AotRepository.startBatchCompile(targets, AotMode.SPEED, context)
                            }
                        },
                        enabled = compilingPkg == null && !batchState.isRunning,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(
                            Icons.Default.RocketLaunch,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.dlg_aot_batch), fontSize = 12.sp)
                    }
                }

                // 批量或单应用编译进度条 / 状态提示
                val statusMsg = when {
                    compilingPkg != null -> stringResource(
                        R.string.aot_compiling_fmt,
                        compilingPkg ?: ""
                    )

                    batchState.isRunning || batchState.totalCount > 0 -> if (batchState.statusMessage == "就绪" || batchState.statusMessage == "Ready") stringResource(
                        R.string.status_ready
                    ) else batchState.statusMessage

                    else -> lastActionMessage
                }
                if (!statusMsg.isNullOrBlank()) {
                    val isErr = statusMsg.startsWith("✗") || statusMsg.startsWith("⚠")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = statusMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                compilingPkg != null || batchState.isRunning -> MaterialTheme.colorScheme.primary
                                isErr -> semantic.danger
                                else -> semantic.success
                            },
                        )
                        if (compilingPkg != null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else if (batchState.isRunning && batchState.totalCount > 0) {
                            LinearProgressIndicator(
                                progress = { batchState.currentIndex.toFloat() / batchState.totalCount.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // 搜索与筛选
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.aot_search_hint),
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AotFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(stringResource(f.labelRes), fontSize = 11.sp) },
                            modifier = Modifier.height(30.dp),
                        )
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
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
                            .height(230.dp),
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                AotAppRow(
                                    app = app,
                                    busy = compilingPkg == app.packageName || batchState.isRunning,
                                    onCompile = { mode ->
                                        scope.launch {
                                            compilingPkg = app.packageName
                                            lastActionMessage = null
                                            val res = withContext(Dispatchers.IO) {
                                                AotRepository.compilePackage(app.packageName, mode)
                                            }
                                            compilingPkg = null
                                            val msg = res.fold(
                                                onSuccess = {
                                                    context.getString(
                                                        R.string.aot_pkg_ok_fmt,
                                                        app.appName,
                                                        mode.rawValue
                                                    )
                                                },
                                                onFailure = { err ->
                                                    if (err.message?.contains(
                                                            "need root or shell access",
                                                            ignoreCase = true
                                                        ) == true
                                                    ) {
                                                        context.getString(R.string.aot_pkg_restricted)
                                                    } else {
                                                        context.getString(
                                                            R.string.aot_pkg_err_fmt,
                                                            err.message ?: ""
                                                        )
                                                    }
                                                },
                                            )
                                            lastActionMessage = msg
                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                            reload()
                                        }
                                    },
                                )
                            }
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

@Composable
private fun AotAppRow(
    app: AppAotInfo,
    busy: Boolean,
    onCompile: (AotMode) -> Unit,
) {
    val semantic = MaterialTheme.semanticColors
    val statusColor = when {
        app.isSpeedCompiled -> semantic.success
        app.status == "speed-profile" -> MaterialTheme.colorScheme.primary
        else -> semantic.warning
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(text = app.status, color = statusColor, compact = true)
                }
                val sizeStr =
                    if (app.odexSizeBytes > 0) " · odex ${TelemetryRepository.formatBytesCompact(app.odexSizeBytes)}" else ""
                Text(
                    text = "${app.packageName} (${app.abi} · ${app.reason}$sizeStr)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { onCompile(AotMode.SPEED) },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text("Speed", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { onCompile(AotMode.SPEED_PROFILE) },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text("Profile", fontSize = 11.sp)
                }
                TextButton(
                    onClick = { onCompile(AotMode.RESET) },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text(stringResource(R.string.aot_reset), fontSize = 11.sp)
                }
            }
        }
    }
}
