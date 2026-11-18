package com.qytech.toolhub.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.qytech.toolhub.R
import com.qytech.toolhub.data.SystemPropertiesHelper
import com.qytech.toolhub.ui.theme.semanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PropFilterCategory(@param:androidx.annotation.StringRes val labelRes: Int) {
    ALL(R.string.prop_cat_all),
    WRITABLE(R.string.prop_cat_writable),
    PERSIST(R.string.prop_cat_persist),
    VENDOR(R.string.prop_cat_vendor),
    RO(R.string.prop_cat_ro),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SysPropBrowserDialog(
    onCopy: (String, String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val semantic = MaterialTheme.semanticColors
    val scope = rememberCoroutineScope()

    var allProps by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(PropFilterCategory.ALL) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    var editingKey by remember { mutableStateOf<String?>(null) }
    var editingVal by remember { mutableStateOf("") }
    var isCustomKey by remember { mutableStateOf(false) }

    fun reloadProps() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                SystemPropertiesHelper.readAll(forceRefresh = true)
            }
            allProps = loaded
        }
    }

    LaunchedEffect(Unit) {
        reloadProps()
    }

    val filteredEntries = remember(allProps, searchQuery, category) {
        val q = searchQuery.trim().lowercase()
        allProps.entries.filter { (k, v) ->
            val matchCat = when (category) {
                PropFilterCategory.ALL -> true
                PropFilterCategory.WRITABLE -> SystemPropertiesHelper.isWritableKey(k)
                PropFilterCategory.PERSIST -> k.startsWith("persist.")
                PropFilterCategory.VENDOR -> k.startsWith("vendor.") || k.startsWith("audio.") || k.startsWith(
                    "sys."
                )

                PropFilterCategory.RO -> k.startsWith("ro.")
            }
            val matchQuery = q.isEmpty() || k.lowercase().contains(q) || v.lowercase().contains(q)
            matchCat && matchQuery
        }.sortedBy { it.key }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .padding(vertical = 12.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsSuggest,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.prop_dlg_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(
                                R.string.prop_dlg_sub_fmt,
                                filteredEntries.size,
                                allProps.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            isCustomKey = true
                            editingKey = "persist.sys."
                            editingVal = ""
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("setprop", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = { reloadProps() }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.prop_search_hint),
                            fontSize = 12.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 分类筛选与快捷调试开关
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PropFilterCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(stringResource(cat.labelRes), fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp),
                        )
                    }
                }

                if (!statusBanner.isNullOrBlank()) {
                    Text(
                        text = statusBanner.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (statusBanner?.startsWith("✗") == true) semantic.danger else semantic.success,
                    )
                }

                // 属性列表
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 380.dp),
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredEntries, key = { it.key }) { (k, v) ->
                            val writable = SystemPropertiesHelper.isWritableKey(k)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        if (writable) {
                                            isCustomKey = false
                                            editingKey = k
                                            editingVal = v
                                        } else {
                                            onCopy(k, "$k=$v")
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatusPill(
                                    text = if (writable) "RW" else "RO",
                                    color = if (writable) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                    compact = true,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = k,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = v.ifEmpty { "<empty>" },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (writable) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(
                                    onClick = { onCopy(k, "$k=$v") },
                                    modifier = Modifier.size(26.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.action_copy),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                thickness = 0.5.dp,
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

    if (editingKey != null) {
        var keyInput by remember(editingKey) { mutableStateOf(editingKey.orEmpty()) }
        var valInput by remember(editingVal) { mutableStateOf(editingVal) }

        AlertDialog(
            onDismissRequest = { editingKey = null },
            title = {
                Text(
                    stringResource(R.string.prop_edit_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { if (isCustomKey) keyInput = it },
                        readOnly = !isCustomKey,
                        label = { Text("Property Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = valInput,
                        onValueChange = { valInput = it },
                        label = { Text("Property Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val k = keyInput.trim()
                        val v = valInput.trim()
                        editingKey = null
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                SystemPropertiesHelper.set(k, v)
                            }
                            res.onSuccess { actual ->
                                statusBanner = "✓ $k = [$actual]"
                                reloadProps()
                            }.onFailure { err ->
                                statusBanner = "✗ $k: ${err.message}"
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.audio_confirm_write))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingKey = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
