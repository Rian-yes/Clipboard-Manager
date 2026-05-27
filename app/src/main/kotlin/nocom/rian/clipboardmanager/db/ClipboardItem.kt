package nocom.rian.clipboardmanager.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "clipboardmanager")
data class ClipboardItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "date") val date: String = getCurrentDate(),
    @ColumnInfo(name = "time") val time: String = getCurrentTime(),
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "clip") val clip: String?,
    @ColumnInfo(name = "label") val label: String? = null,
    @ColumnInfo(name = "appName") val appName: String? = "Unknown",
    @ColumnInfo(name = "packageName") val packageName: String? = "Unknown",
    @ColumnInfo(name = "window_label") val windowLabel: String? = null,
    @ColumnInfo(name = "activity_name") val activityName: String? = null,
    @ColumnInfo(name = "previous_app_name") val previousAppName: String? = null,
    @ColumnInfo(name = "previous_package_name") val previousPackageName: String? = null,
    @ColumnInfo(name = "previous_window_label") val previousWindowLabel: String? = null,
    @ColumnInfo(name = "previous_activity_name") val previousActivityName: String? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String? = "text/plain",
    @ColumnInfo(name = "image_hash") val imageHash: String? = null,
    @ColumnInfo(name = "file_path") val filePath: String? = null,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "is_sensitive") val isSensitive: Boolean = false,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false
) {
    companion object {
        fun getCurrentDate(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        fun getCurrentTime(): String =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
}