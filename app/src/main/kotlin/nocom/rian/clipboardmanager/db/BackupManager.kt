package nocom.rian.clipboardmanager.db
 
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
 
data class BackupFile(
    val version: Int = 5,
    val exportedAt: Long = System.currentTimeMillis(),
    val clips: List<ClipboardItem>
)
 
object BackupManager {
 
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
 
    // ── Export ────────────────────────────────────────────────────────────────
 
    fun exportZip(
        clips: List<ClipboardItem>,
        outputStream: OutputStream
    ): Result<Int> {
        return try {
            ZipOutputStream(outputStream.buffered()).use { zip ->
 
                // 1. Write backup.json — store only the filename, not the full path
                //    so the backup is portable across devices/reinstalls
                val portableClips = clips.map { item ->
                    if (item.filePath != null)
                        item.copy(filePath = File(item.filePath).name)
                    else
                        item
                }
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write(gson.toJson(BackupFile(clips = portableClips)).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
 
                // 2. Write each image file under images/
                var imageCount = 0
                clips.forEach { item ->
                    val path = item.filePath ?: return@forEach
                    val file = File(path)
                    if (file.exists()) {
                        zip.putNextEntry(ZipEntry("images/${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        imageCount++
                    }
                }
 
                android.util.Log.d(
                    "BackupManager",
                    "Exported ${clips.size} clips, $imageCount images"
                )
            }
            Result.success(clips.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
 
    // ── Import ────────────────────────────────────────────────────────────────
 
    fun importZip(
        inputStream: InputStream,
        imageDir: File
    ): Result<List<ClipboardItem>> {
        return try {
            imageDir.mkdirs()
 
            var clips: List<ClipboardItem>? = null
            var imageCount = 0
 
            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        // Parse the metadata
                        entry.name == "backup.json" -> {
                            val bos = java.io.ByteArrayOutputStream()
                            zip.copyTo(bos)
                            val json = bos.toString("UTF-8")
                            val backup: BackupFile = gson.fromJson(json, BackupFile::class.java)
                            clips = backup.clips.map { it.copy(id = 0) }
                        }
                        
                        // Extract image files — skip if one already exists with the same name
                        entry.name.startsWith("images/") && !entry.isDirectory -> {
                            val fileName = File(entry.name).name
                            val dest = File(imageDir, fileName)
                            if (!dest.exists()) {
                                dest.outputStream().use { zip.copyTo(it) }
                                imageCount++
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
 
            val loadedClips = clips
                ?: return Result.failure(Exception("backup.json not found in ZIP"))
 
            // Reconstruct absolute paths from stored filenames
            val resolved = loadedClips.map { item ->
                if (item.filePath != null)
                    item.copy(filePath = File(imageDir, item.filePath).absolutePath)
                else
                    item
            }
 
            android.util.Log.d(
                "BackupManager",
                "Imported ${resolved.size} clips, $imageCount new images"
            )
            Result.success(resolved)
 
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
