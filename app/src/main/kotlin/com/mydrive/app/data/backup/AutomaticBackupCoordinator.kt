package com.mydrive.app.data.backup

import com.mydrive.app.data.local.CloudBackedUpIdentity
import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.media.CloudBackupCandidate
import com.mydrive.app.data.media.CloudBackupLookup
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.isActive
import com.mydrive.app.data.model.isEligibleForBackup
import com.mydrive.app.data.model.isQueued
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
    /**
     * The authoritative "does the cloud already hold this media?" check.
     *
     * Discovery is the first of two gates (the second is inside
     * `BackupRepository.processOne`) and it runs before anything is enqueued, so
     * media the cloud already holds is never even queued — it is reconciled and
     * skipped.
     */
    private val verifyCloudBackedUp: suspend (List<CloudBackupCandidate>) -> CloudBackupLookup =
        { CloudBackupLookup.Unavailable },
    /** Records media the cloud already holds as COMPLETED, with no upload. */
    private val adoptCloudBackedUp: (Map<String, CloudBackedUpIdentity>) -> Unit = { _ -> },
    /**
     * Forgets persisted cloud-index entries the server no longer confirms, so media
     * whose cloud copy really was removed becomes eligible again instead of being
     * remembered as done forever.
     */
    private val forgetCloudIndex: (Set<String>) -> Unit = { _ -> },
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

        // Locally, only what the queue already knows is treated as final. Whether
        // the *cloud* holds a media is a question for the cloud: the queue is
        // install-local (lost on reinstall) and the loaded catalog is one page of
        // many, so neither may be read as proof that something has not been backed
        // up. Everything else is offered to the server and decided there.
        val records = records()
        val candidates = selectCloudVerificationCandidates(
            deviceItems = deviceMedia(),
            libraryItems = libraryMedia(),
            records = records
        )
        if (candidates.isEmpty()) {
            log("BACKUP_DISCOVERY_IDLE", "Backup discovery found nothing to enqueue", reason)
            return BackupDiscoveryResult.NothingToBackup
        }

        val lookup = verifyCloudBackedUp(candidates.map { cloudCandidate(it, records[it.id]) })
        val remaining = when (lookup) {
            CloudBackupLookup.Unavailable -> {
                // Nothing was proven. Enqueueing is still safe: the upload gate asks
                // the same question again before it uploads anything and defers
                // rather than guessing. The distinction is logged because "could not
                // tell" is not the same as "nothing to do".
                log(
                    "BACKUP_DISCOVERY_UNVERIFIED",
                    "Cloud backup state could not be verified; uploads are confirmed individually",
                    reason,
                    mapOf("candidates" to candidates.size.toString())
                )
                // Unverified items still keep the local benefit of the doubt: media
                // the local cloud index believes is backed up is left alone rather
                // than queued. The rest go to the queue, where the upload gate asks
                // the same question again and defers instead of guessing.
                candidates.filterNot { it.cloudBackedUp }
            }
            is CloudBackupLookup.Checked -> reconcileVerified(candidates, lookup, records, reason)
        }
        if (remaining.isEmpty()) {
            log("BACKUP_DISCOVERY_IDLE", "Backup discovery found nothing to enqueue", reason)
            return BackupDiscoveryResult.NothingToBackup
        }
        val ids = remaining.map { it.id }
        val resumeIfPaused = reason == BackupDiscoveryReason.MANUAL
        startBackup(ids, resumeIfPaused || !isPaused())
        log(
            "BACKUP_DISCOVERY_ENQUEUED",
            "Backup discovery enqueued eligible media",
            reason,
            mapOf(
                "count" to ids.size.toString(),
                "already_in_cloud" to (candidates.size - remaining.size).toString()
            )
        )
        BackupDiscoveryResult.Enqueued(ids)
    }

    /**
     * Splits [candidates] against the server's answer.
     *
     * Media the cloud already holds are adopted locally as COMPLETED — a metadata
     * write, no upload, no file read. An item the local cloud index claimed but the
     * server does not confirm has its index entry forgotten, so a cloud copy that
     * really was removed can be backed up again. Only what is left may be uploaded.
     */
    private fun reconcileVerified(
        candidates: List<MediaItem>,
        lookup: CloudBackupLookup.Checked,
        records: Map<String, SyncRecord>,
        reason: BackupDiscoveryReason
    ): List<MediaItem> {
        val alreadyInCloud = LinkedHashMap<String, CloudBackedUpIdentity>()
        val staleIndexIds = LinkedHashSet<String>()
        val remaining = ArrayList<MediaItem>(candidates.size)
        for (item in candidates) {
            val candidate = cloudCandidate(item, records[item.id])
            val row = lookup.rowFor(candidate)
            if (row != null) {
                alreadyInCloud[item.id] = CloudBackedUpIdentity(row.id, row.clientUploadId)
                continue
            }
            // No non-tombstone row at all: the cloud genuinely does not hold it, so
            // a local "backed up" belief for this media is provably stale.
            if (item.cloudBackedUp && lookup.statusOf(candidate) == null) staleIndexIds += item.id
            remaining += item
        }
        if (alreadyInCloud.isNotEmpty()) adoptCloudBackedUp(alreadyInCloud)
        if (staleIndexIds.isNotEmpty()) forgetCloudIndex(staleIndexIds)
        log(
            "BACKUP_DISCOVERY_RECONCILED",
            "Backup discovery reconciled media the cloud already holds",
            reason,
            mapOf(
                "candidates" to candidates.size.toString(),
                "already_in_cloud" to alreadyInCloud.size.toString(),
                "stale_index_entries" to staleIndexIds.size.toString(),
                "to_upload" to remaining.size.toString()
            )
        )
        return remaining
    }

    private fun cloudCandidate(item: MediaItem, record: SyncRecord?): CloudBackupCandidate =
        CloudBackupCandidate(
            localMediaId = item.mediaStoreId,
            clientUploadId = record?.clientUploadId,
            fileSize = item.fileSizeBytes
        )

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
         * The items whose cloud state must be asked about before anything is queued.
         *
         * Local evidence is final in exactly two directions: a COMPLETED record, and
         * a record that is queued or in flight (owned by the running drain). Every
         * other device item is offered to the server, *including* one the local
         * cloud index believes is backed up — the server is what turns that belief
         * into a confirmation or exposes it as stale.
         *
         * [item.cloudBackedUp] is carried through only so a stale index entry can be
         * recognised, never to decide the outcome here.
         */
        fun selectCloudVerificationCandidates(
            deviceItems: List<MediaItem>,
            libraryItems: List<MediaItem>,
            records: Map<String, SyncRecord>
        ): List<MediaItem> {
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
                    needsCloudVerification(records[item.id]?.state?.toBackupState()?.resumeLocally())
                }
                .distinctBy { it.id }
                .toList()
        }

        /**
         * Whether a media with this local queue state still has to be checked
         * against the cloud.
         */
        fun needsCloudVerification(recordState: BackupState?): Boolean = when {
            recordState == null -> true
            recordState == BackupState.COMPLETED -> false
            // Already owned by the running drain, which resumes it.
            recordState.isActive || recordState.isQueued -> false
            // NOT_STARTED, CLOUDINARY_COMPLETED (uploaded but never recorded), and
            // the retryable states all still need the cloud's answer.
            else -> true
        }

        /**
         * Device MediaStore items are the upload candidates. Cloud-backed evidence
         * lives on the composed library row ([MediaItem.cloudBackedUp]), so a
         * library match is applied before the existing eligibility rule.
         *
         * Retained for the manual path used when no coordinator is available; the
         * coordinator itself decides the cloud half from the server
         * ([selectCloudVerificationCandidates]).
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
