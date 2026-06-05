package nocom.rian.clipboardmanager.ui

import android.app.Application
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nocom.rian.clipboardmanager.db.AppDatabase
import nocom.rian.clipboardmanager.db.ClipboardItem
import nocom.rian.clipboardmanager.db.ClipboardRepository

class ClipboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ClipboardRepository
    val allClips: LiveData<List<ClipboardItem>>
    val filteredClips: LiveData<List<ClipboardItem>>
    
    private val _localOverrides = MutableStateFlow<Map<Int, ClipboardItem>>(emptyMap())

    private val _expandedIds = MutableStateFlow<Set<Int>>(emptySet())
    val expandedIds: StateFlow<Set<Int>> = _expandedIds

    private val _selectedIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedIds: StateFlow<Set<Int>> = _selectedIds

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode

    private val _revealedSensitiveIds = MutableStateFlow<Set<Int>>(emptySet())
    val revealedSensitiveIds: StateFlow<Set<Int>> = _revealedSensitiveIds

    private val _searchQuery = MutableStateFlow("")

    enum class Filter { ALL, PINNED, FAVORITES, IMAGES, SENSITIVE, LABELED }
    private val _activeFilter = MutableStateFlow(Filter.ALL)
    val activeFilter: StateFlow<Filter> = _activeFilter

    enum class SearchField { ALL, CONTENT, APP_NAME, LABEL, PACKAGE, WINDOW, ACTIVITY, PREVIOUS_APP_NAME, PREVIOUS_PACKAGE, PREVIOUS_WINDOW, PREVIOUS_ACTIVITY}
    private val _searchField = MutableStateFlow(SearchField.ALL)
    val searchField: StateFlow<SearchField> = _searchField

    enum class SortOrder { NEWEST, OLDEST, LAST_USED, ALPHA, ALPHA_APP, TYPE }
    private val _sortOrder = MutableStateFlow(SortOrder.NEWEST)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    private var cachedClips: List<ClipboardItem> = emptyList()

    // Tracks sequential execution per item ID to safely support sub-0.5s rapid clicks
    private val dbWriteJobs = mutableMapOf<Int, Job>()

    // ── 1. INITIALIZATION & REACTIVE STREAMS ───────────────────────────────────

    init {
        val dao = AppDatabase.getDatabase(application).clipboardDao()
        repository = ClipboardRepository(dao)
        allClips = repository.allClips.asLiveData()
    
        viewModelScope.launch {
            repository.allClips.collect { dbClips ->
                val overrides = _localOverrides.value
                if (overrides.isEmpty()) return@collect
                val confirmedIds = overrides.keys.filter { id ->
                    val db = dbClips.find { it.id == id } ?: return@filter false
                    val ov = overrides[id]              ?: return@filter false
                    db.isPinned    == ov.isPinned   &&
                    db.isFavorite  == ov.isFavorite &&
                    db.isSensitive == ov.isSensitive
                }.toSet()
                if (confirmedIds.isNotEmpty()) {
                    _localOverrides.value = _localOverrides.value - confirmedIds
                }
            }
        }
    
        val effectiveClips = combine(repository.allClips, _localOverrides) { clips, overrides ->
            if (overrides.isEmpty()) clips else clips.map { overrides[it.id] ?: it }
        }
    
        filteredClips = combine(
            effectiveClips,
            _searchQuery,
            _activeFilter,
            _searchField,
            _sortOrder
        ) { clips, query, filter, searchField, sortOrder ->
    
            val base = when (filter) {
                Filter.SENSITIVE -> clips.filter { it.isSensitive }
                Filter.PINNED    -> clips.filter { it.isPinned }
                Filter.FAVORITES -> clips.filter { it.isFavorite }
                Filter.IMAGES    -> clips.filter { it.mimeType?.startsWith("image/") == true }
                Filter.LABELED   -> clips.filter { !it.label.isNullOrBlank() }
                Filter.ALL       -> clips
            }
    
            val searched = if (query.isBlank()) base else base.filter { item ->
                when (searchField) {
                    SearchField.ALL      ->
                        item.clip?.contains(query, ignoreCase = true) == true ||
                        item.appName?.contains(query, ignoreCase = true) == true ||
                        item.label?.contains(query, ignoreCase = true) == true ||
                        item.packageName?.contains(query, ignoreCase = true) == true ||
                        item.windowLabel?.contains(query, ignoreCase = true) == true ||
                        item.activityName?.contains(query, ignoreCase = true) == true ||
                        item.previousAppName?.contains(query, ignoreCase = true) == true ||
                        item.previousPackageName?.contains(query, ignoreCase = true) == true ||
                        item.previousWindowLabel?.contains(query, ignoreCase = true) == true ||
                        item.previousActivityName?.contains(query, ignoreCase = true) == true
                    SearchField.CONTENT  -> item.clip?.contains(query, ignoreCase = true) == true
                    SearchField.APP_NAME -> item.appName?.contains(query, ignoreCase = true) == true
                    SearchField.LABEL    -> item.label?.contains(query, ignoreCase = true) == true
                    SearchField.PACKAGE  -> item.packageName?.contains(query, ignoreCase = true) == true
                    SearchField.WINDOW   -> item.windowLabel?.contains(query, ignoreCase = true) == true
                    SearchField.ACTIVITY -> item.activityName?.contains(query, ignoreCase = true) == true
                    SearchField.PREVIOUS_APP_NAME -> item.previousAppName?.contains(query, ignoreCase = true) == true
                    SearchField.PREVIOUS_PACKAGE  -> item.previousPackageName?.contains(query, ignoreCase = true) == true
                    SearchField.PREVIOUS_WINDOW   -> item.previousWindowLabel?.contains(query, ignoreCase = true) == true
                    SearchField.PREVIOUS_ACTIVITY -> item.previousActivityName?.contains(query, ignoreCase = true) == true
                }
            }
    
            when (sortOrder) {
                SortOrder.NEWEST    ->
                    searched.sortedWith(compareByDescending<ClipboardItem> { it.isPinned }
                        .thenByDescending { it.timestamp })
                SortOrder.OLDEST    ->
                    searched.sortedWith(compareByDescending<ClipboardItem> { it.isPinned }
                        .thenBy { it.timestamp })
                SortOrder.LAST_USED ->
                    searched.sortedWith(compareByDescending<ClipboardItem> { it.isPinned }
                        .thenByDescending { it.timestamp })
                SortOrder.ALPHA     ->
                    searched.sortedWith(compareByDescending<ClipboardItem> { it.isPinned }
                        .thenBy { (it.clip ?: it.appName ?: "").lowercase() })
                SortOrder.ALPHA_APP ->
                    searched.sortedWith(compareByDescending<ClipboardItem> { it.isPinned }
                        .thenBy { (it.appName ?: "").lowercase() })
                SortOrder.TYPE      ->
                    searched.sortedWith(compareByDescending<ClipboardItem> { it.isPinned }
                        .thenBy { it.mimeType ?: "text/plain" }
                        .thenByDescending { it.timestamp })
            }
        }.asLiveData()
    }

    // ── Helper for thread-confined sequential atomic DB updates ────────────────
    private fun safelyExecuteDbWrite(itemId: Int, block: suspend () -> Unit) {
        dbWriteJobs[itemId]?.cancel()
        dbWriteJobs[itemId] = viewModelScope.launch(Dispatchers.IO) {
            block()
        }
    }

    // ── 2. SEARCH & FILTER SETTERS ─────────────────────────────────────────────

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setFilter(filter: Filter) { _activeFilter.value = filter }
    fun setSearchField(field: SearchField) { _searchField.value = field }
    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }

    // ── 3. SELECTION & EXPANSION MANAGEMENT ────────────────────────────────────

    fun toggleExpand(itemId: Int) {
        val current = _expandedIds.value
        _expandedIds.value = if (current.contains(itemId)) current - itemId else current + itemId
    }

    fun toggleSelection(item: ClipboardItem) {
        val current = _selectedIds.value
        _selectedIds.value = if (current.contains(item.id)) current - item.id else current + item.id
        _isSelectionMode.value = _selectedIds.value.isNotEmpty()
    }

    fun selectAll(clips: List<ClipboardItem>) {
        _selectedIds.value = clips.map { it.id }.toSet()
        _isSelectionMode.value = _selectedIds.value.isNotEmpty()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        _isSelectionMode.value = false
    }

    // ── 4. ITEM STATE TOGGLES (OPTIMISTIC) ─────────────────────────────────────
    fun toggleFavorite(item: ClipboardItem) {
        val current = _localOverrides.value[item.id] ?: item
        val updated = current.copy(isFavorite = !current.isFavorite)
        _localOverrides.value = _localOverrides.value + (item.id to updated)

        safelyExecuteDbWrite(item.id) {
            repository.update(updated)   // was: repository.toggleFavorite(updated)
        }
    }

    fun toggleSensitive(item: ClipboardItem) {
        val current = _localOverrides.value[item.id] ?: item
        val updated = current.copy(isSensitive = !current.isSensitive)
        _localOverrides.value = _localOverrides.value + (item.id to updated)

        safelyExecuteDbWrite(item.id) {
            repository.update(updated)   // was: repository.toggleSensitive(updated)
        }
    }
    fun togglePin(item: ClipboardItem) {
        val current = _localOverrides.value[item.id] ?: item
        val updated = current.copy(isPinned = !current.isPinned)
        _localOverrides.value = _localOverrides.value + (item.id to updated)
        
        safelyExecuteDbWrite(item.id) {
            repository.update(updated)
        }
    }

    fun toggleRevealSensitive(itemId: Int) {
        val current = _revealedSensitiveIds.value
        val willBeHidden = current.contains(itemId) // If true, it's about to be removed from revealed -> hidden
        
        _revealedSensitiveIds.value = if (willBeHidden) current - itemId else current + itemId
        
        // Clean up expansion states safely on state update thread if hiding the clip
        if (willBeHidden) {
            _expandedIds.value = _expandedIds.value - itemId
        }
    }

    fun togglePinSelectedClips() = viewModelScope.launch {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return@launch
        val selectedItems = (filteredClips.value ?: emptyList()).filter { ids.contains(it.id) }
        if (selectedItems.isEmpty()) return@launch
        val allPinned = selectedItems.all { it.isPinned }

        withContext(Dispatchers.IO) {
            selectedItems.forEach { repository.update(it.copy(isPinned = !allPinned)) }
        }
        _selectedIds.value = emptySet()
        _isSelectionMode.value = false
    }

    // ── 5. DATABASE MUTATIONS (INSERT / UPDATE) ───────────────────────────────

    fun insert(clip: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.insert(
            ClipboardItem(
                clip        = clip,
                appName     = "Manual Entry",
                packageName = "nocom.rian.clipboardmanager",
                timestamp   = System.currentTimeMillis()
            )
        )
    }

    fun updateLabel(item: ClipboardItem, label: String?) = viewModelScope.launch(Dispatchers.IO) {
        repository.updateLabel(item.id, label)
    }
    
    fun restoreClip(item: ClipboardItem) = viewModelScope.launch(Dispatchers.IO) {
    repository.insert(item.copy(id = 0))
}
    
    fun updateClipById(
        itemId: Int,
        newClip: String?,
        newLabel: String?,
        newAppName: String?,
        newPackageName: String?,
        newWindowLabel: String?,
        newActivityName: String?,
        newPreviousAppName: String?,
        newPreviousPackageName: String?,
        newPreviousWindowLabel: String?,
        newPreviousActivityName: String?
    ) = viewModelScope.launch(Dispatchers.IO) {
        val item = repository.getAllClips().find { it.id == itemId } ?: return@launch
        repository.update(
            item.copy(
                clip         = if (item.mimeType?.startsWith("image/") == true) item.clip else newClip,
                label        = newLabel,
                appName      = newAppName,
                packageName  = newPackageName,
                windowLabel  = newWindowLabel,
                activityName = newActivityName,
                previousAppName      = newPreviousAppName,
                previousPackageName  = newPreviousPackageName,
                previousWindowLabel  = newPreviousWindowLabel,
                previousActivityName = newPreviousActivityName
            )
        )
    }
    
    // ── 6. DELETION ACTIONS & HELPERS ──────────────────────────────────────────

    private fun deleteImageFileIfNeeded(item: ClipboardItem) {
        if (item.mimeType?.startsWith("image/") == true) {
            val file = java.io.File(item.filePath ?: return)
            if (file.exists()) {
                android.util.Log.d("ClipboardViewModel", "Image deleted: ${item.filePath}")
                file.delete()
            }
        }
    }

    fun delete(item: ClipboardItem) = viewModelScope.launch(Dispatchers.IO) {
        deleteImageFileIfNeeded(item)
        repository.delete(item)
    }

    fun deleteAllUnpinned() = viewModelScope.launch(Dispatchers.IO) {
        repository.getUnpinnedItems().forEach { deleteImageFileIfNeeded(it) }
        repository.clearUnpinned()
    }

    fun deleteSelectedClips() = viewModelScope.launch {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return@launch
        val selectedItems = (filteredClips.value ?: emptyList()).filter { ids.contains(it.id) }

        withContext(Dispatchers.IO) {
            selectedItems.forEach { item ->
                deleteImageFileIfNeeded(item)
                repository.delete(item)
            }
        }
        _selectedIds.value = emptySet()
        _isSelectionMode.value = false
    }

    // ── 7. SYSTEM INTERACTION (CLIPBOARD COPY) ─────────────────────────────────

    fun copyToClipboard(context: android.content.Context, item: ClipboardItem) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager

        if (item.mimeType?.startsWith("image/") == true) {
            val file = java.io.File(item.filePath ?: return)
            if (!file.exists()) {
                android.util.Log.e("ClipboardViewModel", "Image missing: ${item.filePath}")
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            clipboard.setPrimaryClip(
                android.content.ClipData.newUri(context.contentResolver, "Copied Image", uri)
            )
        } else {
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("Copied Clip", item.clip)
            )
        }
    }
}
