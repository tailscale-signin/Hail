package com.aistra.hail.ui.apps

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.*
import java.text.Collator
import kotlinx.coroutines.*

data class InstalledRow(val info: ApplicationInfo, val label: String, val frozen: Boolean, val installed: Boolean,
                        val checked: Boolean, val grayscale: Boolean, val sortTime: Long)

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    val isRefreshing = MutableLiveData(false)
    val displayApps = MutableLiveData<List<InstalledRow>>()
    private var applications = emptyList<ApplicationInfo>()
    private var query = ""
    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var queryJob: Job? = null

    fun postQuery(text: String, delayTime: Long = 150L) {
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(delayTime)
            if (query != text) {
                query = text
                updateDisplayAppList()
            }
        }
    }

    fun updateAppList() {
        loadJob?.cancel()
        filterJob?.cancel()
        isRefreshing.value = true
        loadJob = viewModelScope.launch {
            applications = withContext(Dispatchers.IO) { HPackages.getInstalledApplications() }
            updateDisplayAppList()
        }
    }

    fun updateDisplayAppList() {
        filterJob?.cancel()
        val source = applications
        val search = query
        val pm = getApplication<HailApp>().packageManager
        val checked = HailData.checkedList.mapTo(hashSetOf()) { it.packageName }
        val sort = HailData.sortBy
        val mode = HailData.workingMode
        val users = HailData.filterUserApps
        val system = HailData.filterSystemApps
        val frozenApps = HailData.filterFrozenApps
        val unfrozenApps = HailData.filterUnfrozenApps
        val gray = HailData.grayscaleIcon
        val nineKey = HailData.nineKeySearch
        filterJob = viewModelScope.launch {
            displayApps.value = withContext(Dispatchers.IO) {
                val rows = source.mapNotNull { info ->
                    ensureActive()
                    val pkg = info.packageName
                    if (!(if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) system else users)) return@mapNotNull null
                    val frozen = AppManager.isAppFrozen(info, mode)
                    if (!(if (frozen) frozenApps else unfrozenApps)) return@mapNotNull null
                    val label = info.loadLabel(pm).toString()
                    if (search.isNotEmpty() && !(nineKey && NineKeySearch.search(search, pkg, label))
                        && !FuzzySearch.search(pkg, search) && !FuzzySearch.search(label, search)
                        && !PinyinSearch.searchPinyinAll(label, search)) return@mapNotNull null
                    val timestamp = when (sort) {
                        HailData.SORT_INSTALL -> HPackages.getUnhiddenPackageInfoOrNull(pkg)?.firstInstallTime
                        HailData.SORT_UPDATE -> HPackages.getUnhiddenPackageInfoOrNull(pkg)?.lastUpdateTime
                        else -> null
                    }
                    InstalledRow(info, label, frozen, info.flags and ApplicationInfo.FLAG_INSTALLED != 0,
                        pkg in checked, gray, timestamp ?: 0)
                }
                when (sort) {
                    HailData.SORT_INSTALL -> rows.sortedBy { it.sortTime }
                    HailData.SORT_UPDATE -> rows.sortedByDescending { it.sortTime }
                    else -> {
                        val collator = Collator.getInstance()
                        rows.sortedWith { a,b -> collator.compare(a.label,b.label) }
                    }
                }
            }
            isRefreshing.value = false
        }
    }
}
