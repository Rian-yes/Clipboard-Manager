package nocom.rian.clipboardmanager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nocom.rian.clipboardmanager.db.AppDatabase
import nocom.rian.clipboardmanager.db.ClipboardItem
import nocom.rian.clipboardmanager.db.ClipboardRepository

class EditClipViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ClipboardRepository

    private val _item = MutableLiveData<ClipboardItem?>()
    val item: LiveData<ClipboardItem?> = _item

    // true = saved OK, false = item not found / error
    private val _saveComplete = MutableLiveData<Boolean>()
    val saveComplete: LiveData<Boolean> = _saveComplete

    init {
        val dao = AppDatabase.getDatabase(application).clipboardDao()
        repository = ClipboardRepository(dao)
    }

    fun loadItem(id: Int) = viewModelScope.launch(Dispatchers.IO) {
        _item.postValue(repository.findById(id))
    }

    fun saveItem(
        id: Int,
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
        val original = repository.findById(id) ?: run {
            _saveComplete.postValue(false)
            return@launch
        }
        val updated = original.copy(
            clip                 = if (original.mimeType?.startsWith("image/") == true) original.clip else newClip,
            label                = newLabel,
            appName              = newAppName,
            packageName          = newPackageName,
            windowLabel          = newWindowLabel,
            activityName         = newActivityName,
            previousAppName      = newPreviousAppName,
            previousPackageName  = newPreviousPackageName,
            previousWindowLabel  = newPreviousWindowLabel,
            previousActivityName = newPreviousActivityName
        )
        repository.update(updated)
        _saveComplete.postValue(true)
    }
}