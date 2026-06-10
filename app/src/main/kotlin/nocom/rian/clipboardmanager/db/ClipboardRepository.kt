package nocom.rian.clipboardmanager.db

import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction

class ClipboardRepository(private val clipboardDao: ClipboardDao) {

    val allClips: Flow<List<ClipboardItem>> = clipboardDao.getAllClips()
    val favoriteClips: Flow<List<ClipboardItem>> = clipboardDao.getFavoriteClips()

    suspend fun insert(item: ClipboardItem) = clipboardDao.insert(item)
    suspend fun update(item: ClipboardItem) = clipboardDao.update(item)
    suspend fun delete(item: ClipboardItem) = clipboardDao.delete(item)
    suspend fun count(): Int = clipboardDao.getTotalClipCount()

    suspend fun findByContent(text: String): ClipboardItem? = clipboardDao.findByContent(text)
    
    suspend fun findByHash(hash: String): ClipboardItem? = clipboardDao.findByHash(hash)

    suspend fun findByHashAndPackage(hash: String, packageName: String): ClipboardItem? =
        clipboardDao.findByHashAndPackage(hash, packageName)

    suspend fun refreshExistingClip(id: Int, timestamp: Long) =
        clipboardDao.refreshExistingClip(id, timestamp) 

    suspend fun togglePin(item: ClipboardItem) =
        clipboardDao.updatePinStatus(item.id, !item.isPinned)

    suspend fun toggleFavorite(item: ClipboardItem) =
        clipboardDao.updateFavoriteStatus(item.id, !item.isFavorite)

    suspend fun toggleSensitive(item: ClipboardItem) =
        clipboardDao.updateSensitiveStatus(item.id, !item.isSensitive)

    suspend fun updateLabel(id: Int, label: String?) =
        clipboardDao.updateLabel(id, label)

    suspend fun clearUnpinned() = clipboardDao.clearUnpinnedHistory()

    suspend fun enforceClipCap(maxClips: Int) =
        clipboardDao.deleteOldestUnpinnedExceedingCap(maxClips)

    suspend fun deleteOlderThan(cutoffMs: Long) =
        clipboardDao.deleteClipsOlderThan(cutoffMs)
    
    suspend fun deleteAll() = clipboardDao.deleteAll()
    
    suspend fun getUnpinnedItems(): List<ClipboardItem> =
        clipboardDao.getUnpinnedItems()

    suspend fun getUnpinnedExceedingCap(keepCount: Int): List<ClipboardItem> =
        clipboardDao.getUnpinnedExceedingCap(keepCount)

    suspend fun getClipsOlderThan(cutoffMs: Long): List<ClipboardItem> =
        clipboardDao.getClipsOlderThan(cutoffMs)
        
    suspend fun getAllClips(): List<ClipboardItem> = clipboardDao.getAllClipsOnce()
    
    suspend fun searchClips(query: String): Flow<List<ClipboardItem>> {
        return clipboardDao.searchClips("%$query%")
    }
    /**
     * Fetches expiring clips exceeding the cap, deletes their associated image files,
     * and clears them from the database atomically.
     */
    suspend fun enforceClipCapWithImages(maxClips: Int, onEachDeleted: (ClipboardItem) -> Unit, database: AppDatabase) {
        database.withTransaction {
            // 1. Get the exact items that are going to be deleted
            val itemsToDelete = clipboardDao.getUnpinnedExceedingCap(maxClips)
            
            // 2. Clear out their files via the callback passed from service
            itemsToDelete.forEach { onEachDeleted(it) }
            
            // 3. Perform the actual DB purge
            clipboardDao.deleteOldestUnpinnedExceedingCap(maxClips)
        }
    }

    /**
     * Fetches stale clips older than the cutoff, deletes their associated image files,
     * and clears them from the database atomically.
     */
    suspend fun deleteOlderThanWithImages(cutoffMs: Long, onEachDeleted: (ClipboardItem) -> Unit, database: AppDatabase) {
        database.withTransaction {
            // 1. Get the exact items matching the timestamp criteria
            val itemsToDelete = clipboardDao.getClipsOlderThan(cutoffMs)
            
            // 2. Clear out their files via the callback
            itemsToDelete.forEach { onEachDeleted(it) }
            
            // 3. Remove them from DB
            clipboardDao.deleteClipsOlderThan(cutoffMs)
        }
    }
}