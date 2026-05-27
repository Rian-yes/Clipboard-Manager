package nocom.rian.clipboardmanager

import nocom.rian.clipboardmanager.R
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import nocom.rian.clipboardmanager.databinding.ActivityEditClipBinding
import nocom.rian.clipboardmanager.db.ClipboardItem
import java.io.File

class EditClipActivity : AppCompatActivity() {

    // ==========================================
    // Companion Object (Intent Constants & Factory)
    // ==========================================
    companion object {
        const val EXTRA_ITEM_ID       = "extra_item_id"
        const val EXTRA_IS_IMAGE      = "extra_is_image"
        const val EXTRA_CLIP_TEXT     = "extra_clip_text"
        const val EXTRA_FILE_PATH     = "extra_file_path"
        const val EXTRA_LABEL         = "extra_label"
        const val EXTRA_APP_NAME      = "extra_app_name"
        const val EXTRA_PACKAGE_NAME  = "extra_package_name"
        const val EXTRA_WINDOW_LABEL  = "extra_window_label"
        const val EXTRA_ACTIVITY_NAME = "extra_activity_name"
        const val EXTRA_PREVIOUS_APP_NAME      = "extra_previous_app_name"
        const val EXTRA_PREVIOUS_PACKAGE_NAME  = "extra_previous_package_name"
        const val EXTRA_PREVIOUS_WINDOW_LABEL  = "extra_previous_window_label"
        const val EXTRA_PREVIOUS_ACTIVITY_NAME = "extra_previous_activity_name"

        fun createIntent(context: Context, item: ClipboardItem): Intent =
            Intent(context, EditClipActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID,       item.id)
                putExtra(EXTRA_IS_IMAGE,      item.mimeType?.startsWith("image/") == true)
                putExtra(EXTRA_CLIP_TEXT,     item.clip)
                putExtra(EXTRA_FILE_PATH,     item.filePath)
                putExtra(EXTRA_LABEL,         item.label)
                putExtra(EXTRA_APP_NAME,      item.appName)
                putExtra(EXTRA_PACKAGE_NAME,  item.packageName)
                putExtra(EXTRA_WINDOW_LABEL,  item.windowLabel)
                putExtra(EXTRA_ACTIVITY_NAME, item.activityName)
                putExtra(EXTRA_PREVIOUS_APP_NAME,      item.previousAppName)
                putExtra(EXTRA_PREVIOUS_PACKAGE_NAME,  item.previousPackageName)
                putExtra(EXTRA_PREVIOUS_WINDOW_LABEL,  item.previousWindowLabel)
                putExtra(EXTRA_PREVIOUS_ACTIVITY_NAME, item.previousActivityName)
            }
    }

    private lateinit var binding: ActivityEditClipBinding
    private var isAdvancedVisible = false
    private var isImage = false

    // ==========================================
    // Android Activity Lifecycle
    // ==========================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditClipBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.editToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        isImage = intent.getBooleanExtra(EXTRA_IS_IMAGE, false)

        loadFields()
        setupAdvancedToggle()
    }

    // ==========================================
    // Menu & Navigation Hooks
    // ==========================================
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_edit_clip, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save_clip -> { saveAndReturn(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ==========================================
    // Core Tasks & Operations
    // ==========================================
    private fun loadFields() {
        if (isImage) {
            binding.imagePreviewCard.visibility = View.VISIBLE
            binding.contentCard.visibility = View.GONE

            val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
            if (!filePath.isNullOrBlank()) {
                val file = File(filePath)
                if (file.exists()) {
                    // Sample down to avoid OOM on large images
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(filePath, opts)
                    opts.inSampleSize = calculateInSampleSize(opts, 1200, 1200)
                    opts.inJustDecodeBounds = false
                    binding.editImagePreview.setImageBitmap(
                        BitmapFactory.decodeFile(filePath, opts)
                    )
                }
            }
        } else {
            binding.imagePreviewCard.visibility = View.GONE
            binding.contentCard.visibility = View.VISIBLE
            binding.editClipContent.setText(intent.getStringExtra(EXTRA_CLIP_TEXT) ?: "")
        }

        binding.editLabel.setText(intent.getStringExtra(EXTRA_LABEL) ?: "")
        binding.editAppName.setText(intent.getStringExtra(EXTRA_APP_NAME) ?: "")
        binding.editPackageName.setText(intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "")
        binding.editWindowLabel.setText(intent.getStringExtra(EXTRA_WINDOW_LABEL) ?: "")
        binding.editActivityName.setText(intent.getStringExtra(EXTRA_ACTIVITY_NAME) ?: "")
        binding.editPreviousAppName.setText(intent.getStringExtra(EXTRA_PREVIOUS_APP_NAME) ?: "")
        binding.editPreviousPackageName.setText(intent.getStringExtra(EXTRA_PREVIOUS_PACKAGE_NAME) ?: "")
        binding.editPreviousWindowLabel.setText(intent.getStringExtra(EXTRA_PREVIOUS_WINDOW_LABEL) ?: "")
        binding.editPreviousActivityName.setText(intent.getStringExtra(EXTRA_PREVIOUS_ACTIVITY_NAME) ?: "")
    }

    private fun setupAdvancedToggle() {
        binding.btnToggleAdvanced.setOnClickListener {
            isAdvancedVisible = !isAdvancedVisible
            if (isAdvancedVisible) {
                binding.advancedCard.visibility = View.VISIBLE
                binding.btnToggleAdvanced.text = "Hide Advanced Fields"
                binding.btnToggleAdvanced.setIconResource(R.drawable.ic_arrow_up)
            } else {
                binding.advancedCard.visibility = View.GONE
                binding.btnToggleAdvanced.text = "Advanced Fields"
                binding.btnToggleAdvanced.setIconResource(R.drawable.ic_arrow_down)
            }
        }
    }

    private fun saveAndReturn() {
        // Validate: text clips must not be blank
        if (!isImage) {
            val content = binding.editClipContent.text?.toString() ?: ""
            if (content.isBlank()) {
                binding.editClipContent.error = "Content cannot be empty"
                return
            }
        }

        val result = Intent().apply {
            putExtra(EXTRA_ITEM_ID, intent.getIntExtra(EXTRA_ITEM_ID, -1))
            // For image clips, clip text stays null
            if (!isImage) {
                putExtra(EXTRA_CLIP_TEXT,
                    binding.editClipContent.text?.toString()?.ifBlank { null })
            }
            putExtra(EXTRA_LABEL,
                binding.editLabel.text?.toString()?.ifBlank { null })
            putExtra(EXTRA_APP_NAME,
                binding.editAppName.text?.toString()?.ifBlank { null })
            putExtra(EXTRA_PACKAGE_NAME,
                binding.editPackageName.text?.toString()?.ifBlank { null })
            putExtra(EXTRA_WINDOW_LABEL,
                binding.editWindowLabel.text?.toString()?.ifBlank { null })
            putExtra(EXTRA_ACTIVITY_NAME,
                binding.editActivityName.text?.toString()?.ifBlank { null })
            putExtra(EXTRA_PREVIOUS_APP_NAME,
                binding.editPreviousAppName.text?.toString()?.ifBlank { null })
            putExtra(EXTRA_PREVIOUS_PACKAGE_NAME,
                binding.editPreviousPackageName.text?.toString()?.ifBlank { null })
            putExtra(EXTRA_PREVIOUS_WINDOW_LABEL,
                binding.editPreviousWindowLabel.text?.toString()?.ifBlank { null })
            putExtra(EXTRA_PREVIOUS_ACTIVITY_NAME,
                binding.editPreviousActivityName.text?.toString()?.ifBlank { null })    
        }
        setResult(RESULT_OK, result)
        finish()
    }

    // ==========================================
    // Low-Level Private Helpers
    // ==========================================
    private fun calculateInSampleSize(
        options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
