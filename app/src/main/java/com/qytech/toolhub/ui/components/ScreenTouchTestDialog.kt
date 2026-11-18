package com.qytech.toolhub.ui.components

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qytech.toolhub.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

private enum class ScreenTestMode {
    DISPLAY_PATTERN,
    TP_DRAWING,
}

private enum class DisplayPattern(@param:androidx.annotation.StringRes val labelRes: Int) {
    RED(R.string.screen_pat_red),
    GREEN(R.string.screen_pat_green),
    BLUE(R.string.screen_pat_blue),
    WHITE(R.string.screen_pat_white),
    BLACK(R.string.screen_pat_black),
    GRAY(R.string.screen_pat_gray),
    COLOR_BARS(R.string.screen_pat_bars),
    GRADIENT(R.string.screen_pat_gradient),
    GRID(R.string.screen_pat_grid),
}

private data class StrokeSegment(
    val points: List<Offset>,
    val color: Color,
    val cachedPath: Path? = buildPolylinePath(points),
)

private fun buildPolylinePath(points: List<Offset>): Path? {
    if (points.size < 2) return null
    return Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
    }
}

private val POINTER_COLORS = listOf(
    Color(0xFF38BDF8),
    Color(0xFFF43F5E),
    Color(0xFFA855F7),
    Color(0xFFFACC15),
    Color(0xFFFB923C),
    Color(0xFF2DD4BF),
    Color(0xFFE879F9),
    Color(0xFF818CF8),
    Color(0xFF4ADE80),
    Color(0xFFF87171),
)

@Composable
fun ScreenTouchTestDialog(
    initialModeTp: Boolean = false,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var mode by remember {
        mutableStateOf(if (initialModeTp) ScreenTestMode.TP_DRAWING else ScreenTestMode.DISPLAY_PATTERN)
    }
    var patternIndex by remember { mutableIntStateOf(0) }
    var hideHud by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(readCurrentBrightnessRatio()) }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val oldBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        onDispose {
            if (activity != null) {
                val lp = activity.window.attributes
                lp.screenBrightness = oldBrightness
                activity.window.attributes = lp
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (mode) {
                ScreenTestMode.DISPLAY_PATTERN -> {
                    val patterns = DisplayPattern.entries
                    val currentPattern = patterns[patternIndex % patterns.size]

                    DisplayPatternCanvas(
                        pattern = currentPattern,
                        onClickNext = {
                            if (hideHud) {
                                hideHud = false
                            } else {
                                patternIndex = (patternIndex + 1) % patterns.size
                            }
                        },
                    )

                    if (!hideHud) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xCC0F172A),
                            contentColor = Color.White,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "${patternIndex + 1}/${patterns.size} · ${
                                            stringResource(
                                                currentPattern.labelRes
                                            )
                                        }",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                    )
                                    Text(
                                        text = stringResource(R.string.screen_tap_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFCBD5E1),
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Default.BrightnessHigh,
                                        contentDescription = null,
                                        tint = Color(0xFFFACC15),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Slider(
                                        value = brightness,
                                        onValueChange = { v ->
                                            brightness = v
                                            applyBrightness(context as? Activity, v)
                                        },
                                        modifier = Modifier.width(180.dp),
                                    )
                                    Text(
                                        text = "${(brightness * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White,
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = { hideHud = true },
                                        contentPadding = PaddingValues(
                                            horizontal = 10.dp,
                                            vertical = 4.dp
                                        ),
                                        modifier = Modifier.height(34.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            stringResource(R.string.screen_hide_hud),
                                            color = Color.White,
                                            fontSize = 12.sp
                                        )
                                    }

                                    Button(
                                        onClick = { mode = ScreenTestMode.TP_DRAWING },
                                        contentPadding = PaddingValues(
                                            horizontal = 12.dp,
                                            vertical = 4.dp
                                        ),
                                        modifier = Modifier.height(34.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Gesture,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            stringResource(R.string.screen_mode_tp),
                                            fontSize = 12.sp
                                        )
                                    }

                                    Button(
                                        onClick = onDismissRequest,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(
                                                0xFFDC2626
                                            )
                                        ),
                                        contentPadding = PaddingValues(
                                            horizontal = 12.dp,
                                            vertical = 4.dp
                                        ),
                                        modifier = Modifier.height(34.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            stringResource(R.string.action_close),
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                ScreenTestMode.TP_DRAWING -> {
                    TpDrawingTestCanvas(
                        onSwitchToDisplay = { mode = ScreenTestMode.DISPLAY_PATTERN },
                        onClose = onDismissRequest,
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplayPatternCanvas(
    pattern: DisplayPattern,
    onClickNext: () -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onClickNext() },
    ) {
        val w = size.width
        val h = size.height
        when (pattern) {
            DisplayPattern.RED -> drawRect(Color.Red)
            DisplayPattern.GREEN -> drawRect(Color.Green)
            DisplayPattern.BLUE -> drawRect(Color.Blue)
            DisplayPattern.WHITE -> drawRect(Color.White)
            DisplayPattern.BLACK -> drawRect(Color.Black)
            DisplayPattern.GRAY -> drawRect(Color(0xFF808080))
            DisplayPattern.COLOR_BARS -> {
                val colors = listOf(
                    Color.White,
                    Color.Yellow,
                    Color.Cyan,
                    Color.Green,
                    Color.Magenta,
                    Color.Red,
                    Color.Blue,
                    Color.Black,
                )
                val barW = w / colors.size
                colors.forEachIndexed { idx, col ->
                    drawRect(
                        color = col,
                        topLeft = Offset(idx * barW, 0f),
                        size = Size(barW + 1f, h),
                    )
                }
            }

            DisplayPattern.GRADIENT -> {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black, Color.White),
                    ),
                )
            }

            DisplayPattern.GRID -> {
                drawRect(Color.Black)
                val step = 48.dp.toPx()
                var x = 0f
                while (x <= w) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.45f),
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 1f,
                    )
                    x += step
                }
                var y = 0f
                while (y <= h) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.45f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f,
                    )
                    y += step
                }
                drawLine(Color.Red, Offset(0f, 0f), Offset(w, h), strokeWidth = 2f)
                drawLine(Color.Red, Offset(w, 0f), Offset(0f, h), strokeWidth = 2f)
                drawRect(
                    color = Color.Green,
                    topLeft = Offset(1f, 1f),
                    size = Size(w - 2f, h - 2f),
                    style = Stroke(width = 2f),
                )
            }
        }
    }
}

@Composable
private fun TpDrawingTestCanvas(
    onSwitchToDisplay: () -> Unit,
    onClose: () -> Unit,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val completedStrokes = remember { mutableStateListOf<StrokeSegment>() }
    val activeStrokes = remember { mutableStateMapOf<PointerId, List<Offset>>() }
    val activePointers = remember { mutableStateMapOf<PointerId, Offset>() }
    val visitedCells = remember { mutableStateMapOf<Pair<Int, Int>, Boolean>() }
    val reusableActivePath = remember { Path() }

    val cols = 16
    val rows = 10

    // 计算四周边框与两条对角线的目标网格集合
    val targetCells = remember(cols, rows) {
        val set = HashSet<Pair<Int, Int>>()
        for (c in 0 until cols) {
            set.add(c to 0)
            set.add(c to (rows - 1))
            val diagR1 = ((c.toFloat() / (cols - 1)) * (rows - 1)).toInt()
            val diagR2 = (rows - 1) - diagR1
            set.add(c to diagR1)
            set.add(c to diagR2)
        }
        for (r in 0 until rows) {
            set.add(0 to r)
            set.add((cols - 1) to r)
        }
        set
    }

    val hitCount = targetCells.count { visitedCells[it] == true }
    val totalTargets = targetCells.size.coerceAtLeast(1)
    val passPct = (hitCount * 100) / totalTargets

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1120))
            .onSizeChanged { canvasSize = it }
            .pointerInput(cols, rows, canvasSize) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                        val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                        val cellW = w / cols
                        val cellH = h / rows

                        event.changes.forEach { change ->
                            val pid = change.id
                            val pos = change.position
                            if (change.pressed) {
                                activePointers[pid] = pos
                                val existing = activeStrokes[pid].orEmpty()
                                activeStrokes[pid] = existing + pos

                                val c = (pos.x / cellW).toInt().coerceIn(0, cols - 1)
                                val r = (pos.y / cellH).toInt().coerceIn(0, rows - 1)
                                visitedCells[c to r] = true
                            } else {
                                activePointers.remove(pid)
                                val finished = activeStrokes.remove(pid)
                                if (!finished.isNullOrEmpty()) {
                                    val colorIdx = abs(pid.value.toInt()) % POINTER_COLORS.size
                                    completedStrokes.add(
                                        StrokeSegment(
                                            points = finished,
                                            color = POINTER_COLORS[colorIdx],
                                        )
                                    )
                                }
                            }
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    activePointers.clear()
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cellW = w / cols
            val cellH = h / rows

            // 1. 绘制边缘与对角线检测方块
            for (cell in targetCells) {
                val (c, r) = cell
                val isVisited = visitedCells[cell] == true
                val left = c * cellW
                val top = r * cellH
                drawRect(
                    color = if (isVisited) Color(0x6622C55E) else Color(0x2294A3B8),
                    topLeft = Offset(left + 1f, top + 1f),
                    size = Size(cellW - 2f, cellH - 2f),
                )
                drawRect(
                    color = if (isVisited) Color(0xFF4ADE80) else Color(0xFF334155),
                    topLeft = Offset(left + 1f, top + 1f),
                    size = Size(cellW - 2f, cellH - 2f),
                    style = Stroke(width = 1.2f),
                )
            }

            // 2. 绘制已完成的多指轨迹（直接复用预构建的 Path）
            completedStrokes.forEach { seg ->
                val cached = seg.cachedPath
                if (cached != null) {
                    drawPath(
                        path = cached,
                        color = seg.color,
                        style = Stroke(
                            width = 5f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                } else if (seg.points.size == 1) {
                    drawCircle(color = seg.color, radius = 4f, center = seg.points.first())
                }
            }

            // 3. 绘制正在触摸的实时轨迹与十字准星（复用 reusableActivePath）
            activeStrokes.forEach { (pid, pts) ->
                val col = POINTER_COLORS[abs(pid.value.toInt()) % POINTER_COLORS.size]
                drawPolyline(pts, col, reusableActivePath)
            }

            activePointers.forEach { (pid, pos) ->
                val col = POINTER_COLORS[abs(pid.value.toInt()) % POINTER_COLORS.size]
                drawLine(
                    col.copy(alpha = 0.65f),
                    Offset(pos.x, 0f),
                    Offset(pos.x, h),
                    strokeWidth = 1.5f
                )
                drawLine(
                    col.copy(alpha = 0.65f),
                    Offset(0f, pos.y),
                    Offset(w, pos.y),
                    strokeWidth = 1.5f
                )
                drawCircle(col, radius = 22f, center = pos, style = Stroke(width = 3f))
                drawCircle(Color.White, radius = 5f, center = pos)
            }
        }

        // 顶部居中状态与控制栏
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xCC0F172A),
            contentColor = Color.White,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val firstPtr = activePointers.values.firstOrNull()
                val coordStr = if (firstPtr != null) {
                    "X=${firstPtr.x.toInt()} Y=${firstPtr.y.toInt()}"
                } else {
                    "-"
                }
                Text(
                    text = stringResource(
                        R.string.tp_status_fmt,
                        hitCount,
                        totalTargets,
                        passPct,
                        activePointers.size,
                        coordStr,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (passPct == 100) Color(0xFF4ADE80) else Color.White,
                )

                IconButton(
                    onClick = {
                        completedStrokes.clear()
                        activeStrokes.clear()
                        activePointers.clear()
                        visitedCells.clear()
                    },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.action_clear),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                OutlinedButton(
                    onClick = onSwitchToDisplay,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.screen_mode_display),
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }

                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_close), fontSize = 11.sp)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolyline(
    points: List<Offset>,
    color: Color,
    reusablePath: Path,
) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color = color, radius = 4f, center = points.first())
        return
    }
    reusablePath.rewind()
    reusablePath.moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        reusablePath.lineTo(points[i].x, points[i].y)
    }
    drawPath(
        path = reusablePath,
        color = color,
        style = Stroke(
            width = 5f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun readCurrentBrightnessRatio(): Float {
    val blRoot = File("/sys/class/backlight")
    val dir = blRoot.listFiles()?.firstOrNull() ?: return 0.8f
    val cur = runCatching { File(dir, "brightness").readText().trim().toFloat() }.getOrNull()
        ?: return 0.8f
    val max =
        runCatching { File(dir, "max_brightness").readText().trim().toFloat() }.getOrNull() ?: 255f
    if (max <= 0f) return 0.8f
    return (cur / max).coerceIn(0.05f, 1f)
}

private fun applyBrightness(activity: Activity?, ratio: Float) {
    val clamped = ratio.coerceIn(0.05f, 1f)
    if (activity != null) {
        val lp = activity.window.attributes
        lp.screenBrightness = clamped
        activity.window.attributes = lp
    }
    runCatching {
        val blRoot = File("/sys/class/backlight")
        blRoot.listFiles()?.forEach { dir ->
            val max =
                runCatching { File(dir, "max_brightness").readText().trim().toInt() }.getOrDefault(
                    255
                )
            val target = (max * clamped).toInt().coerceIn(1, max)
            val brFile = File(dir, "brightness")
            if (brFile.canWrite()) {
                FileOutputStream(brFile).use {
                    it.write(target.toString().toByteArray(Charsets.UTF_8))
                    it.flush()
                }
            }
        }
    }
}
