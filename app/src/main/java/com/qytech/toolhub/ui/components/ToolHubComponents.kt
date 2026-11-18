package com.qytech.toolhub.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qytech.toolhub.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("UsePropertyAccessSyntax")
fun copyTextToClipboard(
    context: Context,
    label: String,
    value: String,
    onCopied: (String) -> Unit,
) {
    if (value.isBlank() || value == "-") return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    onCopied(context.getString(R.string.copied_toast_fmt, label, value))
}

fun formatTimeMillis(millis: Long): String = runCatching {
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
}.getOrDefault(millis.toString())

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.38f)),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = if (compact) 3.dp else 5.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(if (compact) 12.dp else 14.dp),
                )
            }
            Text(
                text = text,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: (@Composable () -> Unit)? = null,
    headerAction: (@Composable () -> Unit)? = null,
    expandable: Boolean = false,
    defaultExpanded: Boolean = true,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(defaultExpanded) }
    val cardPad = if (compact) 14.dp else 18.dp
    val headerMinH = if (compact) 36.dp else 46.dp
    val iconBoxSize = if (compact) 30.dp else 36.dp
    val topGap = if (compact) 10.dp else 14.dp

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(cardPad)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = headerMinH)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (expandable) Modifier.clickable { expanded = !expanded }
                        else Modifier
                    )
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(iconBoxSize),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(if (compact) 5.dp else 7.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (badge != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    badge()
                }
                if (headerAction != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    headerAction()
                }
                if (expandable) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(
                            R.string.cd_collapse_fmt,
                            title
                        ) else stringResource(R.string.cd_expand_fmt, title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (expandable) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(modifier = Modifier.padding(top = topGap)) {
                        content()
                    }
                }
            } else {
                Column(modifier = Modifier.padding(top = topGap)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun CopyableInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospaceValue: Boolean = false,
    showDivider: Boolean = true,
    compact: Boolean = false,
    maxValueLines: Int = Int.MAX_VALUE,
    onCopy: ((label: String, value: String) -> Unit)? = null,
) {
    val canCopy = onCopy != null && value.isNotBlank() && value != "-"
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = if (compact) 38.dp else 46.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (canCopy) {
                        Modifier.clickable { onCopy.invoke(label, value) }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 4.dp, vertical = if (compact) 6.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.36f),
            )
            Text(
                text = value,
                style = (if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(
                    fontFamily = if (monospaceValue) FontFamily.Monospace else FontFamily.Default,
                ),
                fontWeight = FontWeight.Medium,
                maxLines = maxValueLines,
                overflow = if (maxValueLines < Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip,
                modifier = Modifier.weight(0.64f),
            )
            if (canCopy) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_label_fmt, label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    modifier = Modifier.size(if (compact) 14.dp else 16.dp),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                thickness = 0.6.dp,
            )
        }
    }
}
