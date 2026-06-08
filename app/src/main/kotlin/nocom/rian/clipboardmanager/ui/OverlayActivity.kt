package nocom.rian.clipboardmanager.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nocom.rian.clipboardmanager.R
import nocom.rian.clipboardmanager.db.AppDatabase
import nocom.rian.clipboardmanager.db.ClipboardDao
import nocom.rian.clipboardmanager.db.ClipboardRepository

class OverlayActivity : AppCompatActivity() {

    private lateinit var repository: ClipboardRepository
    private lateinit var clipboardDao: ClipboardDao
    private lateinit var overlayAdapter: ClipboardAdapter
    
    private var allClipsJob: Job? = null
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.overlay_clipboard_dialog)

        // Matches the exact DB initialization context found in your ClipboardService
        val database = AppDatabase.getDatabase(applicationContext)
        clipboardDao = database.clipboardDao()
        repository = ClipboardRepository(clipboardDao)

        setupWindowLayout()
        setupRecyclerView()
        setupSearchAndDatabaseFlows()
    }

    private fun setupWindowLayout() {
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            
            val params = attributes
            params.dimAmount = 0.45f
            params.gravity = Gravity.CENTER
            
            // Keeps your exact overlay card dimension rules (92% width, 55% height)
            val metrics = resources.displayMetrics
            params.width = (metrics.widthPixels * 0.92).toInt()
            params.height = (metrics.heightPixels * 0.55).toInt()
            params.windowAnimations = android.R.style.Animation_Dialog
            attributes = params
        }
        
        // Closes the popup if the user taps outside the dialog borders
        setFinishOnTouchOutside(true)
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.overlayClipboardRecycler)

        val displayConfig = ClipDisplayConfig(
            showAppName = true, showPackageName = false, showWindowLabel = true,
            showActivityName = false, showLabel = true, showTechnicalDetails = false
        )

        overlayAdapter = ClipboardAdapter(
            onPinClick = { item -> 
                lifecycleScope.launch(Dispatchers.IO) {
                    clipboardDao.update(item.copy(isPinned = !item.isPinned))
                }
            },
            onExpandClick = { _ -> },
            onLongClick = { _ -> },
            onDeleteClick = { item -> 
                lifecycleScope.launch(Dispatchers.IO) {
                    clipboardDao.delete(item)
                }
            },
            onCopyClick = { item ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                
                // Check if the item contains an image path directly from the item object itself
                if (!item.filePath.isNullOrEmpty()) {
                    val file = java.io.File(item.filePath)
                    if (file.exists()) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this, "${packageName}.fileprovider", file
                        )
                        clipboard.setPrimaryClip(android.content.ClipData.newUri(contentResolver, "Copied Image", uri))
                    }
                } else {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Copied Clip", item.clip))
                }
                finish() // Smoothly close the dialog overlay window after copying
            },
            onFavoriteClick = { item -> 
                lifecycleScope.launch(Dispatchers.IO) {
                    clipboardDao.update(item.copy(isFavorite = !item.isFavorite))
                }
            },
            onSensitiveClick = { item -> 
                lifecycleScope.launch(Dispatchers.IO) {
                    clipboardDao.update(item.copy(isSensitive = !item.isSensitive))
                }
            },
            onLabelEditClick = { _ -> },
            onRevealClick = { _ -> },
            onEditClick = { _ -> },
            initialConfig = displayConfig
        )

        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@OverlayActivity)
            adapter = overlayAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupSearchAndDatabaseFlows() {
        val searchEditText = findViewById<EditText>(R.id.overlaySearchInput)

        // Collect global un-filtered clip database streams
        allClipsJob = lifecycleScope.launch {
            repository.allClips.collect { clips ->
                if (searchEditText.text.toString().trim().isEmpty()) {
                    overlayAdapter.submitList(clips)
                }
            }
        }

        // Native copy, paste selection popups, and handles work perfectly here out of the box!
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val query = s.toString().trim()

                searchJob = lifecycleScope.launch(Dispatchers.Main) {
                    val flow = if (query.isEmpty()) {
                        repository.allClips
                    } else {
                        repository.searchClips(query)
                    }

                    flow.collect { clips ->
                        overlayAdapter.submitList(clips)
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
}
