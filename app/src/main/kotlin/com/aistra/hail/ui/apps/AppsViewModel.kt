package com.aistra.hail.ui.apps

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppRisk
import com.aistra.hail.app.AppRiskOrdering
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.*
import java.text.Collator
import kotlinx.coroutines.*

data class InstalledRow(val info: ApplicationInfo, val label: String, val frozen: Boolean, val installed: Boolean,
                        val checked: Boolean, val grayscale: Boolean, val sortTime: Long,
                        val risk: AppRisk = AppRisk())

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    val isRefreshing = MutableLiveData(false)
    val displayApps = MutableLiveData<List<InstalledRow>>()
    // Entries live for a single package refresh. Searching and sorting reuse the Android metadata.
    private class Entry(val record: HPackages.UserPackage, val info: ApplicationInfo, pm: PackageManager,
                        val risk: AppRisk) {
        val label by lazy { info.loadLabel(pm).toString() }
        val packageInfo by lazy { HPackages.getUnhiddenPackageInfoOrNull(info.packageName) }
    }

    private var applications: List<Entry> = emptyList()
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

    fun setChecked(pkg: String, checked: Boolean) {
        displayApps.value = displayApps.value?.map { row ->
            if (row.info.packageName == pkg) row.copy(checked = checked) else row
        }
    }

    fun updateAppList() {
        loadJob?.cancel()
        filterJob?.cancel()
        isRefreshing.value = true
        loadJob = viewModelScope.launch {
            val pm = getApplication<HailApp>().packageManager
            applications = withContext(Dispatchers.IO) {
                val risks = AppRisks.snapshot(getApplication<HailApp>())
                PackageLookup(HailData.workingMode).all(includeRetainedSystem = true).values.mapNotNull { record ->
                    record.info?.let { Entry(record, it, pm, risks.classify(it)) }
                }
            }
            updateDisplayAppList()
        }
    }

    fun updateDisplayAppList() {
        filterJob?.cancel()
        val source = applications
        val search = query
        val sort = HailData.sortBy
        val mode = HailData.workingMode
        val users = HailData.filterUserApps
        val system = HailData.filterSystemApps
        val frozenApps = HailData.filterFrozenApps
        val unfrozenApps = HailData.filterUnfrozenApps
        val gray = HailData.grayscaleIcon
        val nineKey = HailData.nineKeySearch
        filterJob = viewModelScope.launch {
            val filtered = withContext(Dispatchers.IO) {
                val rows = source.mapNotNull { entry ->
                    ensureActive()
                    val info = entry.info
                    val record = entry.record
                    val pkg = info.packageName
                    if (!(if (HPackages.isSystemApp(info)) system else users)) return@mapNotNull null
                    val frozen = AppManager.state(record, mode) == AppInfo.State.FROZEN
                    if (!(if (frozen) frozenApps else unfrozenApps)) return@mapNotNull null
                    val label = entry.label
                    if (search.isNotEmpty() && !(nineKey && NineKeySearch.search(search, pkg, label))
                        && !FuzzySearch.search(pkg, search) && !FuzzySearch.search(label, search)
                        && !PinyinSearch.searchPinyinAll(label, search)) return@mapNotNull null
                    val timestamp = when (sort) {
                        HailData.SORT_INSTALL -> entry.packageInfo?.firstInstallTime
                        HailData.SORT_UPDATE -> entry.packageInfo?.lastUpdateTime
                        else -> null
                    }
                    InstalledRow(info, label, frozen, record.installed,
                        false, gray, timestamp ?: 0, entry.risk)
                }
                when (sort) {
                    HailData.SORT_INSTALL -> rows.sortedBy { it.sortTime }
                    HailData.SORT_UPDATE -> rows.sortedByDescending { it.sortTime }
                    else -> {
                        val collator = Collator.getInstance()
                        val nameOrder = Comparator<InstalledRow> { first, second ->
                            val labelOrder = collator.compare(first.label, second.label)
                            if (labelOrder == 0) first.info.packageName.compareTo(second.info.packageName) else labelOrder
                        }
                        val ordering = if (sort == HailData.SORT_RISK_RECOMMENDED || sort == HailData.SORT_RISK_UNSAFE)
                            AppRiskOrdering.comparator(sort == HailData.SORT_RISK_UNSAFE, { it.risk.level }, nameOrder)
                        else nameOrder
                        rows.sortedWith(ordering)
                    }
                }
            }
            val checked = HailData.checkedList.mapTo(hashSetOf()) { it.packageName }
            displayApps.value = filtered.map { if (it.info.packageName in checked) it.copy(checked = true) else it }
            isRefreshing.value = false
        }
    }
}
