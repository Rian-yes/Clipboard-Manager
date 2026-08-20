package nocom.rian.clipboardmanager

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import android.widget.AutoCompleteTextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import nocom.rian.clipboardmanager.databinding.ActivityMainBinding
import nocom.rian.clipboardmanager.db.ClipboardItem
import nocom.rian.clipboardmanager.service.ClipboardService
import nocom.rian.clipboardmanager.ui.ClipDisplayConfig
import nocom.rian.clipboardmanager.ui.ClipboardAdapter
import nocom.rian.clipboardmanager.ui.ClipboardViewModel
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ClipboardViewModel by viewModels()
    private lateinit var clipboardAdapter: ClipboardAdapter
    
    private var shizukuSnackbar: Snackbar? = null
    private var previousClipCount = 0
    
    // Guard flag to prevent calculation feedback loops on large datasets (9,000+ items)
    private var isDraggingScrollbar = false

    // =========================================================================
    // 1. Activity Lifecycle & System Overrides
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
    
        if (!ClipboardService.isRunning) {
            getSystemService(NotificationManager::class.java)?.cancel(1)
        }

        // --- Run garbage collection for all physical file directories ---
        val imageDir = java.io.File(getExternalFilesDir(null), "clipboard_images")
        val mediaDir = java.io.File(getExternalFilesDir(null), "clipboard_media")
        
        // Pass as many directories as you need separated by commas
        viewModel.cleanupOrphanedFiles(imageDir, mediaDir)
        
        requestNotificationPermissionThenStart()
        setupBackPressHandler()
        setupRecyclerView()
        setupSwipeToDelete()     
        setupClickListeners()
        observeViewModel()
        updateFilterIndicator()
        
    }

    override fun onResume() {
        super.onResume()
        applyManualEntryVisibility()
        checkAndShowShizukuStatus()
        clipboardAdapter.setDisplayConfig(buildDisplayConfig())
        checkOverlayPermissionGuard()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_permissions -> {
                startActivity(Intent(this, PermissionActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_clear_unpinned -> {
                showClearUnpinnedDialog()
                true
            }
            R.id.action_advanced -> {
                startActivity(Intent(this, AdvancedActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    // =========================================================================
    // 2. UI & Component Initialization
    // =========================================================================
        
    private fun setupRecyclerView() {
        clipboardAdapter = ClipboardAdapter(
            onPinClick       = { item -> viewModel.togglePin(item) },
            onExpandClick    = { id   -> viewModel.toggleExpand(id) },
            onLongClick      = { item -> viewModel.toggleSelection(item) },
            onDeleteClick    = { item ->
                val isImage = item.mimeType?.startsWith("image/") == true
    
                viewModel.delete(item, deleteFile = false)
    
                val snackbar = Snackbar.make(binding.root, "Clip deleted", Snackbar.LENGTH_LONG)
                snackbar.setAction("Undo") { viewModel.restoreClip(item) }
    
                snackbar.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (event != DISMISS_EVENT_ACTION && isImage) {
                            viewModel.deleteImageFile(item)
                        }
                    }
                })
                snackbar.show()
            },
            onCopyClick      = { item -> handleCopyAction(item) },
            onFavoriteClick  = { item -> viewModel.toggleFavorite(item) },
            onSensitiveClick = { item -> viewModel.toggleSensitive(item) },
            onLabelEditClick = { item -> showLabelDialog(item) },
            onRevealClick    = { id   -> viewModel.toggleRevealSensitive(id) },
            onEditClick      = { item ->
                startActivity(
                    Intent(this, EditClipActivity::class.java)
                        .putExtra(EditClipActivity.EXTRA_ITEM_ID, item.id)
                )
            },
            onShareClick     = { item -> nocom.rian.clipboardmanager.ui.shareClip(this, item) }
        )
    
        binding.recyclerView.apply {
            adapter = clipboardAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true)
            itemAnimator = DefaultItemAnimator()
    
            clipboardAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                    val lm = layoutManager as? LinearLayoutManager
                    if (toPosition == 0 && lm?.findFirstCompletelyVisibleItemPosition() == 0) {
                        post { lm.scrollToPositionWithOffset(0, 0) }
                    }
                }
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    val lm = layoutManager as? LinearLayoutManager
                    if (positionStart == 0 && lm?.findFirstCompletelyVisibleItemPosition() == 0) {
                        post { lm.scrollToPositionWithOffset(0, 0) }
                    }
                }
            })
        }
    
        setupCustomScrollbar()
    }

    private fun setupCustomScrollbar() {
        val recycler = binding.recyclerView 
        val thumb = binding.scrollbarThumb
        val track = binding.scrollbarTrack
        val dateBubbleCard = binding.scrollbarDateBubbleCard
        val dateBubble = binding.scrollbarDateBubble
    
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isDraggingScrollbar) return
    
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val totalItemCount = lm.itemCount
                if (totalItemCount == 0) {
                    track.visibility = View.GONE
                    return
                }
    
                val firstVisiblePos = lm.findFirstVisibleItemPosition()
                val lastVisiblePos = lm.findLastVisibleItemPosition()
                val visibleItemCount = lastVisiblePos - firstVisiblePos + 1
    
                if (visibleItemCount < totalItemCount && firstVisiblePos != RecyclerView.NO_POSITION) {
                    track.visibility = View.VISIBLE
                    
                    track.post {
                        val maxTrackHeight = track.height - thumb.height
                        if (maxTrackHeight > 0) {
                            val denominator = (totalItemCount - visibleItemCount).coerceAtLeast(1)
                            val scrollPercent = firstVisiblePos.toFloat() / denominator.toFloat()
                            thumb.translationY = (scrollPercent * maxTrackHeight).coerceIn(0f, maxTrackHeight.toFloat())
                        }
                    }
                } else {
                    track.visibility = View.GONE
                }
            }
        })
    
        track.setOnTouchListener { _, event ->
            val lm = recycler.layoutManager as? LinearLayoutManager ?: return@setOnTouchListener false
            val totalItemCount = lm.itemCount
            if (totalItemCount == 0) return@setOnTouchListener false
    
            val maxTrackHeight = track.height - thumb.height
            if (maxTrackHeight <= 0) return@setOnTouchListener false
    
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingScrollbar = true
                    thumb.isPressed = true
                    dateBubbleCard.visibility = View.VISIBLE
                    handleScrollbarTouch(event.y, maxTrackHeight, lm, totalItemCount, thumb, dateBubbleCard, dateBubble)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleScrollbarTouch(event.y, maxTrackHeight, lm, totalItemCount, thumb, dateBubbleCard, dateBubble)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingScrollbar = false
                    thumb.isPressed = false
                    dateBubbleCard.visibility = View.INVISIBLE
                    true
                }
                else -> false
            }
        }
    }
    
    private fun handleScrollbarTouch(
        touchY: Float, 
        maxTrackHeight: Int, 
        lm: LinearLayoutManager, 
        totalItemCount: Int,
        thumb: View,
        dateBubbleCard: com.google.android.material.card.MaterialCardView,
        dateBubble: android.widget.TextView
    ) {
        val recycler = binding.recyclerView
        
        var correctedY = touchY - (thumb.height / 2)
        correctedY = correctedY.coerceIn(0f, maxTrackHeight.toFloat())
        thumb.translationY = correctedY
    
        val scrollPercent = correctedY / maxTrackHeight.toFloat()
        val targetPosition = (scrollPercent * totalItemCount).toInt().coerceIn(0, totalItemCount - 1)
    
        lm.scrollToPositionWithOffset(targetPosition, 0)
    
        // Pull item data matching the current target scroll index location
        val item = clipboardAdapter.currentList.getOrNull(targetPosition)
        if (item != null) {
            val itemCalendar = java.util.Calendar.getInstance().apply { timeInMillis = item.timestamp }
            val currentCalendar = java.util.Calendar.getInstance()
            
            // Dynamic condition validation checking if item year matches current device year
            val formatPattern = if (itemCalendar.get(java.util.Calendar.YEAR) == currentCalendar.get(java.util.Calendar.YEAR)) {
                "dd MMM"       // Current year -> e.g., "14 Oct"
            } else {
                "dd MMM yyyy"  // Different year -> e.g., "14 Oct 2025"
            }
            
            dateBubble.text = java.text.SimpleDateFormat(formatPattern, java.util.Locale.getDefault()).format(java.util.Date(item.timestamp))
        }
    
        // Anchor calculation logic positions the bubble card centered with the thumb
        val bubbleHeight = dateBubbleCard.height
        val targetBubbleY = correctedY + (thumb.height / 2f) - (bubbleHeight / 2f)
        
        // Constraint clamps avoid layout boundary overlapping at edges
        val maxAllowedY = (recycler.height - bubbleHeight).toFloat()
        dateBubbleCard.translationY = targetBubbleY.coerceIn(0f, maxAllowedY)
    }
    
    private fun setupClickListeners() {
        binding.btnAddClip.setOnClickListener {
            val text = binding.editClipInput.text.toString()
            if (text.isNotBlank()) {
                viewModel.insert(text)
                binding.editClipInput.text?.clear()
            }
        }
    
        binding.btnDeleteSelected.setOnClickListener { showDeleteSelectedDialog() }
        binding.btnPinSelected.setOnClickListener { viewModel.togglePinSelectedClips() }
    
        binding.btnSelectAll.setOnClickListener {
            val allIds = (viewModel.filteredClips.value ?: emptyList()).map { it.id }.toSet()
            if (viewModel.selectedIds.value == allIds) {
                viewModel.clearSelection()
            } else {
                viewModel.selectAll(viewModel.filteredClips.value ?: emptyList())
            }
        }
    
        binding.editSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    
        binding.btnFilter.setOnClickListener { showFilterBottomSheet() }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.isSelectionMode.value) {
                        viewModel.clearSelection()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }
    
    private fun setupSwipeToDelete() {
        val deleteIcon = androidx.core.content.ContextCompat.getDrawable(
            this, R.drawable.ic_delete
        )!!.mutate()
        androidx.core.graphics.drawable.DrawableCompat.setTint(
            deleteIcon,
            androidx.core.content.ContextCompat.getColor(this, R.color.md_theme_onError)
        )
        
        val background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.md_theme_error))
        }
    
        val swipeCallback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            0,
            androidx.recyclerview.widget.ItemTouchHelper.LEFT or
            androidx.recyclerview.widget.ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = if (viewModel.isSelectionMode.value) 0
                     else super.getSwipeDirs(recyclerView, viewHolder)

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val item = clipboardAdapter.currentList.getOrNull(position) ?: return
                val isImage = item.mimeType?.startsWith("image/") == true

                viewModel.delete(item, deleteFile = false)

                val snackbar = Snackbar.make(binding.root, "Clip deleted", Snackbar.LENGTH_LONG)
                snackbar.setAction("Undo") { viewModel.restoreClip(item) }

                snackbar.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (event != DISMISS_EVENT_ACTION && isImage) {
                            viewModel.deleteImageFile(item)
                        }
                    }
                })
                
                snackbar.show()
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val density = recyclerView.context.resources.displayMetrics.density
                val marginHorizontal = (12 * density).toInt()
                val marginVertical = (6 * density).toInt()
                val cornerRadius = 16 * density
                val cardPadding = (16 * density).toInt()

                background.cornerRadius = cornerRadius

                val cardHeight = itemView.height - (2 * marginVertical)
                val iconTop = itemView.top + marginVertical + (cardHeight - deleteIcon.intrinsicHeight) / 2
                val iconBottom = iconTop + deleteIcon.intrinsicHeight

                when {
                    dX > 0 -> { 
                        val backgroundRight = (itemView.left + marginHorizontal + dX.toInt())
                            .coerceAtMost(itemView.right - marginHorizontal)
                        
                        background.setBounds(
                            itemView.left + marginHorizontal,
                            itemView.top + marginVertical,
                            backgroundRight,
                            itemView.bottom - marginVertical
                        )
                        
                        val iconLeft = itemView.left + marginHorizontal + cardPadding
                        val iconRight = iconLeft + deleteIcon.intrinsicWidth
                        deleteIcon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    }
                    dX < 0 -> { 
                        val backgroundLeft = (itemView.right - marginHorizontal + dX.toInt())
                            .coerceAtLeast(itemView.left + marginHorizontal)
                            
                        background.setBounds(
                            backgroundLeft,
                            itemView.top + marginVertical,
                            itemView.right - marginHorizontal,
                            itemView.bottom - marginVertical
                        )
                        
                        val iconRight = itemView.right - marginHorizontal - cardPadding
                        val iconLeft = iconRight - deleteIcon.intrinsicWidth
                        deleteIcon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    }
                    else -> {
                        background.setBounds(0, 0, 0, 0)
                    }
                }

                background.draw(c)
                if (Math.abs(dX) > (cardPadding + deleteIcon.intrinsicWidth)) {
                    deleteIcon.draw(c)
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        androidx.recyclerview.widget.ItemTouchHelper(swipeCallback)
            .attachToRecyclerView(binding.recyclerView)
    }

    // =========================================================================
    // 3. LiveData & State Observers
    // =========================================================================

    private fun observeViewModel() {
        viewModel.filteredClips.observe(this) { clips ->
            clips?.let { list ->
                val isNewItemAdded = list.size > previousClipCount
                previousClipCount = list.size
    
                // Ensure this ID matches the exact id given to your empty state layout in activity_main.xml
                binding.emptyStateView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    
                clipboardAdapter.submitList(list) {
                    if (isNewItemAdded) binding.recyclerView.scrollToPosition(0)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.selectedIds.collect { ids ->
                clipboardAdapter.setSelectedIds(ids)
        
                val count = ids.size
                val selectedItems = (viewModel.filteredClips.value ?: emptyList()).filter { ids.contains(it.id) }
                val allPinned = selectedItems.isNotEmpty() && selectedItems.all { it.isPinned }
        
                binding.btnPinSelected.text    = if (allPinned) "Unpin $count items" else "Pin $count items"
                binding.btnDeleteSelected.text = "Delete $count items"
        
                val totalVisible = viewModel.filteredClips.value?.size ?: 0
                binding.btnSelectAll.text = if (ids.size == totalVisible && totalVisible > 0) "Deselect All" else "Select All"
            }
        }
    
        lifecycleScope.launch {
            viewModel.expandedIds.collect { ids -> clipboardAdapter.setExpandedIds(ids) }
        }
    
        lifecycleScope.launch {
            viewModel.isSelectionMode.collect { isInSelectionMode ->
                binding.btnDeleteSelected.visibility = if (isInSelectionMode) View.VISIBLE else View.GONE
                binding.btnPinSelected.visibility    = if (isInSelectionMode) View.VISIBLE else View.GONE
                binding.selectAllLayout.visibility   = if (isInSelectionMode) View.VISIBLE else View.GONE
            }
        }
        
        lifecycleScope.launch {
            viewModel.revealedSensitiveIds.collect { ids -> clipboardAdapter.setRevealedIds(ids) }
        }
    }

    // =========================================================================
    // 4. Background Service & Process Management
    // =========================================================================

    private fun requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startClipboardServiceIfShizukuReady()
        }
    }

    private fun startClipboardServiceIfShizukuReady() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val monitoringEnabled = getSharedPreferences("clipboard_settings", MODE_PRIVATE)
                    .getBoolean("monitoring_enabled", true)
    
                if (monitoringEnabled) {
                    startClipboardService()
                }
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun startClipboardService() {
        val intent = Intent(this, ClipboardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkOverlayPermissionGuard() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val isSettingEnabled = prefs.getBoolean(SettingsActivity.KEY_FLOATING_OVERLAY_ENABLED, true)
    
        if (isSettingEnabled) {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(this)
            } else {
                true
            }
    
            if (!hasPermission) {
                Snackbar.make(
                    binding.root,
                    "Overlay feature enabled, but requires 'Display over other apps' permission!",
                    Snackbar.LENGTH_LONG
                ).setAction("Fix") {
                    startActivity(Intent(this, PermissionActivity::class.java))
                }.show()
            }
        }
    }
    
    // =========================================================================
    // 5. Dialogs, Sheets, & Overlay Windows
    // =========================================================================

    private fun showFilterBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.filter_bottom_sheet, null)
        dialog.setContentView(sheet)

        val searchGroup = sheet.findViewById<com.google.android.material.chip.ChipGroup>(R.id.sheetSearchFieldChipGroup)
        val filterGroup = sheet.findViewById<com.google.android.material.chip.ChipGroup>(R.id.sheetFilterChipGroup)
        val sortGroup   = sheet.findViewById<com.google.android.material.chip.ChipGroup>(R.id.sheetSortChipGroup)
        val dropdown    = sheet.findViewById<AutoCompleteTextView>(R.id.dropdownAdvancedSearch)

        val advancedOptions = listOf(
            "Package", "Window", "Activity", 
            "Previous App", "Previous Package", "Previous Window", "Previous Activity"
        )
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, advancedOptions)
        dropdown.setAdapter(adapter)

        dropdown.setOnItemClickListener { _, _, position, _ ->
            searchGroup.clearCheck() 
            viewModel.setSearchField(when (advancedOptions[position]) {
                "Package"           -> ClipboardViewModel.SearchField.PACKAGE
                "Window"            -> ClipboardViewModel.SearchField.WINDOW
                "Activity"          -> ClipboardViewModel.SearchField.ACTIVITY
                "Previous App"      -> ClipboardViewModel.SearchField.PREVIOUS_APP_NAME
                "Previous Package"  -> ClipboardViewModel.SearchField.PREVIOUS_PACKAGE
                "Previous Window"   -> ClipboardViewModel.SearchField.PREVIOUS_WINDOW
                "Previous Activity" -> ClipboardViewModel.SearchField.PREVIOUS_ACTIVITY
                else                -> ClipboardViewModel.SearchField.ALL
            })
            updateFilterIndicator()
        }

        searchGroup.setOnCheckedStateChangeListener { _, ids ->
            if (ids.isNotEmpty()) {
                dropdown.setText("Select advanced field...", false) 
                viewModel.setSearchField(when (ids.firstOrNull()) {
                    R.id.sheetChipSearchContent -> ClipboardViewModel.SearchField.CONTENT
                    R.id.sheetChipSearchApp     -> ClipboardViewModel.SearchField.APP_NAME
                    R.id.sheetChipSearchLabel   -> ClipboardViewModel.SearchField.LABEL
                    else                        -> ClipboardViewModel.SearchField.ALL
                })
                updateFilterIndicator()
            }
        }

        val currentSearchField = viewModel.searchField.value
        when (currentSearchField) {
            ClipboardViewModel.SearchField.CONTENT  -> sheet.findViewById<com.google.android.material.chip.Chip>(R.id.sheetChipSearchContent).isChecked = true
            ClipboardViewModel.SearchField.APP_NAME -> sheet.findViewById<com.google.android.material.chip.Chip>(R.id.sheetChipSearchApp).isChecked = true
            ClipboardViewModel.SearchField.LABEL    -> sheet.findViewById<com.google.android.material.chip.Chip>(R.id.sheetChipSearchLabel).isChecked = true
            ClipboardViewModel.SearchField.ALL      -> sheet.findViewById<com.google.android.material.chip.Chip>(R.id.sheetChipSearchAll).isChecked = true
            else -> {
                searchGroup.clearCheck()
                dropdown.setText(when (currentSearchField) {
                    ClipboardViewModel.SearchField.PACKAGE           -> "Package"
                    ClipboardViewModel.SearchField.WINDOW            -> "Window"
                    ClipboardViewModel.SearchField.ACTIVITY          -> "Activity"
                    ClipboardViewModel.SearchField.PREVIOUS_APP_NAME -> "Previous App"
                    ClipboardViewModel.SearchField.PREVIOUS_PACKAGE  -> "Previous Package"
                    ClipboardViewModel.SearchField.PREVIOUS_WINDOW   -> "Previous Window"
                    ClipboardViewModel.SearchField.PREVIOUS_ACTIVITY -> "Previous Activity"
                    else                                             -> "Select advanced field..."
                }, false)
            }
        }

        sheet.findViewById<com.google.android.material.chip.Chip>(
            when (viewModel.activeFilter.value) {
                ClipboardViewModel.Filter.SENSITIVE -> R.id.sheetChipFilterSensitive
                ClipboardViewModel.Filter.PINNED    -> R.id.sheetChipFilterPinned
                ClipboardViewModel.Filter.FAVORITES -> R.id.sheetChipFilterFavorites
                ClipboardViewModel.Filter.IMAGES    -> R.id.sheetChipFilterImages
                ClipboardViewModel.Filter.LABELED   -> R.id.sheetChipFilterLabeled
                else                                -> R.id.sheetChipFilterAll
            }
        ).isChecked = true

        sheet.findViewById<com.google.android.material.chip.Chip>(
            when (viewModel.sortOrder.value) {
                ClipboardViewModel.SortOrder.OLDEST    -> R.id.sheetChipSortOldest
                ClipboardViewModel.SortOrder.LAST_USED -> R.id.sheetChipSortLastUsed
                ClipboardViewModel.SortOrder.ALPHA     -> R.id.sheetChipSortAlpha
                ClipboardViewModel.SortOrder.ALPHA_APP -> R.id.sheetChipSortAlphaApp
                ClipboardViewModel.SortOrder.TYPE      -> R.id.sheetChipSortType
                else                                   -> R.id.sheetChipSortNewest
            }
        ).isChecked = true

        filterGroup.setOnCheckedStateChangeListener { _, ids ->
            viewModel.setFilter(when (ids.firstOrNull()) {
                R.id.sheetChipFilterSensitive -> ClipboardViewModel.Filter.SENSITIVE
                R.id.sheetChipFilterPinned    -> ClipboardViewModel.Filter.PINNED
                R.id.sheetChipFilterFavorites -> ClipboardViewModel.Filter.FAVORITES
                R.id.sheetChipFilterImages    -> ClipboardViewModel.Filter.IMAGES
                R.id.sheetChipFilterLabeled   -> ClipboardViewModel.Filter.LABELED
                else                          -> ClipboardViewModel.Filter.ALL
            })
            updateFilterIndicator()
        }

        sortGroup.setOnCheckedStateChangeListener { _, ids ->
            viewModel.setSortOrder(when (ids.firstOrNull()) {
                R.id.sheetChipSortOldest   -> ClipboardViewModel.SortOrder.OLDEST
                R.id.sheetChipSortLastUsed -> ClipboardViewModel.SortOrder.LAST_USED
                R.id.sheetChipSortAlpha    -> ClipboardViewModel.SortOrder.ALPHA
                R.id.sheetChipSortAlphaApp -> ClipboardViewModel.SortOrder.ALPHA_APP
                R.id.sheetChipSortType     -> ClipboardViewModel.SortOrder.TYPE
                else                       -> ClipboardViewModel.SortOrder.NEWEST
            })
            updateFilterIndicator()
        }

        sheet.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResetFilters)
            .setOnClickListener {
                viewModel.setFilter(ClipboardViewModel.Filter.ALL)
                viewModel.setSearchField(ClipboardViewModel.SearchField.ALL)
                viewModel.setSortOrder(ClipboardViewModel.SortOrder.NEWEST)
                sheet.findViewById<com.google.android.material.chip.Chip>(R.id.sheetChipSearchAll).isChecked = true
                sheet.findViewById<com.google.android.material.chip.Chip>(R.id.sheetChipFilterAll).isChecked = true
                sheet.findViewById<com.google.android.material.chip.Chip>(R.id.sheetChipSortNewest).isChecked = true
                dropdown.setText("Select advanced field...", false)
                updateFilterIndicator()
            }

        sheet.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnApplyFilters)
            .setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showLabelDialog(item: ClipboardItem) {
        val input = android.widget.EditText(this).apply {
            setText(item.label)
            hint = "Enter a label..."
            setPadding(48, 24, 48, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Edit Label")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val label = input.text.toString().trim().ifBlank { null }
                viewModel.updateLabel(item, label)
            }
            .setNegativeButton("Clear") { _, _ -> viewModel.updateLabel(item, null) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showClearUnpinnedDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear Unpinned Clips")
            .setMessage("Delete all unpinned clips? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ -> viewModel.deleteAllUnpinned() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteSelectedDialog() {
        val count = viewModel.selectedIds.value.size
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete $count clip${if (count == 1) "" else "s"}?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteSelectedClips() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndShowShizukuStatus() {
        val prefs = getSharedPreferences("clipboard_settings", MODE_PRIVATE)
        val monitoringEnabled = prefs.getBoolean("monitoring_enabled", true)
        
        if (!monitoringEnabled) {
            shizukuSnackbar?.dismiss()
            shizukuSnackbar = null
            return
        }

        val isConnected = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    
        if (!isConnected) {
            if (shizukuSnackbar == null) {
                shizukuSnackbar = Snackbar.make(
                    binding.root,
                    "Shizuku is not connected — clipboard monitoring is inactive",
                    Snackbar.LENGTH_INDEFINITE
                ).setAction("Fix") {
                    startActivity(Intent(this, PermissionActivity::class.java))
                }
                shizukuSnackbar?.show()
            }
        } else {
            shizukuSnackbar?.dismiss()
            shizukuSnackbar = null
        }
    }

    private fun checkForCrashLog() {
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        if (!crashFile.exists()) return
    
        val report = try { crashFile.readText() } catch (_: Exception) { return }
        crashFile.delete()
    
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Previous Crash Log")
            .setMessage(report)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                Toast.makeText(this, "Crash log copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    // =========================================================================
    // 6. Presentation Helpers & Data Transporters
    // =========================================================================

    private fun handleCopyAction(item: ClipboardItem) {
        viewModel.copyToClipboard(this, item)
        val message = if (item.mimeType?.startsWith("image/") == true) {
            "Image copied to clipboard!"
        } else {
            "Copied to clipboard!"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateFilterIndicator() {
        val filterLabel = when (viewModel.activeFilter.value) {
            ClipboardViewModel.Filter.SENSITIVE -> "Sensitive"
            ClipboardViewModel.Filter.PINNED    -> "Pinned"
            ClipboardViewModel.Filter.FAVORITES -> "Favorites"
            ClipboardViewModel.Filter.IMAGES    -> "Images"
            ClipboardViewModel.Filter.LABELED   -> "Labeled"
            ClipboardViewModel.Filter.ALL       -> "All clips"
        }
        val sortLabel = when (viewModel.sortOrder.value) {
            ClipboardViewModel.SortOrder.OLDEST    -> "Oldest"
            ClipboardViewModel.SortOrder.LAST_USED -> "Last Used"
            ClipboardViewModel.SortOrder.ALPHA     -> "A→Z Content"
            ClipboardViewModel.SortOrder.ALPHA_APP -> "A→Z App"
            ClipboardViewModel.SortOrder.TYPE      -> "Type"
            ClipboardViewModel.SortOrder.NEWEST    -> "Newest"
        }
        val fieldSuffix = when (viewModel.searchField.value) {
            ClipboardViewModel.SearchField.CONTENT           -> " · Content"
            ClipboardViewModel.SearchField.APP_NAME          -> " · App"
            ClipboardViewModel.SearchField.LABEL             -> " · Label"
            ClipboardViewModel.SearchField.PACKAGE           -> " · Package"
            ClipboardViewModel.SearchField.WINDOW            -> " · Window"
            ClipboardViewModel.SearchField.ACTIVITY          -> " · Activity"
            ClipboardViewModel.SearchField.PREVIOUS_APP_NAME -> " · Previous App"
            ClipboardViewModel.SearchField.PREVIOUS_PACKAGE  -> " · Previous Package"
            ClipboardViewModel.SearchField.PREVIOUS_WINDOW   -> " · Previous Window"
            ClipboardViewModel.SearchField.PREVIOUS_ACTIVITY -> " · Previous Activity"
            else                                             -> ""
        }
        binding.tvActiveFilters.text = "$filterLabel · $sortLabel$fieldSuffix"
    
        val isFiltered = viewModel.activeFilter.value != ClipboardViewModel.Filter.ALL
            || viewModel.sortOrder.value != ClipboardViewModel.SortOrder.NEWEST
            || viewModel.searchField.value != ClipboardViewModel.SearchField.ALL
        binding.btnFilter.alpha = if (isFiltered) 1.0f else 0.75f
    }

    private fun applyManualEntryVisibility() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val show = prefs.getBoolean(SettingsActivity.KEY_SHOW_MANUAL_ENTRY, false)
        binding.manualEntryLayout.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun buildDisplayConfig(): ClipDisplayConfig {
        val p = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        return ClipDisplayConfig(
            showAppName          = p.getBoolean(SettingsActivity.KEY_SHOW_FIELD_APP_NAME,      true),
            showPackageName      = p.getBoolean(SettingsActivity.KEY_SHOW_FIELD_PACKAGE,       true),
            showWindowLabel      = p.getBoolean(SettingsActivity.KEY_SHOW_FIELD_WINDOW,        true),
            showActivityName     = p.getBoolean(SettingsActivity.KEY_SHOW_FIELD_ACTIVITY,      true),
            showLabel            = p.getBoolean(SettingsActivity.KEY_SHOW_FIELD_LABEL,         true),
            showTechnicalDetails = p.getBoolean(SettingsActivity.KEY_SHOW_FIELD_TECH,          true),
            showPrevAppName      = p.getBoolean(SettingsActivity.KEY_SHOW_FIELD_PREV_APP_NAME, true),
            showPrevPackageName  = p.getBoolean(SettingsActivity.KEY_SHOW_FIELD_PREV_PACKAGE,  true),
            showPrevWindowLabel  = p.getBoolean(SettingsActivity.KEY_SHOW_FIELD_PREV_WINDOW,   true),
            showPrevActivityName = p.getBoolean(SettingsActivity.KEY_SHOW_FIELD_PREV_ACTIVITY, true)
        )
    }


    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        startClipboardServiceIfShizukuReady()
    }
}
