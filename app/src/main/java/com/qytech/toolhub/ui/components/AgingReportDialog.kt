package com.qytech.toolhub.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.qytech.toolhub.R
import com.qytech.toolhub.data.AgingReport
import com.qytech.toolhub.data.StressTestRepository
import com.qytech.toolhub.ui.theme.semanticColors

@Composable
fun AgingReportDialog(
    report: AgingReport,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val semantic = MaterialTheme.semanticColors
    val reportText = remember(report) {
        StressTestRepository.formatAgingReportMarkdown(report)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(min = 540.dp, max = 680.dp)
            .fillMaxWidth(0.78f)
            .padding(12.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Assignment,
                    contentDescription = null,
                    tint = if (report.isPass) semantic.success else semantic.danger,
                )
                Text(
                    text = stringResource(R.string.dlg_aging_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.weight(1f))
                StatusPill(
                    text = if (report.isPass) stringResource(R.string.aging_pass) else stringResource(
                        R.string.aging_fail
                    ),
                    color = if (report.isPass) semantic.success else semantic.danger,
                    icon = if (report.isPass) Icons.Default.CheckCircle else Icons.Default.Error,
                    compact = true,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 核心指标速览
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricBox(
                        title = stringResource(R.string.aging_duration_title),
                        value = "${report.elapsedSeconds}s",
                        subtitle = if (report.plannedDurationSec > 0) stringResource(
                            R.string.aging_target_sec_fmt,
                            report.plannedDurationSec
                        ) else stringResource(R.string.aging_continuous),
                        modifier = Modifier.weight(1f),
                    )
                    MetricBox(
                        title = stringResource(R.string.aging_ddr_err_title),
                        value = stringResource(R.string.aging_err_count_fmt, report.hardwareErrors),
                        subtitle = if (report.hardwareErrors == 0L) stringResource(R.string.aging_ddr_ok) else stringResource(
                            R.string.aging_ddr_bitflip
                        ),
                        isDanger = report.hardwareErrors > 0L,
                        modifier = Modifier.weight(1f),
                    )
                    MetricBox(
                        title = stringResource(R.string.aging_max_temp_title),
                        value = report.peakTempCelsius?.let { "${it.toInt()}°C" } ?: "-",
                        subtitle = stringResource(
                            R.string.aging_cutoff_fmt,
                            report.overheatThresholdCelsius.toInt()
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }

                // 完整报告文本预览
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        Text(
                            text = reportText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        copyTextToClipboard(
                            context,
                            context.getString(R.string.aging_report_label),
                            reportText
                        ) {
                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.aging_copy_all))
                }

                Button(
                    onClick = {
                        runCatching {
                            val result =
                                StressTestRepository.exportAgingReportToFile(context, report)
                            Toast.makeText(
                                context,
                                result.displayMessage(context),
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.save_failed_fmt, it.message ?: ""),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_export))
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
private fun MetricBox(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    isDanger: Boolean = false,
) {
    val semantic = MaterialTheme.semanticColors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isDanger) semantic.danger.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(
            alpha = 0.35f
        ),
        border = BorderStroke(
            1.dp,
            if (isDanger) semantic.danger.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isDanger) semantic.danger else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
