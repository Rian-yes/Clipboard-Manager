package nocom.rian.clipboardmanager

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import nocom.rian.clipboardmanager.databinding.ActivityEditClipBinding
import nocom.rian.clipboardmanager.ui.EditClipViewModel
import java.io.File

class EditClipActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
    }

    private lateinit var binding: ActivityEditClipBinding
    private val viewModel: EditClipViewModel by viewModels()

    private var isAdvancedVisible = false
    private var isImage = false
    private var itemId: Int = -1

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditClipBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.editToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        itemId = intent.getIntExtra(EXTRA_ITEM_ID, -1)
        if (itemId == -1) {
            Toast.makeText(this, "Invalid clip ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupAdvancedToggle()
        observeViewModel()
        viewModel.loadItem(itemId)
    }

    // =========================================================================
    // Menu & Navigation
    // =========================================================================

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

    // =========================================================================
    // ViewModel observation
    // =========================================================================

    private fun observeViewModel() {
        viewModel.item.observe(this) { item ->
            if (item == null) {
                Toast.makeText(this, "Clip not found", Toast.LENGTH_SHORT).show()
                finish()
                return@observe
            }
            isImage = item.mimeType?.startsWith("image/") == true
            loadFields(item)
        }

        viewModel.saveComplete.observe(this) { success ->
            if (success) {
                finish()
            } else {
                Toast.makeText(this, "Failed to save — clip may have been deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================================
    // UI population
    // =========================================================================

    private fun loadFields(item: nocom.rian.clipboardmanager.db.ClipboardItem) {
        if (isImage) {
            binding.imagePreviewCard.visibility = View.VISIBLE
            binding.contentCard.visibility = View.GONE

            val filePath = item.filePath
            if (!filePath.isNullOrBlank()) {
                val file = File(filePath)
                if (file.exists()) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(filePath, opts)
                    opts.inSampleSize = calculateInSampleSize(opts, 1200, 1200)
                    opts.inJustDecodeBounds = false
                    binding.editImagePreview.setImageBitmap(BitmapFactory.decodeFile(filePath, opts))
                }
            }
        } else {
            binding.imagePreviewCard.visibility = View.GONE
            binding.contentCard.visibility = View.VISIBLE
            binding.editClipContent.setText(item.clip ?: "")
        }

        binding.editLabel.setText(item.label ?: "")
        binding.editAppName.setText(item.appName ?: "")
        binding.editPackageName.setText(item.packageName ?: "")
        binding.editWindowLabel.setText(item.windowLabel ?: "")
        binding.editActivityName.setText(item.activityName ?: "")
        binding.editPreviousAppName.setText(item.previousAppName ?: "")
        binding.editPreviousPackageName.setText(item.previousPackageName ?: "")
        binding.editPreviousWindowLabel.setText(item.previousWindowLabel ?: "")
        binding.editPreviousActivityName.setText(item.previousActivityName ?: "")
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

    // =========================================================================
    // Save
    // =========================================================================

    private fun saveAndReturn() {
        if (!isImage) {
            val content = binding.editClipContent.text?.toString() ?: ""
            if (content.isBlank()) {
                binding.editClipContent.error = "Content cannot be empty"
                return
            }
        }

        viewModel.saveItem(
            id                    = itemId,
            newClip               = if (!isImage) binding.editClipContent.text?.toString()?.ifBlank { null } else null,
            newLabel              = binding.editLabel.text?.toString()?.ifBlank { null },
            newAppName            = binding.editAppName.text?.toString()?.ifBlank { null },
            newPackageName        = binding.editPackageName.text?.toString()?.ifBlank { null },
            newWindowLabel        = binding.editWindowLabel.text?.toString()?.ifBlank { null },
            newActivityName       = binding.editActivityName.text?.toString()?.ifBlank { null },
            newPreviousAppName    = binding.editPreviousAppName.text?.toString()?.ifBlank { null },
            newPreviousPackageName= binding.editPreviousPackageName.text?.toString()?.ifBlank { null },
            newPreviousWindowLabel= binding.editPreviousWindowLabel.text?.toString()?.ifBlank { null },
            newPreviousActivityName = binding.editPreviousActivityName.text?.toString()?.ifBlank { null }
        )
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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