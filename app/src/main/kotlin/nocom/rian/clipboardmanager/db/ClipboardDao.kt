package nocom.rian.clipboardmanager.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ClipboardItem)

    @Update
    suspend fun update(item: ClipboardItem)

    @Delete
    suspend fun delete(item: ClipboardItem)

    @Query("SELECT COUNT(*) FROM clipboardmanager")
    suspend fun getTotalClipCount(): Int

    @Query("SELECT * FROM clipboardmanager ORDER BY is_pinned DESC, timestamp DESC")
    fun getAllClips(): Flow<List<ClipboardItem>>

    @Query("SELECT * FROM clipboardmanager WHERE clip LIKE :query ORDER BY is_pinned DESC, timestamp DESC")
    fun searchClips(query: String): Flow<List<ClipboardItem>>
    
    @Query("SELECT * FROM clipboardmanager WHERE is_favorite = 1 ORDER BY is_pinned DESC, timestamp DESC")
    fun getFavoriteClips(): Flow<List<ClipboardItem>>

    @Query("DELETE FROM clipboardmanager WHERE is_pinned = 0 AND is_favorite = 0")
    suspend fun clearUnpinnedHistory()

    @Query("SELECT * FROM clipboardmanager WHERE clip = :text LIMIT 1")
    suspend fun findByContent(text: String): ClipboardItem?

    @Query("SELECT * FROM clipboardmanager WHERE image_hash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): ClipboardItem?

    @Query("SELECT * FROM clipboardmanager WHERE image_hash = :hash AND packageName = :packageName LIMIT 1")
    suspend fun findByHashAndPackage(hash: String, packageName: String): ClipboardItem?

    @Query("UPDATE clipboardmanager SET timestamp = :timestamp WHERE id = :id")
    suspend fun refreshExistingClip(id: Int, timestamp: Long)
    
    @Query("UPDATE clipboardmanager SET is_pinned = :pinned WHERE id = :id")
    suspend fun updatePinStatus(id: Int, pinned: Boolean)

    @Query("UPDATE clipboardmanager SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun updateFavoriteStatus(id: Int, isFavorite: Boolean)

    @Query("UPDATE clipboardmanager SET is_sensitive = :isSensitive WHERE id = :id")
    suspend fun updateSensitiveStatus(id: Int, isSensitive: Boolean)

    @Query("UPDATE clipboardmanager SET label = :label WHERE id = :id")
    suspend fun updateLabel(id: Int, label: String?)

    @Query("""
        DELETE FROM clipboardmanager
        WHERE is_pinned = 0
        AND is_favorite = 0
        AND id NOT IN (
            SELECT id FROM clipboardmanager
            ORDER BY timestamp DESC
            LIMIT :keepCount
        )
    """)
    suspend fun deleteOldestUnpinnedExceedingCap(keepCount: Int)

    @Query("DELETE FROM clipboardmanager WHERE is_pinned = 0 AND is_favorite = 0 AND timestamp < :cutoffMs")
    suspend fun deleteClipsOlderThan(cutoffMs: Long)
    
    @Query("DELETE FROM clipboardmanager") 
    suspend fun deleteAll()

    @Query("SELECT * FROM clipboardmanager WHERE is_pinned = 0")
    suspend fun getUnpinnedItems(): List<ClipboardItem>

    @Query("""
        SELECT * FROM clipboardmanager
        WHERE is_pinned = 0
        AND is_favorite = 0
        AND id NOT IN (
            SELECT id FROM clipboardmanager
            ORDER BY timestamp DESC
            LIMIT :keepCount
        )
    """)
    suspend fun getUnpinnedExceedingCap(keepCount: Int): List<ClipboardItem>

    @Query("SELECT * FROM clipboardmanager WHERE is_pinned = 0 AND is_favorite = 0 AND timestamp < :cutoffMs")
    suspend fun getClipsOlderThan(cutoffMs: Long): List<ClipboardItem>
    
    @Query("SELECT * FROM clipboardmanager ORDER BY is_pinned DESC, timestamp DESC")
    suspend fun getAllClipsOnce(): List<ClipboardItem>
}