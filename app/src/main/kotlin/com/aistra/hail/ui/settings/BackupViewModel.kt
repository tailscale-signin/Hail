package com.aistra.hail.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.BackupContent
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HLog
import com.aistra.hail.utils.HShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object Busy : State
        data class Preview(val data: BackupContent, val appsJson: String, val tagsJson: String) : State
        data object Saved : State
        data object Restored : State
        data object Failed : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    fun open(uri: Uri, restore: Boolean) {
        if (state.value != State.Idle) return
        state.value = State.Busy
        viewModelScope.launch {
            try {
                if (restore) {
                    state.value = withContext(Dispatchers.IO) {
                        val data = requireNotNull(app.contentResolver.openInputStream(uri)).use(BackupContent::readFrom)
                        State.Preview(data, BackupContent.encodeApps(data.apps).toString(), BackupContent.encodeTags(data.tags).toString())
                    }
                } else {
                    val data = HailData.backupSnapshot()
                    withContext(Dispatchers.IO) {
                        requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).use(data::writeTo)
                    }
                    state.value = State.Saved
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HLog.e(e)
                state.value = State.Failed
            }
        }
    }

    fun confirm() {
        val preview = state.value as? State.Preview ?: return
        val oldShortcut = HailData.dynamicShortcutAction
        HailData.restoreBackup(preview.data, preview.appsJson, preview.tagsJson)
        state.value = State.Restored
        AppIconCache.clear()
        app.setAutoFreezeService()
        if (oldShortcut != HailData.dynamicShortcutAction) viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                HShortcuts.removeAllDynamicShortcuts()
                HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction)
            }.onFailure { HLog.e(it) }
        }
        app.setAppTheme(HailData.appTheme)
    }
}
