package com.qytech.toolhub.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.qytech.toolhub.R
import com.qytech.toolhub.data.AlsaCardInfo
import com.qytech.toolhub.data.AudioToneGenerator
import com.qytech.toolhub.data.HardwareRepository
import com.qytech.toolhub.data.ToneChannelMode
import com.qytech.toolhub.data.ToneWaveform
import com.qytech.toolhub.ui.theme.semanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioToneDialog(
    onDismissRequest: () -> Unit,
) {
    val semantic = MaterialTheme.semanticColors
    val toneState by AudioToneGenerator.state.collectAsState()
    var alsaCards by remember { mutableStateOf<List<AlsaCardInfo>>(emptyList()) }

    LaunchedEffect(toneState.isPlaying) {
        while (isActive) {
            alsaCards = withContext(Dispatchers.IO) {
                HardwareRepository.readAlsaCards()
            }
            delay((if (toneState.isPlaying) 800L else 2000L).milliseconds)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            AudioToneGenerator.stop()
        }
    }

    val rateShort = if (toneState.sampleRateHz % 1000 == 0) {
        "${toneState.sampleRateHz / 1000} kHz"
    } else {
        "${toneState.sampleRateHz / 1000.0} kHz"
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = 580.dp)
            .fillMaxWidth(0.92f)
            .padding(vertical = 12.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tone_dlg_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.tone_dlg_sub),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                StatusPill(
                    text = if (toneState.isPlaying) "$rateShort ON" else "IDLE",
                    color = if (toneState.isPlaying) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    compact = true,
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
                // 1. 信号波形选择
                Text(
                    text = stringResource(R.string.tone_sec_waveform),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ToneWaveform.entries.forEach { wf ->
                        FilterChip(
                            selected = toneState.waveform == wf,
                            onClick = { AudioToneGenerator.updateConfig(waveform = wf) },
                            label = { Text(stringResource(wf.labelRes), fontSize = 11.5.sp) },
                            modifier = Modifier.height(30.dp),
                        )
                    }
                }

                // 2. 声道模式
                Text(
                    text = stringResource(R.string.tone_sec_channel),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ToneChannelMode.entries.forEach { ch ->
                        FilterChip(
                            selected = toneState.channelMode == ch,
                            onClick = { AudioToneGenerator.updateConfig(channelMode = ch) },
                            label = { Text(stringResource(ch.labelRes), fontSize = 11.5.sp) },
                            modifier = Modifier.height(30.dp),
                        )
                    }
                }

                // 3. 采样率
                Text(
                    text = stringResource(R.string.tone_sec_rate),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AudioToneGenerator.SUPPORTED_SAMPLE_RATES.forEach { rate ->
                        val label =
                            if (rate % 1000 == 0) "${rate / 1000} kHz" else "${rate / 1000.0} kHz"
                        FilterChip(
                            selected = toneState.sampleRateHz == rate,
                            onClick = { AudioToneGenerator.updateConfig(sampleRateHz = rate) },
                            label = {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            modifier = Modifier.height(28.dp),
                        )
                    }
                }

                // 4. 电平衰减 (dBFS)
                Text(
                    text = stringResource(R.string.tone_sec_level),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AudioToneGenerator.SUPPORTED_DB_LEVELS.forEach { db ->
                        FilterChip(
                            selected = toneState.attenuationDb == db,
                            onClick = { AudioToneGenerator.updateConfig(attenuationDb = db) },
                            label = {
                                Text(
                                    "$db dBFS",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            modifier = Modifier.height(28.dp),
                        )
                    }
                }

                // 5. 实时 ALSA hw_params 环路核验面板
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.tone_alsa_verify_title),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        val activeStreams = alsaCards.flatMap { card ->
                            card.streams.filter { it.isActive }.map { stream ->
                                "card${card.index} [${card.id}] ${stream.pcmId}: ${stream.format ?: "-"} · ${stream.rate ?: "-"} · ${stream.channels ?: "-"}ch"
                            }
                        }
                        if (activeStreams.isEmpty()) {
                            Text(
                                text = stringResource(R.string.tone_alsa_idle),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            activeStreams.forEach { line ->
                                Text(
                                    text = "● $line",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = semantic.success,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (toneState.isPlaying) {
                    Button(
                        onClick = { AudioToneGenerator.stop() },
                        colors = ButtonDefaults.buttonColors(containerColor = semantic.danger),
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.tone_btn_stop))
                    }
                } else {
                    Button(
                        onClick = { AudioToneGenerator.start() },
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.tone_btn_start))
                    }
                }
                TextButton(
                    onClick = {
                        AudioToneGenerator.stop()
                        onDismissRequest()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.action_close))
                }
            }
        },
    )
}
