package com.mydrive.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Local mirror of a Private Vault item.
 *
 * The vault's *file* state must be authoritative on the device — Supabase may be
 * unreachable and the ciphertext only exists here — so the local record is what the
 * hide/restore/delete flows drive, and it is what lets a crashed run be detected and
 * resumed instead of producing a second vault copy.
 *
 * Nothing sensitive lives here: no key material, no PIN, no decrypted path. The file
 * name is a device-relative name inside app-private storage. Live in the app's
 * existing Room database so there is one local store and one migration chain.
 */
@Entity(
    tableName = "media_vault_items",
    // One local vault record per media asset; re-hiding after a permanent delete
    // is a different row because the previous one is deleted, not reused.
    indices = [androidx.room.Index(value = ["localMediaId"], unique = true)]
)
data class VaultItemEntity(
    /** Server-side `media_vault_items.id` when known; the local key otherwise. */
    @PrimaryKey val vaultItemId: String,
    /** `media_assets.id` — the SAME cloud asset the normal gallery uses. */
    val remoteMediaId: String?,
    /** Device MediaStore id, used to re-find and remove the original safely. */
    val localMediaId: Long,
    /** Device-relative ciphertext file name inside app-private vault storage. */
    val encryptedFileName: String,
    val encryptedFileSize: Long,
    val encryptedSha256: String?,
    val originalMimeType: String,
    val originalFileName: String,
    val originalFileSize: Long,
    /** Mirrors the server enum: ENCRYPTING, READY, RESTORING, FAILED, DELETED. */
    val vaultStatus: String,
    val vaultVersion: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
    val hiddenAt: Long? = null,
    val restoredAt: Long? = null,
    /**
     * Set once the original MediaStore item has been removed. A record that is
     * READY but has not yet removed its original is an interrupted hide and must be
     * resumed rather than duplicated.
     */
    val originalRemoved: Boolean = false
) {
    companion object {
        const val STATUS_ENCRYPTING = "ENCRYPTING"
        const val STATUS_READY = "READY"
        const val STATUS_RESTORING = "RESTORING"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_DELETED = "DELETED"

        val ALL_STATUSES = setOf(
            STATUS_ENCRYPTING,
            STATUS_READY,
            STATUS_RESTORING,
            STATUS_FAILED,
            STATUS_DELETED
        )
    }
}

@Dao
interface VaultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: VaultItemEntity)

    @Query("SELECT * FROM media_vault_items WHERE vaultItemId = :vaultItemId LIMIT 1")
    suspend fun find(vaultItemId: String): VaultItemEntity?

    /**
     * The active record for a media asset, if any.
     *
     * Used by the hide flow to detect a duplicate/interrupted hide instead of
     * creating a second vault copy for the same media.
     */
    @Query(
        "SELECT * FROM media_vault_items WHERE localMediaId = :localMediaId " +
            "AND vaultStatus != 'DELETED' LIMIT 1"
    )
    suspend fun findActiveByLocalMediaId(localMediaId: Long): VaultItemEntity?

    /** Ids of every media asset currently held in the vault (any status). */
    @Query("SELECT localMediaId FROM media_vault_items WHERE vaultStatus != 'DELETED'")
    suspend fun vaultedLocalMediaIds(): List<Long>

    /** Ready items, newest first, for the vault grid. */
    @Query(
        "SELECT * FROM media_vault_items WHERE vaultStatus = 'READY' " +
            "ORDER BY hiddenAt DESC"
    )
    suspend fun readyItems(): List<VaultItemEntity>

    /** Interrupted work found at startup so it can be completed or rolled back. */
    @Query("SELECT * FROM media_vault_items WHERE vaultStatus IN ('ENCRYPTING','RESTORING')")
    suspend fun interruptedItems(): List<VaultItemEntity>

    @Query("DELETE FROM media_vault_items WHERE vaultItemId = :vaultItemId")
    suspend fun delete(vaultItemId: String)

    @Query("DELETE FROM media_vault_items")
    suspend fun clear()

    @Query(
        "UPDATE media_vault_items SET vaultStatus = :status, updatedAt = :updatedAt, " +
            "originalRemoved = :originalRemoved WHERE vaultItemId = :vaultItemId"
    )
    suspend fun updateStatus(
        vaultItemId: String,
        status: String,
        updatedAt: Long,
        originalRemoved: Boolean
    )
}
