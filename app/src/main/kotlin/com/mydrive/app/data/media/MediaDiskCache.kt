package com.mydrive.app.data.media

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the on-disk media caches bounded.
 *
 * The caches are plain per-user directory trees written by [ThumbnailLoader]
 * and [FullImageLoader]; this object owns their size policy. There is no
 * separate index: a file's last-modified stamp *is* its last-access stamp,
 * refreshed by [touch] whenever a cached file is served. That survives process
 * death and app upgrades for free, so a trim interrupted by termination simply
 * resumes from the filesystem next time.
 *
 * Eviction never touches MediaStore files, the Room catalog, or anything
 * remote — only files under the two cache roots, which Cloudinary and Drive
 * can always re-serve.
 */
object MediaDiskCache {

    /** Thumbnails are small and are the cache users notice when it is missing. */
    const val THUMBNAIL_BUDGET_BYTES = 96L * 1024L * 1024L

    /** Originals are individually large; a handful of them is worth keeping. */
    const val ORIGINAL_BUDGET_BYTES = 512L * 1024L * 1024L

    /**
     * Trimming to the budget exactly would re-trim on the next write, so each
     * pass drops to a low watermark and then stays quiet for a while.
     */
    private const val LOW_WATERMARK = 0.85

    /** Writes that accumulate before a trim is worth the directory walk. */
    private const val TRIM_AFTER_BYTES = 16L * 1024L * 1024L

    /** A trim also runs at most this often even if little was written. */
    private const val TRIM_INTERVAL_MS = 5L * 60L * 1000L

    /** A `.tmp` file older than this is from an interrupted write. */
    private const val PARTIAL_FILE_MAX_AGE_MS = 10L * 60L * 1000L

    /** Re-stamping a file that was already touched recently buys nothing. */
    private const val TOUCH_RESOLUTION_MS = 60L * 1000L

    const val THUMBNAIL_DIR = "media_thumbnails"
    const val ORIGINAL_DIR = "media_originals"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trimLock = Mutex()
    private val pending = AtomicLong(0L)
    private val lastTrimAt = AtomicLong(0L)

    /** Files a viewer or player is currently reading, by canonical path. */
    private val pinned = ConcurrentHashMap<String, Int>()

    /**
     * A cached file is usable only if it is a non-empty regular file.
     *
     * A truncated download or a partially written video would otherwise be
     * decoded as an image or handed to the player as a complete original.
     */
    fun isComplete(file: File): Boolean = file.isFile && file.length() > 0L

    /** Drops an entry that exists but can never be served. */
    fun discardInvalid(file: File) {
        if (file.exists() && !isComplete(file)) runCatching { file.delete() }
    }

    /** Records a cache hit so the file sorts as recently used. */
    fun touch(file: File, now: Long = System.currentTimeMillis()) {
        if (!file.isFile) return
        if (now - file.lastModified() < TOUCH_RESOLUTION_MS) return
        runCatching { file.setLastModified(now) }
    }

    /**
     * Marks [file] as in use for the duration of [block].
     *
     * Eviction skips pinned files rather than waiting for them, so a viewer
     * cannot lose the bytes it is halfway through reading, and a slow reader
     * cannot stall the trim.
     */
    inline fun <T> pinned(file: File, block: () -> T): T {
        pin(file)
        try {
            return block()
        } finally {
            unpin(file)
        }
    }

    fun pin(file: File) {
        val key = keyOf(file)
        pinned.compute(key) { _, count -> (count ?: 0) + 1 }
    }

    fun unpin(file: File) {
        val key = keyOf(file)
        pinned.compute(key) { _, count -> if (count == null || count <= 1) null else count - 1 }
    }

    fun isPinned(file: File): Boolean = pinned.containsKey(keyOf(file))

    /**
     * Writes [bytes] through a temporary file so a killed process leaves a
     * `.tmp` the next sweep removes, never a half-written cache entry.
     */
    fun write(file: File, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val written = runCatching {
            temporary.writeBytes(bytes)
            // Only ever publish by rename: a direct write to the final path
            // could be interrupted and leave a plausible-looking entry.
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file) || throw IOException("cache rename failed")
            }
            true
        }.getOrElse {
            runCatching { temporary.delete() }
            false
        }
        if (written) pending.addAndGet(bytes.size.toLong())
        return written
    }

    /**
     * Requests a trim. Cheap to call from a media path: it only walks the
     * cache once enough has been written or enough time has passed, and a
     * single trim runs at a time on the IO dispatcher.
     */
    fun scheduleTrim(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val due = force ||
            pending.get() >= TRIM_AFTER_BYTES ||
            now - lastTrimAt.get() >= TRIM_INTERVAL_MS
        if (!due) return
        val filesDir = context.applicationContext.filesDir ?: return
        scope.launch {
            // A trim that started must finish its bookkeeping even if the
            // caller's scope goes away; it is short and purely local.
            withContext(NonCancellable) { trim(filesDir, System.currentTimeMillis()) }
        }
    }

    /** Runs a trim immediately. Exposed for startup sweeps and tests. */
    suspend fun trim(
        filesDir: File,
        now: Long = System.currentTimeMillis(),
        thumbnailBudget: Long = THUMBNAIL_BUDGET_BYTES,
        originalBudget: Long = ORIGINAL_BUDGET_BYTES
    ): TrimResult = trimLock.withLock {
        withContext(Dispatchers.IO) {
            pending.set(0L)
            lastTrimAt.set(now)
            TrimResult(
                thumbnails = sweep(File(filesDir, THUMBNAIL_DIR), thumbnailBudget, now),
                originals = sweep(File(filesDir, ORIGINAL_DIR), originalBudget, now)
            )
        }
    }

    data class TrimResult(val thumbnails: SweepResult, val originals: SweepResult)

    data class SweepResult(
        val remainingBytes: Long,
        val deletedFiles: Int,
        val deletedBytes: Long,
        val skippedPinned: Int
    )

    /**
     * Removes unusable entries from [root], then the least recently used files
     * until the tree fits [budget].
     */
    private fun sweep(root: File, budget: Long, now: Long): SweepResult {
        if (!root.isDirectory) return SweepResult(0L, 0, 0L, 0)
        var deletedFiles = 0
        var deletedBytes = 0L
        val entries = ArrayList<Entry>()
        var total = 0L

        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val length = file.length()
            val partial = file.name.endsWith(".tmp") &&
                now - file.lastModified() > PARTIAL_FILE_MAX_AGE_MS
            if (length == 0L || partial) {
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    deletedFiles++
                    deletedBytes += length
                }
                return@forEach
            }
            if (file.name.endsWith(".tmp")) return@forEach // a write in flight
            entries.add(Entry(file, length, file.lastModified()))
            total += length
        }

        var skipped = 0
        if (total > budget) {
            val target = (budget * LOW_WATERMARK).toLong()
            for (entry in entries.sortedBy { it.lastAccessMs }) {
                if (total <= target) break
                if (isPinned(entry.file)) {
                    skipped++
                    continue
                }
                if (runCatching { entry.file.delete() }.getOrDefault(false)) {
                    total -= entry.sizeBytes
                    deletedFiles++
                    deletedBytes += entry.sizeBytes
                }
            }
        }
        removeEmptyDirectories(root)
        return SweepResult(total, deletedFiles, deletedBytes, skipped)
    }

    private fun removeEmptyDirectories(root: File) {
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.listFiles()?.isEmpty() == true) {
                runCatching { child.delete() }
            }
        }
    }

    private data class Entry(val file: File, val sizeBytes: Long, val lastAccessMs: Long)

    fun keyOf(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.path)
}
