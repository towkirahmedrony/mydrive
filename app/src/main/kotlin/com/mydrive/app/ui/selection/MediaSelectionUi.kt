package com.mydrive.app.ui.selection

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.trash.TrashActionSheet

@Composable
fun MediaSelectionEffects(
    controller: GallerySelectionController,
    snackbarHostState: SnackbarHostState
) {
    val confirmation by controller.confirmation.collectAsStateWithLifecycle()
    val manageMedia by controller.manageMedia.collectAsStateWithLifecycle()
    val userMessage by controller.userMessage.collectAsStateWithLifecycle()
    val pendingMove by controller.pendingMove.collectAsStateWithLifecycle()
    val selection by controller.selection.collectAsStateWithLifecycle()

    val confirmationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        controller.onSystemConfirmationResult(result.resultCode == Activity.RESULT_OK)
    }
    val manageMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        controller.onManageMediaAccessResult()
    }
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) controller.moveToDestination(treeUri) else controller.cancelPendingMove()
    }

    BackHandler(enabled = selection.mode) { controller.clearSelection() }

    LaunchedEffect(confirmation) {
        confirmation?.let { request ->
            confirmationLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }
    LaunchedEffect(manageMedia) {
        if (manageMedia == null) return@LaunchedEffect
        val intent = controller.manageMediaIntent()
        if (intent != null) manageMediaLauncher.launch(intent) else controller.onManageMediaAccessResult()
    }
    LaunchedEffect(pendingMove) {
        if (pendingMove) safLauncher.launch(null)
    }
    LaunchedEffect(userMessage) {
        val message = userMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        controller.consumeUserMessage()
    }
}

@Composable
fun MediaSelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionHeaderIcon(
            icon = Icons.Outlined.Close,
            contentDescription = "Cancel selection",
            onClick = onClose
        )
        Text(
            text = MediaSelection.countLabel(selectedCount),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.md)
        )
        SelectionHeaderIcon(
            icon = Icons.Outlined.SelectAll,
            contentDescription = "Select all",
            onClick = onSelectAll
        )
    }
}

@Composable
fun MediaSelectionBottomBar(
    enabled: Boolean,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    modifier: Modifier = Modifier,
    extraAction: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SelectionActionButton(
            icon = Icons.Outlined.Delete,
            label = "Delete",
            enabled = enabled,
            onClick = onDelete,
            modifier = Modifier.weight(1f)
        )
        SelectionActionButton(
            icon = Icons.Outlined.FileOpen,
            label = "Move",
            enabled = enabled,
            onClick = onMove,
            modifier = Modifier.weight(1f)
        )
        extraAction?.invoke(this)
    }
}

@Composable
fun MediaSelectionProgress(progress: BulkActionProgress) {
    val colors = MaterialTheme.colorScheme
    val label = progress.label
    if (progress.inProgress) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = Copper,
            trackColor = colors.surfaceVariant
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs)
            )
        }
    }
}

@Composable
fun MediaSelectionSheets(controller: GallerySelectionController, items: List<MediaItem>) {
    val sheet by controller.sheet.collectAsStateWithLifecycle()
    when (val current = sheet) {
        is GallerySelectionSheet.TrashConfirm -> {
            val count = current.ids.size
            val preview = items.firstOrNull { it.id == current.ids.firstOrNull() }
            TrashActionSheet(
                title = MediaSelection.trashConfirmTitle(count),
                body = MediaSelection.trashConfirmBody(count),
                confirmLabel = "Move to Trash",
                preview = preview,
                onDismiss = controller::dismissSheet,
                onConfirm = controller::confirmDeleteSelected
            )
        }
        GallerySelectionSheet.Hidden -> Unit
    }
}

@Composable
fun MediaSelectionSnackbarHost(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    SnackbarHost(hostState = snackbarHostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = colors.inverseSurface,
            contentColor = colors.inverseOnSurface,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
fun SelectionIndicator(
    selected: Boolean,
    isVideo: Boolean,
    modifier: Modifier = Modifier
) {
    val description = when {
        selected && isVideo -> "Deselect video"
        selected -> "Deselect photo"
        isVideo -> "Select video"
        else -> "Select photo"
    }
    Box(
        modifier = modifier
            .size(22.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Copper)
                    .border(1.dp, Color.White.copy(alpha = 0.92f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = description,
                    tint = Ink,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Ink.copy(alpha = 0.28f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.92f), CircleShape)
            )
        }
    }
}

@Composable
private fun SelectionHeaderIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = colors.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SelectionActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    ExtendedFloatingActionButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(label, fontWeight = FontWeight.SemiBold) },
        shape = RoundedCornerShape(100.dp),
        containerColor = if (enabled) Copper else colors.surfaceVariant,
        contentColor = if (enabled) Ivory else colors.onSurfaceVariant.copy(alpha = 0.45f),
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
    )
}
