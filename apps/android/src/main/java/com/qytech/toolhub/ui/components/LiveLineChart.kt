package com.qytech.toolhub.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class ChartSeries(
    val label: String,
    val color: Color,
    val values: List<Float>,
    val currentValueText: String,
    val fillArea: Boolean = true,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelemetryChartCard(
    title: String,
    icon: ImageVector,
    badgeText: String,
    badgeColor: Color,
    seriesList: List<ChartSeries>,
    minY: Float,
    maxY: Float,
    maxSlots: Int,
    yAxisTopLabel: String,
    yAxisMidLabel: String,
    yAxisBottomLabel: String,
    footerText: String,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 132.dp,
    fillAvailableHeight: Boolean = false,
    extraStatsText: String? = null,
    headerAction: (@Composable () -> Unit)? = null,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val pad = if (fillAvailableHeight) 12.dp else 16.dp
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f) }
    val linePath = remember { Path() }
    val fillPath = remember { Path() }
    val density = LocalDensity.current
    val lineStroke = remember(density) {
        Stroke(
            width = with(density) { 2.2.dp.toPx() },
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    }
    val seriesColorsKey = seriesList.map { it.color }
    val fillBrushes = remember(seriesColorsKey) {
        seriesList.map { series ->
            Brush.verticalGradient(
                colors = listOf(
                    series.color.copy(alpha = 0.24f),
                    series.color.copy(alpha = 0.02f),
                ),
            )
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .then(if (fillAvailableHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(if (fillAvailableHeight) 6.dp else 10.dp),
        ) {
            // 标题与当前值徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeColor.copy(alpha = 0.14f),
                    modifier = Modifier.size(if (fillAvailableHeight) 28.dp else 34.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.padding(if (fillAvailableHeight) 5.dp else 7.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = if (fillAvailableHeight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (!extraStatsText.isNullOrBlank()) {
                        Text(
                            text = extraStatsText,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (headerAction != null) {
                    headerAction()
                }
                StatusPill(
                    text = badgeText,
                    color = badgeColor,
                    icon = icon,
                    compact = fillAvailableHeight,
                )
            }

            // 图例
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                seriesList.forEach { series ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(series.color, CircleShape),
                        )
                        Text(
                            text = "${series.label}: ${series.currentValueText}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // 曲线绘图区 + 右侧纵轴刻度
            val chartRowModifier = if (fillAvailableHeight) {
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
            }

            Row(
                modifier = chartRowModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                    ) {
                        val width = size.width
                        val height = size.height
                        if (width <= 1f || height <= 1f) return@Canvas

                        for (step in 0..2) {
                            val ratio = step * 0.5f
                            val y = height * ratio
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(width, y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = if (step == 2) null else dashEffect,
                            )
                        }

                        val range = (maxY - minY).coerceAtLeast(0.0001f)
                        val slotCount = maxSlots.coerceAtLeast(2)
                        val slotDenom = (slotCount - 1).toFloat()

                        seriesList.forEachIndexed { seriesIdx, series ->
                            val values = series.values
                            if (values.isEmpty()) return@forEachIndexed

                            val startSlot = (slotCount - values.size).coerceAtLeast(0)
                            fun xAt(idx: Int): Float =
                                ((startSlot + idx).toFloat() / slotDenom) * width

                            fun yAt(idx: Int): Float {
                                val normY = ((values[idx] - minY) / range).coerceIn(0f, 1f)
                                return height - normY * height
                            }

                            if (values.size == 1) {
                                val pt = Offset(xAt(0), yAt(0))
                                drawCircle(
                                    color = series.color.copy(alpha = 0.28f),
                                    radius = 6.dp.toPx(),
                                    center = pt,
                                )
                                drawCircle(
                                    color = series.color,
                                    radius = 3.dp.toPx(),
                                    center = pt,
                                )
                            } else {
                                val firstX = xAt(0)
                                val firstY = yAt(0)
                                val lastIdx = values.lastIndex
                                val lastX = xAt(lastIdx)
                                val lastY = yAt(lastIdx)

                                linePath.rewind()
                                linePath.moveTo(firstX, firstY)
                                for (i in 0 until lastIdx) {
                                    val p0x = xAt(i)
                                    val p0y = yAt(i)
                                    val p1x = xAt(i + 1)
                                    val p1y = yAt(i + 1)
                                    val midX = (p0x + p1x) / 2f
                                    val midY = (p0y + p1y) / 2f
                                    if (i == 0) {
                                        linePath.lineTo(midX, midY)
                                    } else {
                                        linePath.quadraticTo(p0x, p0y, midX, midY)
                                    }
                                }
                                linePath.lineTo(lastX, lastY)

                                if (series.fillArea) {
                                    fillPath.rewind()
                                    fillPath.addPath(linePath)
                                    fillPath.lineTo(lastX, height)
                                    fillPath.lineTo(firstX, height)
                                    fillPath.close()
                                    drawPath(
                                        path = fillPath,
                                        brush = fillBrushes[seriesIdx],
                                    )
                                }

                                drawPath(
                                    path = linePath,
                                    color = series.color,
                                    style = lineStroke,
                                )

                                val last = Offset(lastX, lastY)
                                drawCircle(
                                    color = series.color.copy(alpha = 0.28f),
                                    radius = 5.5.dp.toPx(),
                                    center = last,
                                )
                                drawCircle(
                                    color = series.color,
                                    radius = 2.8.dp.toPx(),
                                    center = last,
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = yAxisTopLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = yAxisMidLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = yAxisBottomLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 底部硬件上下文信息
            Text(
                text = footerText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
