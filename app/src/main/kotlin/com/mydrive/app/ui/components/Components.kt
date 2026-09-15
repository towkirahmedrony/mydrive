package com.mydrive.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.ConnectionStatus
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.ui.theme.CardShape
import com.mydrive.app.ui.theme.ChipShape
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.MediaShape
import com.mydrive.app.ui.theme.Overlay
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention
import com.mydrive.app.ui.theme.StatusConnected
import com.mydrive.app.ui.theme.StatusIdle
import com.mydrive.app.ui.theme.StatusSyncing
import com.mydrive.app.ui.util.formatDuration

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, CardShape)
            .then(clickMod)
            .padding(Spacing.lg),
        content = content
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = Copper,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}

@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = Copper
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(Radius.md))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = accent,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SyncStatusChip(
    status: ConnectionStatus,
    label: String,
    modifier: Modifier = Modifier
) {
    val color = when (status) {
        ConnectionStatus.CONNECTED -> StatusConnected
        ConnectionStatus.SYNCING -> StatusSyncing
        ConnectionStatus.ATTENTION -> StatusAttention
    }
    Row(
        modifier = modifier
            .clip(ChipShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
fun BackupStateChip(state: BackupState, modifier: Modifier = Modifier) {
    val (label, color, icon) = stateVisual(state)
    Row(
        modifier = modifier
            .clip(ChipShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = Spacing.xs, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun MediaThumb(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val description = if (item.type == MediaType.VIDEO) {
        "Video ${item.filename}"
    } else {
        "Photo ${item.filename}"
    }
    Box(
        modifier = modifier
            .clip(MediaShape)
            .aspectRatio(1f)
            .then(clickMod)
            .semantics { contentDescription = description }
    ) {
        MediaImage(
            uri = item.uri,
            seed = item.thumbnailSeed,
            type = item.type,
            modifier = Modifier.fillMaxSize(),
            sizePx = 256,
            contentDescription = description
        )
        if (item.type == MediaType.VIDEO) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Overlay),
                            startY = 80f
                        )
                    )
            )
            if (item.durationSeconds != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(Spacing.xs)
                        .clip(ChipShape)
                        .background(Ink.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "Video",
                        tint = Ivory,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = formatDuration(item.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ivory
                    )
                }
            }
        }
        if (item.isFavorite) {
            Icon(
                Icons.Outlined.Favorite,
                contentDescription = "Favorite",
                tint = Copper,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.xs)
                    .size(14.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(Spacing.xs)
        ) {
            MiniSyncDot(item.backupState)
        }
    }
}

@Composable
fun MiniSyncDot(state: BackupState) {
    val color = when (state) {
        BackupState.COMPLETED -> StatusConnected
        BackupState.FAILED, BackupState.CANCELLED -> StatusAttention
        BackupState.WAITING, BackupState.NOT_STARTED, BackupState.PAUSED -> StatusIdle
        else -> StatusSyncing
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.background.copy(alpha = 0.4f), CircleShape)
    )
}

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: ImageVector? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickMod)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val colors = MaterialTheme.colorScheme
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = Copper, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = Copper,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    )
}

@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )
        Column(
            modifier = Modifier
                .clip(CardShape)
                .background(colors.surfaceVariant)
                .border(1.dp, colors.outlineVariant, CardShape),
            content = content
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inbox
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val colors = MaterialTheme.colorScheme
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(Spacing.sm))
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        Spacer(Modifier.height(Spacing.xxs))
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
fun ProgressCard(
    title: String,
    filename: String,
    progress: Float,
    stateLabel: String,
    fileSize: String,
    modifier: Modifier = Modifier,
    thumbnail: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(Radius.sm))
        ) { thumbnail() }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = filename,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$fileSize · $stateLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(ChipShape),
                color = Copper,
                trackColor = MaterialTheme.colorScheme.background.copy(alpha = 0.5f)
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = Copper
        )
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ChipShape,
        color = Copper,
        contentColor = Ink
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ChipShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

fun stateVisual(state: BackupState): Triple<String, Color, ImageVector> {
    val idle = StatusIdleColor
    return when (state) {
        BackupState.COMPLETED -> Triple("Completed", StatusConnected, Icons.Outlined.CloudDone)
        BackupState.NOT_STARTED -> Triple("Not backed up", idle, Icons.Outlined.CloudQueue)
        BackupState.PREPARING -> Triple("Preparing", StatusSyncing, Icons.Outlined.CloudUpload)
        BackupState.UPLOADING -> Triple("Uploading", StatusSyncing, Icons.Outlined.CloudUpload)
        BackupState.PROCESSING -> Triple("Processing", StatusSyncing, Icons.Outlined.CloudQueue)
        BackupState.SENDING_TELEGRAM -> Triple("Telegram sync", StatusSyncing, Icons.Outlined.CloudUpload)
        BackupState.WAITING -> Triple("Waiting", idle, Icons.Outlined.CloudQueue)
        BackupState.PAUSED -> Triple("Paused", idle, Icons.Outlined.CloudQueue)
        BackupState.FAILED -> Triple("Failed", StatusAttention, Icons.Outlined.ErrorOutline)
        BackupState.CANCELLED -> Triple("Cancelled", idle, Icons.Outlined.Cancel)
    }
}

private val StatusIdleColor = Color(0xFF8B909A)

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(ChipShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, ChipShape)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = "Search",
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(Spacing.xs))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onBackground),
            cursorBrush = SolidColor(Copper),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                inner()
            }
        )
    }
}
