package com.mydrive.app.ui.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.util.formatDateTime
import com.mydrive.app.ui.util.formatFileSize
import com.mydrive.app.ui.util.formatPlaybackMs

@Composable
fun MediaDetailsSheet(item: MediaItem) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface,
        contentColor = colors.onSurface,
        shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = "Details",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface
            )
            Spacer(Modifier.height(Spacing.md))
            DetailRow("Name", item.filename)
            if (item.capturedAtMillis > 0L) {
                DetailRow("Date", formatDateTime(item.capturedAtMillis))
            }
            if (item.fileSizeBytes > 0L) {
                DetailRow("Size", formatFileSize(item.fileSizeBytes))
            }
            val resolution = when {
                item.width > 0 && item.height > 0 -> "${item.width} × ${item.height}"
                item.resolution.isNotBlank() && item.resolution != "Unknown" -> item.resolution
                else -> null
            }
            if (resolution != null) {
                DetailRow("Dimensions", resolution)
            }
            if (item.type == MediaType.VIDEO) {
                val durationMs = item.durationMillis?.toInt()?.takeIf { it > 0 }
                    ?: item.durationSeconds?.takeIf { it > 0 }?.times(1000)
                if (durationMs != null) {
                    DetailRow("Duration", formatPlaybackMs(durationMs))
                }
            }
            if (item.mimeType.isNotBlank()) {
                DetailRow("Type", item.mimeType)
            }
            DetailRow("Kind", if (item.type == MediaType.VIDEO) "Video" else "Photo")
            if (item.uri.isNotBlank()) {
                DetailRow("URI", item.uri)
            }
            val location = item.relativePath?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
                ?: item.albumName.takeIf { it.isNotBlank() }
            if (location != null) {
                DetailRow("Album/Location", location)
            }
            DetailRow("Cloud Backup", when (item.backupState) {
                BackupState.COMPLETED -> "Backed up to Cloud"
                BackupState.NOT_STARTED -> "Not backed up"
                BackupState.FAILED -> "Backup failed"
                else -> "Backup in progress"
            })
            if (!item.cloudinaryAssetId.isNullOrBlank()) {
                DetailRow("Cloud Asset ID", item.cloudinaryAssetId)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(0.38f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(0.62f)
        )
    }
}
