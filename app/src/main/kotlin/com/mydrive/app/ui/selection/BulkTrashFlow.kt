package com.mydrive.app.ui.selection

import android.content.IntentSender
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.repository.DeleteMediaResult
import com.mydrive.app.data.repository.RemoveFromLibraryResult

/**
 * One item's path through the existing Move-to-Trash architecture.
 *
 * Local device items go through MediaStore trash then [moveCloudToTrash]; cloud-only
 * tiles skip MediaStore (their uri is not a content:// handle) and use the cloud
 * half only. The device half is never rolled back.
 */
enum class TrashAttempt {
    LOCAL_THEN_CLOUD,
    CLOUD_ONLY
}

fun trashAttemptFor(item: MediaItem?): TrashAttempt {
    if (item == null) return TrashAttempt.CLOUD_ONLY
    if (!item.originLocal) return TrashAttempt.CLOUD_ONLY
    if (item.uri.isBlank() || item.uri.startsWith("http://") || item.uri.startsWith("https://")) {
        return TrashAttempt.CLOUD_ONLY
    }
    return TrashAttempt.LOCAL_THEN_CLOUD
}

fun recordTrashSuccess(
    counts: BulkItemCounts,
    cloud: RemoveFromLibraryResult
): BulkItemCounts {
    // Unauthorized / Failed still left the item in Trash locally; that is success
    // for the user-facing delete, with cloud sync pending.
    return when (cloud) {
        RemoveFromLibraryResult.Success,
        RemoveFromLibraryResult.NotFound,
        RemoveFromLibraryResult.Unauthorized,
        RemoveFromLibraryResult.Failed -> counts.copy(succeeded = counts.succeeded + 1)
    }
}

fun recordTrashFailure(counts: BulkItemCounts): BulkItemCounts =
    counts.copy(failed = counts.failed + 1)

fun interpretLocalTrash(result: DeleteMediaResult): LocalTrashDecision = when (result) {
    DeleteMediaResult.Success -> LocalTrashDecision.Finalize
    DeleteMediaResult.NotFound -> LocalTrashDecision.CloudOnly
    is DeleteMediaResult.RequiresSystemConfirmation -> LocalTrashDecision.PauseSystem(
        result.intentSender,
        result.alreadyPerformedOnApproval
    )
    DeleteMediaResult.RequiresManageMedia -> LocalTrashDecision.PauseManageMedia
    DeleteMediaResult.PermissionDenied, DeleteMediaResult.Failed -> LocalTrashDecision.Fail
}

sealed class LocalTrashDecision {
    data object Finalize : LocalTrashDecision()
    data object CloudOnly : LocalTrashDecision()
    data object Fail : LocalTrashDecision()
    data class PauseSystem(
        val intentSender: IntentSender,
        val alreadyPerformedOnApproval: Boolean
    ) : LocalTrashDecision()
    data object PauseManageMedia : LocalTrashDecision()
}
