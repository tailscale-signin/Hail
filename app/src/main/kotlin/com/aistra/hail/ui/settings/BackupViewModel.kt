package com.aistra.hail.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.BackupContent
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.DecisionLog
import com.aistra.hail.utils.HLog
import com.aistra.hail.utils.HShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object Busy : State
        data class Preview(val data: BackupContent, val appsJson: String, val tagsJson: String, val applyStates: Boolean = false) : State
        data object Saved : State
        data class Restored(val changed: Int? = null, val unchanged: Int = 0, val unavailable: Int = 0, val failed: Int = 0) : State
        data object Failed : State
    }

    private val current = MutableStateFlow<State>(State.Idle)
    val state = current.asStateFlow()

    fun dismiss() {
        if (current.value != State.Busy) current.value = State.Idle
    }

    fun open(uri: Uri, restore: Boolean) {
        if (current.value != State.Idle) return
        current.value = State.Busy
        viewModelScope.launch {
            try {
                if (restore) {
                    current.value = withContext(Dispatchers.IO) {
                        val data = requireNotNull(app.contentResolver.openInputStream(uri)).use(BackupContent::readFrom)
                        State.Preview(data, BackupContent.encodeApps(data.apps).toString(), BackupContent.encodeTags(data.tags).toString())
                    }
                } else {
                    val data = HailData.backupSnapshot()
                    withContext(Dispatchers.IO) {
                        val full = data.captureFrozenStates()
                        requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).use(full::writeTo)
                    }
                    current.value = State.Saved
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HLog.e(e)
                current.value = State.Failed
            }
        }
    }

    fun toggleStates(enabled: Boolean) {
        val preview = current.value as? State.Preview ?: return
        current.value = preview.copy(applyStates = enabled)
    }

    fun confirm() {
        val preview = current.value as? State.Preview ?: return
        val trace = DecisionLog.begin("settings_backup", "restore")
        trace?.event("restore_confirmed", null, "apps" to preview.data.apps.size, "savedStates" to preview.data.frozenStates.size,
            "applyStates" to preview.applyStates)
        val oldShortcut = HailData.dynamicShortcutAction
        HailData.restoreBackup(preview.data, preview.appsJson, preview.tagsJson)
        current.value = State.Busy
        AppIconCache.clear()
        app.setAutoFreezeService()
        if (oldShortcut != HailData.dynamicShortcutAction) viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                HShortcuts.removeAllDynamicShortcuts()
                HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction)
            }.onFailure { HLog.e(it) }
        }
        viewModelScope.launch {
            val saved = preview.data.frozenStates
            if (preview.applyStates && saved.isNotEmpty()) {
                val results = saved.entries.groupBy({ it.value }, { it.key }).map { (frozen, packages) ->
                    AppManager.changeFrozen(packages, frozen, includeLabel = false, trace = trace)
                }
                current.value = State.Restored(results.sumOf { it.changed.size }, results.sumOf { it.unchanged.size },
                    results.sumOf { it.missing.size }, results.sumOf { it.failed.size })
            } else {
                trace?.skipped(null, if (saved.isEmpty()) "Backup contains no saved states" else "Restore of saved states was not selected")
                current.value = State.Restored()
            }
            app.setAppTheme(HailData.appTheme)
        }
    }
}
