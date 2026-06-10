package nocom.rian.clipboardmanager.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import nocom.rian.clipboardmanager.R
import nocom.rian.clipboardmanager.EditClipActivity
import nocom.rian.clipboardmanager.db.ClipboardItem
import androidx.appcompat.app.AlertDialog

class OverlayActivity : AppCompatActivity() {

    // Inject the ViewModel to handle all filtering, sorting, and state logic
    private val viewModel: ClipboardViewModel by viewModels()
    private lateinit var overlayAdapter: ClipboardAdapter
    
    // Tracks currently expanded items via long-click
    private val expandedItemIds = mutableSetOf<Int>()
    private val revealedItemIds = mutableSetOf<Int>()

    // Guard flag to safely process fast scrolling transitions across heavy lists
    private var isDraggingScrollbar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.overlay_clipboard_dialog)

        setupWindowLayout()
        setupRecyclerView()
        setupSearchAndDatabaseFlows()
    }

    private fun setupWindowLayout() {
        window.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            
            val params = attributes
            params.gravity = Gravity.CENTER
            
            val metrics = resources.displayMetrics
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = (metrics.heightPixels * 0.55).toInt()
            params.windowAnimations = android.R.style.Animation_Dialog
            attributes = params
        }
        setFinishOnTouchOutside(true)
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.overlayClipboardRecycler)
    
        val displayConfig = ClipDisplayConfig(
            showAppName = true, showPackageName = false, showWindowLabel = true,
            showActivityName = false, showLabel = true, showTechnicalDetails = false,
            showQuickCopy = false
        )
    
        overlayAdapter = ClipboardAdapter(
            onPinClick = { item -> viewModel.togglePin(item) },
            onExpandClick = { id -> toggleExpansion(id) },
            onLongClick = { item -> toggleExpansion(item.id) },
            onDeleteClick = { item -> viewModel.delete(item, deleteFile = true) },
            onCopyClick = { item ->
                viewModel.copyToClipboard(this, item)
                val isImage = item.mimeType?.startsWith("image/") == true
                Toast.makeText(
                    this, 
                    if (isImage) "Image copied to clipboard" else "Copied to clipboard", 
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            },
            onFavoriteClick = { item -> viewModel.toggleFavorite(item) },
            onSensitiveClick = { item -> viewModel.toggleSensitive(item) },
            onLabelEditClick = { item -> showLabelDialog(item) },
            onEditClick = { _ -> },
            onRevealClick = { id -> toggleReveal(id) },
            initialConfig = displayConfig
        )
    
        val layoutManager = LinearLayoutManager(this)
    
        overlayAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                if (!isDraggingScrollbar && toPosition == 0 && layoutManager.findFirstCompletelyVisibleItemPosition() == 0) {
                    recyclerView.post { layoutManager.scrollToPositionWithOffset(0, 0) }
                }
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (!isDraggingScrollbar && positionStart == 0 && layoutManager.findFirstCompletelyVisibleItemPosition() == 0) {
                    recyclerView.post { layoutManager.scrollToPositionWithOffset(0, 0) }
                }
            }
        })
    
        recyclerView.apply {
            this.layoutManager = layoutManager
            this.adapter = overlayAdapter
            setHasFixedSize(true)
        }
    
        setupCustomScrollbar()
    }
    
    private fun setupCustomScrollbar() {
        val recycler = findViewById<RecyclerView>(R.id.overlayClipboardRecycler)
        val track = findViewById<View>(R.id.scrollbarTrack)
        val thumb = findViewById<View>(R.id.scrollbarThumb)
        val dateBubbleCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.scrollbarDateBubbleCard)
        val dateBubble = findViewById<android.widget.TextView>(R.id.scrollbarDateBubble)
    
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
                    handleScrollbarTouch(event.y, maxTrackHeight, lm, totalItemCount, thumb, recycler, dateBubbleCard, dateBubble)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleScrollbarTouch(event.y, maxTrackHeight, lm, totalItemCount, thumb, recycler, dateBubbleCard, dateBubble)
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
        touchY: Float, maxTrackHeight: Int, lm: LinearLayoutManager, totalItemCount: Int,
        thumb: View, recycler: RecyclerView, dateBubbleCard: com.google.android.material.card.MaterialCardView,
        dateBubble: android.widget.TextView
    ) {
        var correctedY = touchY - (thumb.height / 2)
        correctedY = correctedY.coerceIn(0f, maxTrackHeight.toFloat())
        thumb.translationY = correctedY
    
        val scrollPercent = correctedY / maxTrackHeight.toFloat()
        val targetPosition = (scrollPercent * totalItemCount).toInt().coerceIn(0, totalItemCount - 1)
        lm.scrollToPositionWithOffset(targetPosition, 0)
    
        val item = overlayAdapter.currentList.getOrNull(targetPosition)
        if (item != null) {
            val itemCalendar = java.util.Calendar.getInstance().apply { timeInMillis = item.timestamp }
            val currentCalendar = java.util.Calendar.getInstance()
            val formatPattern = if (itemCalendar.get(java.util.Calendar.YEAR) == currentCalendar.get(java.util.Calendar.YEAR)) "dd MMM" else "dd MMM yyyy"
            dateBubble.text = java.text.SimpleDateFormat(formatPattern, java.util.Locale.getDefault()).format(java.util.Date(item.timestamp))
        }
    
        val bubbleHeight = dateBubbleCard.height
        val targetBubbleY = correctedY + (thumb.height / 2f) - (bubbleHeight / 2f)
        val maxAllowedY = (recycler.height - bubbleHeight).toFloat()
        dateBubbleCard.translationY = targetBubbleY.coerceIn(0f, maxAllowedY)
    }
    
    private fun toggleExpansion(itemId: Int) {
        if (expandedItemIds.contains(itemId)) expandedItemIds.remove(itemId) else expandedItemIds.add(itemId)
        overlayAdapter.setExpandedIds(expandedItemIds.toSet())
    }
    
    private fun toggleReveal(itemId: Int) {
        if (revealedItemIds.contains(itemId)) revealedItemIds.remove(itemId) else revealedItemIds.add(itemId)
        overlayAdapter.setRevealedIds(revealedItemIds.toSet())
    }

    private fun setupSearchAndDatabaseFlows() {
        val searchEditText = findViewById<EditText>(R.id.overlaySearchInput)
        val btnFilter = findViewById<View>(R.id.btnOverlayFilter)
    
        // Observe the ViewModel's fully processed filter/search/sort flow
        viewModel.filteredClips.observe(this) { clips ->
            overlayAdapter.submitList(clips)
        }
    
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s.toString().trim())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnFilter.setOnClickListener {
            showFilterBottomSheet()
        }
    }

    private fun showFilterBottomSheet() {
        val dialog = BottomSheetDialog(this)
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
            }

        sheet.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnApplyFilters)
            .setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
