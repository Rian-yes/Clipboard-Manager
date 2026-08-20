package nocom.rian.clipboardmanager

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import nocom.rian.clipboardmanager.db.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BackupIntegrityTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ClipboardDao
    private lateinit var context: Context
    private lateinit var tempImageDir: File

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.clipboardDao()
        tempImageDir = File(context.cacheDir, "test_images")
        tempImageDir.mkdirs()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
        tempImageDir.deleteRecursively()
    }

    @Test
    fun testExportAndImport11000Clips() {
        val testClipsCount = 11247
        val batch = mutableListOf<ClipboardItem>()
        for (i in 1..testClipsCount) {
            batch.add(
                ClipboardItem(
                    id = 0,
                    clip = "Test clip content $i",
                    appName = "App$i",
                    packageName = "com.test.app$i",
                    timestamp = System.currentTimeMillis() + i
                )
            )
        }
        
        batch.chunked(500).forEach { chunk ->
            kotlinx.coroutines.runBlocking {
                dao.insertAll(chunk)
            }
        }

        val count = kotlinx.coroutines.runBlocking { dao.getTotalClipCount() }
        assertEquals(testClipsCount, count)

        val out = ByteArrayOutputStream()
        val exportResult = BackupManager.exportZip(db, out, null)
        assertTrue(exportResult.isSuccess)
        assertEquals(testClipsCount, exportResult.getOrThrow())

        kotlinx.coroutines.runBlocking { dao.deleteAll() }
        assertEquals(0, kotlinx.coroutines.runBlocking { dao.getTotalClipCount() })

        val input = ByteArrayInputStream(out.toByteArray())
        val importResult = BackupManager.importZip(input, tempImageDir)
        assertTrue(importResult.isSuccess)
        
        val restoredClips = importResult.getOrThrow().clips
        assertEquals(testClipsCount, restoredClips.size)

        restoredClips.chunked(500).forEach { chunk ->
            kotlinx.coroutines.runBlocking {
                dao.insertAll(chunk)
            }
        }

        val finalCount = kotlinx.coroutines.runBlocking { dao.getTotalClipCount() }
        assertEquals(testClipsCount, finalCount)

        val allItems = kotlinx.coroutines.runBlocking { dao.getAllClipsOnce() }
        assertEquals(testClipsCount, allItems.size)
        val sample = allItems.find { it.clip == "Test clip content 500" }
        assertNotNull(sample)
        assertEquals("App500", sample?.appName)
        assertEquals("com.test.app500", sample?.packageName)
    }

    @Test
    fun testImageBackupIntegrityAndDeduplication() {
        val imgFile = File(tempImageDir, "source_image.png")
        imgFile.writeText("fake-image-bytes")

        val clip1 = ClipboardItem(id = 1, clip = "Image Clip 1", filePath = imgFile.absolutePath)
        val clip2 = ClipboardItem(id = 2, clip = "Image Clip 2", filePath = imgFile.absolutePath)

        kotlinx.coroutines.runBlocking {
            dao.insert(clip1)
            dao.insert(clip2)
        }

        val out = ByteArrayOutputStream()
        val exportResult = BackupManager.exportZip(db, out, null)
        assertTrue(exportResult.isSuccess)

        val input = ByteArrayInputStream(out.toByteArray())
        val importResult = BackupManager.importZip(input, tempImageDir)
        assertTrue(importResult.isSuccess)

        val restored = importResult.getOrThrow().clips
        assertEquals(2, restored.size)

        val firstPath = restored[0].filePath
        val secondPath = restored[1].filePath
        assertNotNull(firstPath)
        assertNotNull(secondPath)
        assertEquals(firstPath, secondPath) // Deduplicated path

        val restoredFile = File(firstPath!!)
        assertTrue(restoredFile.exists())
        assertEquals("fake-image-bytes", restoredFile.readText())
    }

    @Test
    fun testIntentSenderBackupAndRestore() {
        val intentJson = """{
            "${AdvancedActivity.KEY_INTENT_SENDER_ENABLED}": true,
            "${AdvancedActivity.KEY_INTENT_SENDER_ACTION}": "com.test.ACTION",
            "${AdvancedActivity.KEY_INTENT_SENDER_PACKAGE}": "com.test.package",
            "${AdvancedActivity.KEY_INTENT_SENDER_EXTRA_KEY}": "my_extra",
            "${AdvancedActivity.KEY_INTENT_SENDER_ON_UPDATE}": true
        }""".trimIndent()

        val out = ByteArrayOutputStream()
        val exportResult = BackupManager.exportZip(db, out, null, intentJson)
        assertTrue(exportResult.isSuccess)

        val input = ByteArrayInputStream(out.toByteArray())
        val importResult = BackupManager.importZip(input, tempImageDir)
        assertTrue(importResult.isSuccess)

        val restoredIntentSenderJson = importResult.getOrThrow().intentSenderJson
        assertNotNull(restoredIntentSenderJson)

        val jsonObject = com.google.gson.JsonParser.parseString(restoredIntentSenderJson!!).asJsonObject
        assertTrue(jsonObject.get(AdvancedActivity.KEY_INTENT_SENDER_ENABLED).asBoolean)
        assertEquals("com.test.ACTION", jsonObject.get(AdvancedActivity.KEY_INTENT_SENDER_ACTION).asString)
        assertEquals("com.test.package", jsonObject.get(AdvancedActivity.KEY_INTENT_SENDER_PACKAGE).asString)
        assertEquals("my_extra", jsonObject.get(AdvancedActivity.KEY_INTENT_SENDER_EXTRA_KEY).asString)
        assertTrue(jsonObject.get(AdvancedActivity.KEY_INTENT_SENDER_ON_UPDATE).asBoolean)
    }

    @Test
    fun testImportDeduplicationLogic() {
        val clip = ClipboardItem(id = 0, clip = "Unique text content", packageName = "com.test")
        kotlinx.coroutines.runBlocking { dao.insert(clip) }

        val backupClips = listOf(
            ClipboardItem(id = 0, clip = "Unique text content", packageName = "com.test"),
            ClipboardItem(id = 0, clip = "New content", packageName = "com.test")
        )

        kotlinx.coroutines.runBlocking {
            val existing = dao.getAllClipsOnce()
            val existingTexts = existing.mapNotNull { it.clip }.toHashSet()
            val existingImageKeys = existing
                .filter { it.imageHash != null }
                .mapTo(HashSet()) { "${it.imageHash}|${it.packageName}" }

            var addedCount = 0
            var skippedCount = 0

            val newClips = backupClips.filter { item ->
                val isDup = when {
                    item.clip != null -> item.clip in existingTexts
                    item.imageHash != null -> "${item.imageHash}|${item.packageName}" in existingImageKeys
                    else -> false
                }
                if (isDup) {
                    skippedCount++
                    false
                } else {
                    item.clip?.let { existingTexts.add(it) }
                    item.imageHash?.let { existingImageKeys.add("$it|${item.packageName}") }
                    addedCount++
                    true
                }
            }

            assertEquals(1, addedCount)
            assertEquals(1, skippedCount)
            assertEquals(1, newClips.size)
            assertEquals("New content", newClips[0].clip)
        }
    }

    @Test
    fun testCorruptAndTruncatedBackupDetect() {
        val badBytes = "this-is-garbage-zip-content".toByteArray()
        val input = ByteArrayInputStream(badBytes)
        val importResult = BackupManager.importZip(input, tempImageDir)
        assertTrue(importResult.isFailure)
    }
}
