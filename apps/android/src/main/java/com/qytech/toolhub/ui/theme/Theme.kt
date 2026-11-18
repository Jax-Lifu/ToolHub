package com.qytech.toolhub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class SemanticColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
)

private val LightSemanticColors = SemanticColors(
    success = StatusEmeraldLight,
    warning = StatusAmberLight,
    danger = StatusRoseLight,
    info = StatusInfoLight,
)

private val DarkSemanticColors = SemanticColors(
    success = StatusEmeraldDark,
    warning = StatusAmberDark,
    danger = StatusRoseDark,
    info = StatusInfoDark,
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }

private val DarkColorScheme = darkColorScheme(
    primary = SkyPrimaryDark,
    onPrimary = SkyOnPrimaryDark,
    primaryContainer = SkyContainerDark,
    onPrimaryContainer = SkyOnContainerDark,
    // 交互高亮统一使用 Cobalt/Sky 蓝色系，避免导航与状态绿混淆
    secondary = SkyPrimaryDark,
    onSecondary = SkyOnPrimaryDark,
    secondaryContainer = SkyContainerDark,
    onSecondaryContainer = SkyOnContainerDark,
    tertiary = StatusAmberDark,
    background = ObsidianBackgroundDark,
    onBackground = ObsidianOnSurfaceDark,
    surface = ObsidianSurfaceDark,
    onSurface = ObsidianOnSurfaceDark,
    surfaceVariant = ObsidianSurfaceVariantDark,
    onSurfaceVariant = ObsidianOnSurfaceVariantDark,
    surfaceContainerLow = ObsidianSurfaceDark,
    surfaceContainer = ObsidianSurfaceDark,
    surfaceContainerHigh = ObsidianSurfaceVariantDark,
    outline = ObsidianOutlineDark,
    outlineVariant = ObsidianOutlineDark,
    error = StatusRoseDark,
)

private val LightColorScheme = lightColorScheme(
    primary = CobaltPrimaryLight,
    onPrimary = CobaltOnPrimaryLight,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = CobaltOnContainerLight,
    secondary = CobaltPrimaryLight,
    onSecondary = CobaltOnPrimaryLight,
    secondaryContainer = CobaltContainerLight,
    onSecondaryContainer = CobaltOnContainerLight,
    tertiary = StatusAmberLight,
    background = SlateBackgroundLight,
    onBackground = SlateOnSurfaceLight,
    surface = SlateSurfaceLight,
    onSurface = SlateOnSurfaceLight,
    surfaceVariant = SlateSurfaceVariantLight,
    onSurfaceVariant = SlateOnSurfaceVariantLight,
    surfaceContainerLow = SlateSurfaceLight,
    surfaceContainer = SlateSurfaceLight,
    surfaceContainerHigh = SlateSurfaceVariantLight,
    outline = SlateOutlineLight,
    outlineVariant = SlateOutlineLight,
    error = StatusRoseLight,
)

val MaterialTheme.semanticColors: SemanticColors
    @Composable
    get() = LocalSemanticColors.current

@Composable
fun ToolHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
