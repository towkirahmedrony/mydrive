package com.mydrive.app.data.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaDiskCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private fun cached(dir: String, user: String, name: String, sizeBytes: Int, ageMs: Long): File {
        val file = File(folder.root, "$dir/$user/$name")
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(sizeBytes) { 7 })
        file.setLastModified(now - ageMs)
        return file
    }

    private fun original(user: String, name: String, sizeBytes: Int, ageMs: Long): File =
        cached(MediaDiskCache.ORIGINAL_DIR, user, name, sizeBytes, ageMs)

    private fun trim(
        originalBudget: Long = BUDGET,
        thumbnailBudget: Long = BUDGET
    ): MediaDiskCache.TrimResult = runBlocking {
        MediaDiskCache.trim(folder.root, now, thumbnailBudget, originalBudget)
    }

    @Test
    fun `cache under budget is left alone`() {
        val file = original("user-a", "small.bin", CHUNK, ageMs = 30 * DAY)

        val result = trim()

        assertTrue(file.isFile)
        assertEquals(0, result.originals.deletedFiles)
    }

    @Test
    fun `the oldest entries are evicted first once the budget is exceeded`() {
        val oldest = original("user-a", "oldest.bin", CHUNK, ageMs = 30 * DAY)
        val older = original("user-a", "older.bin", CHUNK, ageMs = 10 * DAY)
        val recent = original("user-a", "recent.bin", CHUNK, ageMs = HOUR)
        val newest = original("user-a", "newest.bin", CHUNK, ageMs = 0)

        // Five chunks against a four chunk budget, trimmed to 85% of it.
        original("user-a", "fresh.bin", CHUNK, ageMs = 0)
        val result = trim()

        assertFalse(oldest.isFile)
        assertFalse(older.isFile)
        assertTrue(recent.isFile)
        assertTrue(newest.isFile)
        assertTrue(result.originals.remainingBytes <= BUDGET)
    }

    @Test
    fun `an entry in use is skipped and eviction continues with the next one`() {
        val playing = original("user-a", "playing.bin", CHUNK, ageMs = 60 * DAY)
        val idle = original("user-a", "idle.bin", CHUNK, ageMs = 30 * DAY)
        original("user-a", "c.bin", CHUNK, ageMs = HOUR)
        original("user-a", "d.bin", CHUNK, ageMs = 0)
        original("user-a", "e.bin", CHUNK, ageMs = 0)

        val result = MediaDiskCache.pinned(playing) { trim() }

        assertTrue("the file being played must survive", playing.isFile)
        assertFalse(idle.isFile)
        assertEquals(1, result.originals.skippedPinned)
    }

    @Test
    fun `zero byte entries and abandoned partial writes are removed`() {
        val empty = File(folder.root, "${MediaDiskCache.ORIGINAL_DIR}/user-a/empty.bin")
        empty.parentFile?.mkdirs()
        empty.createNewFile()
        val abandoned = original("user-a", "dead.bin.tmp", 2048, ageMs = HOUR)
        val inFlight = original("user-a", "live.bin.tmp", 2048, ageMs = 0)

        trim()

        assertFalse(empty.exists())
        assertFalse(abandoned.exists())
        assertTrue("a write in progress must not be swept", inFlight.isFile)
    }

    @Test
    fun `a zero byte original is never treated as a complete cache entry`() {
        val partial = File(folder.root, "partial.bin")
        partial.createNewFile()

        assertFalse(MediaDiskCache.isComplete(partial))
        MediaDiskCache.discardInvalid(partial)
        assertFalse(partial.exists())
    }

    @Test
    fun `thumbnails and originals are budgeted separately`() {
        val thumb = cached(MediaDiskCache.THUMBNAIL_DIR, "user-a", "t.webp", 4096, 90 * DAY)
        original("user-a", "a.bin", CHUNK, ageMs = 30 * DAY)
        original("user-a", "b.bin", 1024, ageMs = 0)

        val result = trim(originalBudget = CHUNK.toLong())

        assertTrue("an over-budget originals tree must not evict thumbnails", thumb.isFile)
        assertEquals(1, result.originals.deletedFiles)
    }

    @Test
    fun `one user's cache can be evicted without touching another's`() {
        val userA = original("user-a", "a.bin", CHUNK, ageMs = 30 * DAY)
        val userB = original("user-b", "b.bin", 1024, ageMs = 0)

        trim(originalBudget = CHUNK.toLong())

        assertFalse(userA.isFile)
        assertTrue(userB.isFile)
        assertTrue(userB.path.contains("/user-b/"))
    }

    @Test
    fun `eviction stays inside the cache roots`() {
        val mediaStoreLike = File(folder.root, "Pictures/IMG_0001.jpg")
        mediaStoreLike.parentFile?.mkdirs()
        mediaStoreLike.writeBytes(ByteArray(8192))
        mediaStoreLike.setLastModified(now - 365 * DAY)
        original("user-a", "a.bin", CHUNK, ageMs = 30 * DAY)
        original("user-a", "b.bin", CHUNK, ageMs = 0)

        trim(originalBudget = CHUNK.toLong())

        assertTrue(mediaStoreLike.isFile)
    }

    @Test
    fun `a write is atomic and leaves no temporary behind`() {
        val file = File(folder.root, "${MediaDiskCache.ORIGINAL_DIR}/user-a/written.bin")

        assertTrue(MediaDiskCache.write(file, ByteArray(16) { 3 }))

        assertTrue(MediaDiskCache.isComplete(file))
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
        assertFalse(MediaDiskCache.write(file, ByteArray(0)))
    }

    @Test
    fun `touching a cache hit makes it sort as recently used`() {
        val file = original("user-a", "hit.bin", CHUNK, ageMs = 30 * DAY)

        MediaDiskCache.touch(file, now)

        assertEquals(now, file.lastModified())
    }

    private companion object {
        const val HOUR = 60L * 60L * 1000L
        const val DAY = 24L * HOUR
        const val CHUNK = 64 * 1024
        const val BUDGET = 4L * CHUNK
    }
}
