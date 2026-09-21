package nocom.rian.clipboardmanager.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
@Database(entities = [ClipboardItem::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "clipboard_db"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance

                // Trigger background migration of MD5 hashes to SHA-256
                triggerHashMigration(instance)

                instance
            }
        }

        private fun triggerHashMigration(db: AppDatabase) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dao = db.clipboardDao()
                    val md5Items = dao.getMD5Items()
                    if (md5Items.isNotEmpty()) {
                        android.util.Log.d("AppDatabase", "Found ${md5Items.size} items with MD5 hashes. Migrating to SHA-256...")
                        db.withTransaction {
                            for (item in md5Items) {
                                val path = item.filePath
                                if (path != null) {
                                    val file = java.io.File(path)
                                    if (file.exists()) {
                                        val sha256 = getFileHash(file)
                                        var updatedItem = item.copy(imageHash = sha256)
                                        
                                        if (file.name.startsWith("img_") && file.name.endsWith(".bin") && file.name.length == 40) {
                                            val parentDir = file.parentFile
                                            val newFile = java.io.File(parentDir, "img_$sha256.bin")
                                            if (newFile != file) {
                                                if (newFile.exists()) {
                                                    file.delete()
                                                } else {
                                                    file.renameTo(newFile)
                                                }
                                            }
                                            updatedItem = updatedItem.copy(filePath = newFile.absolutePath)
                                        }
                                        
                                        dao.update(updatedItem)
                                    } else {
                                        // Stale reference, clean up
                                        dao.update(item.copy(imageHash = null, filePath = null))
                                    }
                                }
                            }
                        }
                        android.util.Log.d("AppDatabase", "MD5 to SHA-256 hash migration completed.")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AppDatabase", "Error during hash migration", e)
                }
            }
        }

        private fun getFileHash(file: java.io.File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}