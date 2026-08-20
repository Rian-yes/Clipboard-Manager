package nocom.rian.clipboardmanager.db

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupManifest(
    val formatVersion: Int = 6,
    val exportedAt: Long = System.currentTimeMillis(),
    val clipCount: Int,
    val imageCount: Int,
    val ruleCount: Int,
    val completed: Boolean = true
)

data class BackupResult(
    val clips: List<ClipboardItem>,
    val rulesJson: String?,
    val intentSenderJson: String? = null
)

object BackupManager {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private fun getFileMD5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
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

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportZip(
        database: AppDatabase,
        outputStream: OutputStream,
        rulesJson: String? = null,
        intentSenderJson: String? = null,
        onProgress: ((exportedClips: Int, totalClips: Int) -> Unit)? = null
    ): Result<Int> {
        return try {
            val dao = database.clipboardDao()
            val totalClips = kotlinx.coroutines.runBlocking { dao.getTotalClipCount() }
            val rulesCount = if (!rulesJson.isNullOrBlank()) {
                try {
                    val type = AutomationRuleListTypeToken().type
                    val list: List<AutomationRule> = gson.fromJson(rulesJson, type)
                    list.size
                } catch (e: Exception) {
                    1
                }
            } else 0

            var clipsSeen = 0
            var clipsWritten = 0
            var imagesWritten = 0

            val writtenImages = mutableSetOf<String>()
            val pathToMd5 = mutableMapOf<String, String>()

            ZipOutputStream(outputStream.buffered()).use { zip ->
                val uniqueImagesToExport = mutableSetOf<String>()
                
                var offset = 0
                val limit = 500
                while (offset < totalClips) {
                    val chunk = kotlinx.coroutines.runBlocking { dao.getClipsChunk(limit, offset) }
                    if (chunk.isEmpty()) break
                    chunk.forEach { item ->
                        item.filePath?.let { path ->
                            val file = File(path)
                            if (file.exists()) {
                                val md5 = pathToMd5.getOrPut(file.absolutePath) { getFileMD5(file) }
                                uniqueImagesToExport.add(md5)
                            }
                        }
                    }
                    offset += limit
                }
                val expectedImageCount = uniqueImagesToExport.size

                // Write manifest
                zip.putNextEntry(ZipEntry("manifest.json"))
                val manifest = BackupManifest(
                    clipCount = totalClips,
                    imageCount = expectedImageCount,
                    ruleCount = rulesCount,
                    completed = true
                )
                zip.write(gson.toJson(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // Write backup_stream.json using streaming JsonWriter
                zip.putNextEntry(ZipEntry("backup_stream.json"))
                val writer = JsonWriter(OutputStreamWriter(zip, Charsets.UTF_8))
                writer.setIndent("  ")
                writer.beginArray()

                offset = 0
                while (offset < totalClips) {
                    val chunk = kotlinx.coroutines.runBlocking { dao.getClipsChunk(limit, offset) }
                    if (chunk.isEmpty()) break
                    clipsSeen += chunk.size

                    chunk.forEach { item ->
                        val portableItem = if (item.filePath != null) {
                            val originalFile = File(item.filePath)
                            if (originalFile.exists()) {
                                val md5 = pathToMd5.getOrPut(originalFile.absolutePath) { getFileMD5(originalFile) }
                                item.copy(filePath = "img_$md5.bin")
                            } else {
                                item.copy(filePath = null)
                            }
                        } else {
                            item
                        }
                        gson.toJson(portableItem, ClipboardItem::class.java, writer)
                        clipsWritten++
                        onProgress?.invoke(clipsWritten, totalClips)
                    }
                    offset += limit
                }
                writer.endArray()
                writer.flush()
                zip.closeEntry()

                if (!rulesJson.isNullOrBlank()) {
                    zip.putNextEntry(ZipEntry("automation_rules.json"))
                    zip.write(rulesJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                if (!intentSenderJson.isNullOrBlank()) {
                    zip.putNextEntry(ZipEntry("intent_sender_settings.json"))
                    zip.write(intentSenderJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                offset = 0
                while (offset < totalClips) {
                    val chunk = kotlinx.coroutines.runBlocking { dao.getClipsChunk(limit, offset) }
                    if (chunk.isEmpty()) break
                    chunk.forEach { item ->
                        val path = item.filePath ?: return@forEach
                        val file = File(path)
                        if (file.exists()) {
                            val md5 = pathToMd5.getOrPut(file.absolutePath) { getFileMD5(file) }
                            val zipName = "images/img_$md5.bin"
                            if (!writtenImages.contains(zipName)) {
                                zip.putNextEntry(ZipEntry(zipName))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                                writtenImages.add(zipName)
                                imagesWritten++
                            }
                        }
                    }
                    offset += limit
                }

                if (clipsSeen != totalClips || clipsWritten != totalClips) {
                    throw IllegalStateException("Data integrity mismatch: Clips read=$clipsSeen, Written=$clipsWritten, Expected=$totalClips")
                }
                if (imagesWritten != expectedImageCount) {
                    throw IllegalStateException("Data integrity mismatch: Images written=$imagesWritten, Expected=$expectedImageCount")
                }

                android.util.Log.d(
                    "BackupManager",
                    "Export completed. clips=$clipsWritten, images=$imagesWritten, rules=$rulesCount"
                )
            }
            Result.success(clipsWritten)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    fun importZip(
        inputStream: InputStream,
        imageDir: File
    ): Result<BackupResult> {
        val tempFilesToClean = mutableListOf<File>()
        return try {
            imageDir.mkdirs()

            var manifest: BackupManifest? = null
            var importedRulesJson: String? = null
            var importedIntentSenderJson: String? = null

            val zipPathToTempFile = mutableMapOf<String, File>()

            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    when {
                        entryName == "manifest.json" -> {
                            val bos = java.io.ByteArrayOutputStream()
                            zip.copyTo(bos)
                            manifest = gson.fromJson(bos.toString("UTF-8"), BackupManifest::class.java)
                        }

                        entryName == "automation_rules.json" -> {
                            val bos = java.io.ByteArrayOutputStream()
                            zip.copyTo(bos)
                            importedRulesJson = bos.toString("UTF-8")
                        }


                        entryName == "intent_sender_settings.json" -> {
                            val bos = java.io.ByteArrayOutputStream()
                            zip.copyTo(bos)
                            importedIntentSenderJson = bos.toString("UTF-8")
                        }
                        entryName == "backup.json" || entryName == "backup_stream.json" -> {
                            val tempFile = File.createTempFile("imported_backup_", ".json")
                            tempFilesToClean.add(tempFile)
                            tempFile.outputStream().use { zip.copyTo(it) }
                            zipPathToTempFile[entryName] = tempFile
                        }

                        entryName.startsWith("images/") && !entry.isDirectory -> {
                            val fileName = File(entryName).name
                            val dest = File(imageDir, "temp_import_${UUID.randomUUID()}_$fileName")
                            val canonicalDir = imageDir.canonicalPath
                            if (!dest.canonicalPath.startsWith(canonicalDir + File.separator)) {
                                throw SecurityException("Path traversal attempt detected in ZIP entry: $entryName")
                            }
                            dest.outputStream().use { zip.copyTo(it) }
                            tempFilesToClean.add(dest)
                            zipPathToTempFile[entryName] = dest
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val jsonEntryName = when {
                zipPathToTempFile.containsKey("backup_stream.json") -> "backup_stream.json"
                zipPathToTempFile.containsKey("backup.json") -> "backup.json"
                else -> throw IllegalArgumentException("Missing backup.json or backup_stream.json in backup archive")
            }

            val backupTempFile = zipPathToTempFile[jsonEntryName]!!
            val parsedClips = mutableListOf<ClipboardItem>()

            JsonReader(InputStreamReader(backupTempFile.inputStream(), Charsets.UTF_8)).use { reader ->
                if (jsonEntryName == "backup_stream.json") {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val item = gson.fromJson<ClipboardItem>(reader, ClipboardItem::class.java)
                        parsedClips.add(item)
                    }
                    reader.endArray()
                } else {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name == "clips") {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val item = gson.fromJson<ClipboardItem>(reader, ClipboardItem::class.java)
                                parsedClips.add(item)
                            }
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }

            manifest?.let { m ->
                if (m.clipCount != parsedClips.size) {
                    throw IllegalStateException("Data integrity mismatch: Manifest expected ${m.clipCount} clips, but found ${parsedClips.size}")
                }
            }

            val zipPathToPermanentFile = mutableMapOf<String, File>()
            val resolvedClips = parsedClips.map { item ->
                if (item.filePath != null) {
                    val relativeZipPath = if (item.filePath.startsWith("images/")) {
                        item.filePath
                    } else if (item.filePath.startsWith("img_") && item.filePath.endsWith(".bin")) {
                        "images/${item.filePath}"
                    } else {
                        "images/${item.filePath}"
                    }

                    val tempFile = zipPathToTempFile[relativeZipPath]
                    if (tempFile != null && tempFile.exists()) {
                        val permFile = zipPathToPermanentFile.getOrPut(relativeZipPath) {
                            val md5 = getFileMD5(tempFile)
                            val target = File(imageDir, "img_$md5.bin")
                            if (!target.exists()) {
                                tempFile.copyTo(target, overwrite = true)
                            }
                            target
                        }
                        item.copy(id = 0, filePath = permFile.absolutePath, imageHash = getFileMD5(permFile))
                    } else {
                        throw IllegalStateException("Image reference $relativeZipPath not found in ZIP archive")
                    }
                } else {
                    item.copy(id = 0)
                }
            }

            tempFilesToClean.forEach {
                if (it.exists()) {
                    it.delete()
                }
            }

            Result.success(
                BackupResult(
                    clips = resolvedClips,
                    rulesJson = importedRulesJson,
                    intentSenderJson = importedIntentSenderJson
                )
            )
        } catch (e: Exception) {
            tempFilesToClean.forEach {
                if (it.exists()) {
                    it.delete()
                }
            }
            Result.failure(e)
        }
    }
}
