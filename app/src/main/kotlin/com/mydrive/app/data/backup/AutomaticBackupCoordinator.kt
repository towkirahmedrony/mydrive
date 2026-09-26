package com.mydrive.app.data.backup

import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.isEligibleForBackup
import com.mydrive.app.data.repository.resumeLocally
import com.mydrive.app.data.repository.toBackupState
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class BackupDiscoveryReason {
    PERMISSION_GRANTED,
    APP_START,
    FOREGROUND,
    MEDIASTORE,
    PERIODIC,
    MANUAL,
    AUTHENTICATED
}

sealed class BackupDiscoveryResult {
    data object PermissionDenied : BackupDiscoveryResult()
    data object AutomaticDisabled : BackupDiscoveryResult()
    data object NoSession : BackupDiscoveryResult()
    data object Coalesced : BackupDiscoveryResult()
    data object NothingToBackup : BackupDiscoveryResult()
    data class Enqueued(val ids: List<String>) : BackupDiscoveryResult()
}

/**
 * Coalesces overlapping automatic-backup discovery into one in-flight scan
 * plus at most one trailing scan. Mirrors [com.mydrive.app.data.media.RemoteRefreshGate].
 */
class BackupDiscoveryGate {
    private var inFlight = false
    private var trailing = false

    @Synchronized
    fun begin(): Boolean {
        if (inFlight) {
            trailing = true
            return false
        }
        inFlight = true
        return true
    }

    @Synchronized
    fun end(): Boolean {
        inFlight = false
        val owed = trailing
        trailing = false
        return owed
    }

    @Synchronized
    fun isInFlight(): Boolean = inFlight
}

/**
 * Single entry point for automatic (and manual) backup discovery.
 *
 * Discovers eligible local media through the existing MediaStore scan and
 * [isEligibleForBackup] rule, then hands ids to the existing
 * [com.mydrive.app.data.repository.BackupRepository] / WorkManager pipeline.
 * It never uploads itself.
 */
class AutomaticBackupCoordinator(
    private val canReadMedia: () -> Boolean,
    private val automaticBackupEnabled: () -> Boolean,
    private val currentUserId: () -> String?,
    private val refreshLibrary: suspend (force: Boolean, localOverlayOnly: Boolean) -> Unit,
    private val deviceMedia: () -> List<MediaItem>,
    private val libraryMedia: () -> List<MediaItem>,
    private val records: () -> Map<String, SyncRecord>,
    private val startBackup: (ids: Collection<String>, resumeIfPaused: Boolean) -> Unit,
    private val isPaused: () -> Boolean = { false },
    private val gate: BackupDiscoveryGate = BackupDiscoveryGate()
) {
    private val runMutex = Mutex()

    @Volatile
    private var lastReason: BackupDiscoveryReason = BackupDiscoveryReason.APP_START

    suspend fun request(reason: BackupDiscoveryReason): BackupDiscoveryResult {
        lastReason = reason
        if (!gate.begin()) {
            log("BACKUP_DISCOVERY_COALESCED", "Backup discovery coalesced into in-flight scan", reason)
            return BackupDiscoveryResult.Coalesced
        }
        try {
            var latest = discover(lastReason)
            while (gate.end()) {
                if (!gate.begin()) return latest
                latest = discover(lastReason)
            }
            return latest
        } catch (cancelled: CancellationException) {
            gate.end()
            throw cancelled
        } catch (error: Throwable) {
            gate.end()
            runCatching {
                DeveloperLogger.error(
                    category = LogCategory.SYSTEM,
                    event = "BACKUP_DISCOVERY_FAILED",
                    message = "Backup discovery failed",
                    throwable = error,
                    metadata = mapOf("reason" to lastReason.name)
                )
            }
            throw error
        }
    }

    private suspend fun discover(reason: BackupDiscoveryReason): BackupDiscoveryResult = runMutex.withLock {
        if (currentUserId().isNullOrBlank()) return BackupDiscoveryResult.NoSession
        if (!canReadMedia()) {
            runCatching { refreshLibrary(false, false) }
            return BackupDiscoveryResult.PermissionDenied
        }
        if (reason != BackupDiscoveryReason.MANUAL && !automaticBackupEnabled()) {
            return BackupDiscoveryResult.AutomaticDisabled
        }

        val localOverlayOnly = reason == BackupDiscoveryReason.MEDIASTORE
        // Only a genuine state change bypasses the launch throttle. APP_START,
        // FOREGROUND and AUTHENTICATED all fire within the same cold start as the
        // gallery's own load, and forcing each of them made every launch scan the
        // device several times over. They now fold into whichever pass is already
        // running; MEDIASTORE is a local overlay pass, so it is never throttled and
        // keeps reacting to a new photo immediately.
        val force = reason == BackupDiscoveryReason.PERMISSION_GRANTED ||
            reason == BackupDiscoveryReason.MANUAL
        refreshLibrary(force, localOverlayOnly)

        if (!canReadMedia()) return BackupDiscoveryResult.PermissionDenied

        val ids = selectEligibleBackupIds(
            deviceItems = deviceMedia(),
            libraryItems = libraryMedia(),
            records = records()
        )
        if (ids.isEmpty()) {
            log("BACKUP_DISCOVERY_IDLE", "Backup discovery found nothing to enqueue", reason)
            return BackupDiscoveryResult.NothingToBackup
        }
        val resumeIfPaused = reason == BackupDiscoveryReason.MANUAL
        startBackup(ids, resumeIfPaused || !isPaused())
        log(
            "BACKUP_DISCOVERY_ENQUEUED",
            "Backup discovery enqueued eligible media",
            reason,
            mapOf("count" to ids.size.toString())
        )
        BackupDiscoveryResult.Enqueued(ids)
    }

    private fun log(
        event: String,
        message: String,
        reason: BackupDiscoveryReason,
        extra: Map<String, String> = emptyMap()
    ) {
        runCatching {
            DeveloperLogger.info(
                category = LogCategory.SYSTEM,
                event = event,
                message = message,
                metadata = extra + mapOf("reason" to reason.name)
            )
        }
    }

    companion object {
        /**
         * Device MediaStore items are the upload candidates. Cloud-backed evidence
         * lives on the composed library row ([MediaItem.cloudBackedUp]), so a
         * library match is applied before the existing eligibility rule.
         */
        fun selectEligibleBackupIds(
            deviceItems: List<MediaItem>,
            libraryItems: List<MediaItem>,
            records: Map<String, SyncRecord>
        ): List<String> {
            if (deviceItems.isEmpty()) return emptyList()
            val libraryById = libraryItems.associateBy { it.id }
            return deviceItems
                .asSequence()
                .map { item ->
                    val composed = libraryById[item.id]
                    if (composed != null && composed.cloudBackedUp) {
                        item.copy(cloudBackedUp = true)
                    } else {
                        item
                    }
                }
                .filter { item ->
                    isEligibleForBackup(
                        item = item,
                        recordState = records[item.id]?.state?.toBackupState()?.resumeLocally()
                    )
                }
                .map { it.id }
                .distinct()
                .toList()
        }
    }
}
