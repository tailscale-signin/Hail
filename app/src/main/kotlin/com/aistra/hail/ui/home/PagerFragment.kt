package com.aistra.hail.ui.home

import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.EditText
import androidx.appcompat.widget.SearchView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailApi.addTag
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.databinding.FragmentPagerBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.failureDetails
import com.aistra.hail.ui.showActionFeedback
import com.aistra.hail.ui.FreezeFeedback
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.aistra.hail.work.HWork
import com.google.android.material.color.MaterialColors
import com.aistra.hail.ui.PrivateDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class PagerFragment : MainFragment(), PagerAdapter.OnItemClickListener, PagerAdapter.OnItemLongClickListener,
    MenuProvider {
    private var query: String = String()
    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter
    private var listJob: Job? = null
    private var lastLoggedView: Pair<String, String>? = null
    private var operations = 0
    private var multiselect: Boolean
        set(value) {
            (parentFragment as HomeFragment).multiselect = value
        }
        get() = (parentFragment as HomeFragment).multiselect
    private val selectedList get() = (parentFragment as HomeFragment).selectedList
    private val tabs: TabLayout? get() = (parentFragment as? HomeFragment)?.binding?.tabs
    private val adapter: HomeAdapter? get() = (parentFragment as? HomeFragment)?.binding?.pager?.adapter as? HomeAdapter
    private val tag: Pair<String, Int>? get() = tabs?.let { HailData.tags.getOrNull(it.selectedTabPosition) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentPagerBinding.inflate(inflater, container, false)
        pagerAdapter = PagerAdapter().apply {
            onItemClickListener = this@PagerFragment
            onItemLongClickListener = this@PagerFragment
        }
        childFragmentManager.setFragmentResultListener(PackageStatusDialog.RESULT, viewLifecycleOwner) { _, result ->
            result.getString(PackageStatusDialog.REMOVE)?.let { removeCheckedApp(it) }
            updateCurrentList()
        }
        binding.recyclerView.run {
            layoutManager = GridLayoutManager(
                activity, resources.getInteger(
                    if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
                )
            )
            adapter = pagerAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> activity.fab.run {
                            postDelayed({ if (tag != null) show() }, 1000)
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> activity.fab.hide()
                    }
                }
            })
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }

        }

        binding.refresh.apply {
            setOnRefreshListener {
                updateCurrentList()
            }
            applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AppManager.changes.collect { if (operations == 0) updateCurrentList() }
            }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        updateCurrentList()
        app.setAutoFreezeService()
        updateBarTitle()
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        tabs?.let { tabLayout ->
            tabLayout.getTabAt(tabLayout.selectedTabPosition)?.view?.setOnLongClickListener {
                if (isResumed) showTagDialog()
                true
            }
        }
        activity.fab.setOnClickListener {
            setListFrozen(true, currentApps(), skipWhitelisted = true, source = "home_freeze_button")
        }
        activity.fab.setOnLongClickListener {
            setListFrozen(true, source = "home_freeze_button_long_press")
            true
        }
    }

    private fun updateCurrentList() {
        listJob?.cancel()
        val search = query
        val id = tag?.second
        val gray = HailData.grayscaleIcon
        val font = HailData.homeFontSize
        val mode = HailData.workingMode
        val nineKey = HailData.nineKeySearch
        val snapshot = HailData.checkedList.filter { search.isNotEmpty() || id in it.tagIdList }.map {
            HomeRow(it, null, "", AppInfo.State.NOT_FOUND, it.pinned, it.whitelisted, false, gray, font)
        }
        val trace = DecisionLog.begin("home", "refresh", mode)
        val loggingView = trace?.let { it.session to mode }
        val previous = if (trace != null && lastLoggedView == loggingView)
            pagerAdapter.currentList.associate { it.info.packageName to it.state } else emptyMap()
        listJob = viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val collator = Collator.getInstance()
                val lookup = PackageLookup(mode, trace)
                val risks = AppRisks.snapshot()
                val packages = if (snapshot.isEmpty()) emptyMap() else lookup.all()
                snapshot.mapNotNull { row ->
                    ensureActive()
                    val pkg = row.info.packageName
                    val listed = packages[pkg]
                    val record = if (listed?.installed == true) listed else lookup.fromInventory(pkg)
                    val application = record?.info
                    val label = application?.loadLabel(app.packageManager)?.toString() ?: pkg
                    if (search.isNotEmpty() && !(nineKey && NineKeySearch.search(search, pkg, label))
                        && !FuzzySearch.search(pkg, search) && !FuzzySearch.search(label, search)
                        && !PinyinSearch.searchPinyinAll(label, search)) return@mapNotNull null
                    val state = AppManager.state(record, mode)
                    if (trace != null && previous[pkg] != state) {
                        trace.event("home_row", pkg, "displayedState" to state.name, "listedInstalled" to listed?.installed,
                            "installedForUser" to record?.installed, "hasMetadata" to (application != null),
                            "system" to application?.let(HPackages::isSystemApp), "reason" to when (state) {
                                AppInfo.State.NOT_FOUND -> "No current-user installation evidence"
                                AppInfo.State.NOT_INSTALLED -> "Retained metadata; no installed app for this user"
                                AppInfo.State.FROZEN -> "Package matches the selected frozen-state check"
                                AppInfo.State.UNFROZEN -> "Package is available under the selected mode"
                            })
                    }
                    row.copy(application = application, label = label, state = state, risk = risks.classify(application))
                }.sortedWith(compareByDescending<HomeRow> { it.pinned }.thenComparator { a, b -> collator.compare(a.label, b.label) })
            }
            lastLoggedView = loggingView
            trace?.event("list_displayed", null, "managedInView" to snapshot.size, "visible" to rows.size,
                "unavailable" to rows.count { it.state == AppInfo.State.NOT_FOUND },
                "notInstalled" to rows.count { it.state == AppInfo.State.NOT_INSTALLED })
            binding.empty.isVisible = rows.isEmpty()
            showSelection(rows)
            binding.refresh.isRefreshing = operations > 0
        }
    }

    private fun showSelection(rows: List<HomeRow> = pagerAdapter.currentList) {
        val selected = selectedList.toHashSet()
        pagerAdapter.submitList(rows.map { row ->
            val isSelected = row.info in selected
            if (row.selected == isSelected) row else row.copy(selected = isSelected)
        })
    }

    private fun currentApps(): List<AppInfo> {
        if (query.isNotEmpty()) return pagerAdapter.apps
        val id = tag?.second ?: return emptyList()
        return HailData.checkedList.filter { id in it.tagIdList }
    }

    private fun updateBarTitle() {
        activity.supportActionBar?.title =
            if (multiselect) getString(R.string.msg_selected, selectedList.size.toString())
            else getString(R.string.app_name)
    }

    override fun onItemClick(info: AppInfo) {
        if (multiselect) {
            if (info in selectedList) selectedList.remove(info)
            else selectedList.add(info)
            showSelection()
            updateBarTitle()
            return
        }
        launchApp(info.packageName)
    }

    override fun onItemLongClick(info: AppInfo): Boolean {
        val row = pagerAdapter.currentList.firstOrNull { it.info == info } ?: return false
        if (row.state.unavailable && (!multiselect || info !in selectedList)) {
            DecisionLog.begin("home", "inspect_unavailable", pkg = info.packageName)?.event("status_opened", info.packageName,
                "reason" to "Home row has no current-user installation evidence")
            showPackageStatus(info.packageName)
            return true
        }
        if (info in selectedList) {
            onMultiSelect()
            return true
        }
        val pkg = info.packageName
        val frozen = row.state == AppInfo.State.FROZEN
        val action = getString(if (frozen) R.string.action_unfreeze else R.string.action_freeze)
        val showStatus = row.application?.let {
            HPackages.isSystemApp(it) || (frozen && HailData.workingMode.endsWith(HailData.DISABLE))
        } ?: true
        val entries = resources.getStringArray(R.array.home_action_entries).filter {
                (it != getString(R.string.action_freeze) || !frozen) && (it != getString(R.string.action_unfreeze) || frozen) && (it != getString(
                    R.string.action_pin
                ) || !info.pinned) && (it != getString(R.string.action_unpin) || info.pinned) && (it != getString(
                    R.string.action_whitelist
                ) || !info.whitelisted) && (it != getString(R.string.action_remove_whitelist) || info.whitelisted) && (it != getString(
                    R.string.action_unfreeze_remove_home
                ) || frozen)
            }
        val menu = if (showStatus) listOf(getString(R.string.package_status_controls)) + entries else entries
        PrivateDialogBuilder(activity).setTitle(row.label).setItems(menu.toTypedArray()) { _, index ->
            if (showStatus && index == 0) {
                showPackageStatus(pkg)
                return@setItems
            }
            when (if (showStatus) index - 1 else index) {
                0 -> launchApp(pkg)
                1 -> setListFrozen(!frozen, listOf(info))
                2 -> {
                    val values = resources.getIntArray(R.array.deferred_task_values)
                    val entries = arrayOfNulls<String>(values.size)
                    values.forEachIndexed { i, it ->
                        entries[i] = resources.getQuantityString(R.plurals.deferred_task_entry, it, it)
                    }
                    PrivateDialogBuilder(activity).setTitle(R.string.action_deferred_task)
                        .setItems(entries) { _, i ->
                            HWork.setDeferredFrozen(pkg, !frozen, values[i].toLong())
                            Snackbar.make(
                                activity.fab, resources.getQuantityString(
                                    R.plurals.msg_deferred_task, values[i], values[i], action, info.name
                                ), Snackbar.LENGTH_INDEFINITE
                            ).setAction(R.string.action_undo) { HWork.cancelWork(pkg) }.show()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }

                3 -> {
                    info.pinned = !info.pinned
                    HailData.saveApps()
                    updateCurrentList()
                }

                4 -> {
                    info.whitelisted = !info.whitelisted
                    HailData.saveApps()
                    updateCurrentList()
                }

                5 -> tagDialog(info)

                6 -> tabs?.takeIf { it.tabCount > 1 }?.let {
                    PrivateDialogBuilder(requireActivity()).setTitle(R.string.action_unfreeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            HShortcuts.addPinShortcut(
                                info,
                                pkg,
                                info.name,
                                HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg).addTag(HailData.tags[index].first)
                            )
                        }.setPositiveButton(R.string.action_skip) { _, _ ->
                            HShortcuts.addPinShortcut(
                                info, pkg, info.name, HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()
                } ?: HShortcuts.addPinShortcut(
                    info, pkg, info.name, HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg)
                )

                7 -> exportToClipboard(listOf(info))
                8 -> removeCheckedApp(pkg)
                9 -> setListFrozen(false, listOf(info), removeAfter = true)
            }
        }.setNeutralButton(R.string.action_details) { _, _ ->
            HUI.startActivity(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HPackages.packageUri(pkg)
            )
        }.setNegativeButton(android.R.string.cancel, null).show()
        return true
    }

    private fun showPackageStatus(pkg: String, message: String? = null) {
        PackageStatusDialog.show(childFragmentManager, pkg, message)
    }

    private fun tagDialog(info: AppInfo) {
        val checkedItems = BooleanArray(HailData.tags.size) { index ->
            HailData.tags[index].second in info.tagIdList
        }
        PrivateDialogBuilder(activity).setTitle(R.string.action_tag_set).setMultiChoiceItems(
            HailData.tags.map { it.first }.toTypedArray(), checkedItems
        ) { _, index, isChecked ->
            checkedItems[index] = isChecked
        }.setPositiveButton(android.R.string.ok) { _, _ ->
            info.tagIdList.clear()
            checkedItems.forEachIndexed { index, checked ->
                if (checked) info.tagIdList.add(HailData.tags[index].second)
            }
            if (info.tagIdList.isEmpty()) {
                removeCheckedApp(info.packageName, false)
            }
            HailData.saveApps()
            updateCurrentList()
        }.setNeutralButton(R.string.action_tag_add) { _, _ ->
            showTagDialog(listOf(info))
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun deselect(update: Boolean = true) {
        selectedList.clear()
        if (!update) return
        updateCurrentList()
        updateBarTitle()
    }

    private fun onMultiSelect() {
        PrivateDialogBuilder(activity).setTitle(
            getString(
                R.string.msg_selected, selectedList.size.toString()
            )
        ).setItems(
            intArrayOf(
                R.string.action_freeze,
                R.string.action_unfreeze,
                R.string.action_tag_set,
                R.string.action_export_clipboard,
                R.string.action_remove_home,
                R.string.action_unfreeze_remove_home
            ).map { getString(it) }.toTypedArray()
        ) { _, which ->
            when (which) {
                0 -> {
                    setListFrozen(true, selectedList)
                    deselect()
                }

                1 -> {
                    setListFrozen(false, selectedList)
                    deselect()
                }

                2 -> triStateTagDialog()

                3 -> {
                    exportToClipboard(selectedList)
                    deselect()
                }

                4 -> {
                    selectedList.forEach { removeCheckedApp(it.packageName, false) }
                    HailData.saveApps()
                    deselect()
                }

                5 -> {
                    setListFrozen(false, selectedList, removeAfter = true)
                    deselect()
                }
            }
        }.setNegativeButton(R.string.action_deselect) { _, _ ->
            deselect()
        }.setNeutralButton(R.string.action_select_all) { _, _ ->
            selectedList.addAll(pagerAdapter.apps.filterNot { it in selectedList })
            updateCurrentList()
            updateBarTitle()
            onMultiSelect()
        }.show()
    }

    private fun triStateTagDialog() {
        val initialStates = Array(HailData.tags.size) { index ->
            val tagId = HailData.tags[index].second
            when (selectedList.count { tagId in it.tagIdList }) {
                selectedList.size -> ToggleableState.On
                0 -> ToggleableState.Off
                else -> ToggleableState.Indeterminate
            }
        }
        val states = mutableStateListOf(*initialStates)
        PrivateDialogBuilder(activity).setTitle(R.string.action_tag_set).setView(ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AppTheme { TriStateTagList(initialStates, states) } }
        }).setPositiveButton(android.R.string.ok) { _, _ ->
            selectedList.forEach {
                states.forEachIndexed { index, state ->
                    val tagId = HailData.tags[index].second
                    when (state) {
                        ToggleableState.On -> {
                            if (tagId !in it.tagIdList) it.tagIdList.add(tagId)
                        }

                        ToggleableState.Off -> it.tagIdList.remove(tagId)
                        ToggleableState.Indeterminate -> {}
                    }
                }
                if (it.tagIdList.isEmpty()) removeCheckedApp(it.packageName, false)
            }
            HailData.saveApps()
            deselect()
        }.setNeutralButton(R.string.action_tag_add) { _, _ ->
            showTagDialog(selectedList)
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    @Composable
    private fun TriStateTagList(initialStates: Array<ToggleableState>, states: MutableList<ToggleableState>) = Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        HailData.tags.forEachIndexed { index, tag ->
            Row(modifier = Modifier.fillMaxWidth().clickable {
                states[index] = if (initialStates[index] == ToggleableState.Indeterminate) when (states[index]) {
                    ToggleableState.On -> ToggleableState.Off
                    ToggleableState.Off -> ToggleableState.Indeterminate
                    ToggleableState.Indeterminate -> ToggleableState.On
                }
                else if (states[index] == ToggleableState.On) ToggleableState.Off
                else ToggleableState.On
            }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(
                    state = states[index],
                    onClick = null,
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary)
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = tag.first,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    private fun launchApp(packageName: String) {
        val mode = HailData.workingMode
        val trace = DecisionLog.begin("home", "launch", mode, packageName)
        binding.root.showActionFeedback(null)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val target = AppManager.prepareLaunch(packageName, mode, trace)
                if (!isResumed) {
                    trace?.skipped(packageName, "Home is no longer in the foreground")
                    return@launch
                }
                if (target != null) {
                    startActivity(target)
                    trace?.event("activity_started", packageName)
                    app.recordLaunch(packageName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                trace?.event("launch_unavailable", packageName, "reason" to "No current-user installation evidence")
                _binding?.root?.showActionFeedback(getString(R.string.unavailable_app_inline))
            } catch (e: RuntimeException) {
                trace?.error("launch_failed", packageName, e)
                _binding?.root?.showActionFeedback(e.localizedMessage ?: getString(R.string.activity_not_found))
            }
        }
    }

    private fun setListFrozen(
        frozen: Boolean, list: List<AppInfo> = HailData.checkedList, removeAfter: Boolean = false,
        skipWhitelisted: Boolean = false, source: String = "home_selection"
    ) {
        val mode = HailData.workingMode
        val trace = DecisionLog.change(source, frozen, mode, list.singleOrNull()?.packageName)
        val packages = list.mapNotNull {
            if (skipWhitelisted && it.whitelisted) {
                trace?.skipped(it.packageName, "Whitelisted for this action")
                null
            } else it.packageName
        }
        if (mode == HailData.MODE_DEFAULT) {
            trace?.skipped(packages.singleOrNull(), "Idle mode requires package controls or Android Settings")
            binding.root.showActionFeedback(getString(R.string.msg_guide))
            return
        }
        val origin = binding
        val host = activity
        origin.root.showActionFeedback(null)
        operations++
        origin.refresh.isRefreshing = true
        host.lifecycleScope.launch {
            try {
                if (mode == HailData.MODE_SHIZUKU_HIDE && withContext(Dispatchers.IO) {
                    runCatching { !HShizuku.isRoot }.getOrDefault(false)
                }) {
                    trace?.skipped(null, "ADB-backed Shizuku cannot hide packages")
                    if (_binding === origin) origin.root.showActionFeedback(getString(R.string.shizuku_hide_adb))
                    return@launch
                }
                val result = AppManager.changeFrozen(packages, frozen, mode = mode, trace = trace)
                if (removeAfter) {
                    val removable = packages.filter { result.reachedTarget(it, frozen) }
                    if (removable.isNotEmpty()) {
                        removable.forEach { HailData.removeCheckedApp(it, false) }
                        HailData.saveApps()
                    }
                }
                if (_binding === origin) {
                    origin.root.showActionFeedback(result.failureDetails(frozen))
                    if (frozen && isResumed) FreezeFeedback.show(result, origin.root)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                trace?.error("action_failed", packages.singleOrNull(), e)
                if (_binding === origin) origin.root.showActionFeedback(e.localizedMessage ?: host.getString(R.string.permission_denied))
            } finally {
                operations--
                if (_binding === origin) {
                    origin.refresh.isRefreshing = operations > 0
                    if (operations == 0) updateCurrentList()
                }
            }
        }
    }

    private fun showTagDialog(list: List<AppInfo>? = null) {
        val tabLayout = tabs ?: return  // The view has been destroyed; return directly.
        val homeAdapter = adapter ?: return

        val binding = DialogInputBinding.inflate(layoutInflater)
        binding.inputLayout.setHint(R.string.tag)
        list ?: binding.editText.setText(tag?.first ?: "")
        PrivateDialogBuilder(activity).setTitle(if (list != null) R.string.action_tag_add else R.string.action_tag_set)
            .setView(binding.root).setPositiveButton(android.R.string.ok) { _, _ ->
                val tagName = binding.editText.text.toString()
                val tagId = tagName.hashCode()
                if (HailData.tags.any { it.first == tagName || it.second == tagId }) return@setPositiveButton
                if (list != null) { // Add tag
                    HailData.tags.add(tagName to tagId)
                    homeAdapter.notifyItemInserted(homeAdapter.itemCount - 1)
                    if (query.isEmpty() && tabLayout.tabCount == 2) tabLayout.isVisible = true
                    if (list == selectedList) triStateTagDialog() else tagDialog(list.first())
                } else { // Rename tag
                    val position = tabLayout.selectedTabPosition
                    val defaultTab = position == 0
                    val oldTagId = HailData.tags[position].second
                    HailData.tags[position] = tagName to if (defaultTab) 0 else tagId
                    if (!defaultTab) {
                        HailData.checkedList.forEach {
                            val index = it.tagIdList.indexOf(oldTagId)
                            if (index != -1) it.tagIdList[index] = tagId
                        }
                    }
                    homeAdapter.notifyItemChanged(position)
                }
                HailData.saveTags()
            }.apply {
                val position = tabLayout.selectedTabPosition
                if (list != null || position == 0) return@apply
                setNeutralButton(R.string.action_tag_remove) { _, _ ->
                    val tagIdToRemove = HailData.tags[position].second
                    HailData.checkedList.toList().forEach {
                        if (it.tagIdList.remove(tagIdToRemove) && it.tagIdList.isEmpty()) {
                            removeCheckedApp(it.packageName, false)
                        }
                    }
                    HailData.tags.removeAt(position)
                    homeAdapter.notifyItemRemoved(position)
                    if (tabLayout.tabCount == 1) tabLayout.isVisible = false
                    HailData.saveTags()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun exportToClipboard(list: List<AppInfo>) {
        if (list.isEmpty()) return
        HUI.copyText(if (list.size > 1) JSONArray().run {
            list.forEach { put(it.packageName) }
            toString()
        } else list[0].packageName)
    }

    private fun importFromClipboard() = runCatching {
        val str = HUI.pasteText() ?: throw IllegalArgumentException()
        val json = if (str.contains('[')) JSONArray(
            str.substring(
                str.indexOf('[')..str.indexOf(']', str.indexOf('['))
            )
        )
        else JSONArray().put(str)
        var i = 0
        for (index in 0 until json.length()) {
            val pkg = json.getString(index)
            if (HPackages.getApplicationInfoOrNull(pkg) != null && !HailData.isChecked(pkg)) {
                HailData.addCheckedApp(pkg, tag?.second ?: 0, false)
                i++
            }
        }
        if (i > 0) {
            HailData.saveApps()
            updateCurrentList()
        }
    }.onFailure { _binding?.root?.showActionFeedback(getString(R.string.operation_failed, it.localizedMessage ?: "Unknown")) }

    private suspend fun importFrozenApp(): Int {
        val tagId = tag?.second ?: 0
        val mode = HailData.workingMode
        val packages = withContext(Dispatchers.IO) {
            PackageLookup(mode).all().filterValues { AppManager.state(it, mode) == AppInfo.State.FROZEN }.keys
        }
        val added = packages.filterNot(HailData::isChecked)
        added.forEach { HailData.addCheckedApp(it, tagId, false) }
        return added.size
    }

    private fun removeCheckedApp(packageName: String, saveApps: Boolean = true) {
        HailData.removeCheckedApp(packageName, saveApps)
        if (saveApps) updateCurrentList()
    }

    private fun MenuItem.updateIcon() = icon?.setTint(
        MaterialColors.getColor(
            activity.findViewById(R.id.toolbar),
            if (multiselect) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface
        )
    )

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_multiselect -> {
                multiselect = !multiselect
                item.updateIcon()
                if (multiselect) {
                    updateBarTitle()
                } else deselect()
            }

            R.id.action_freeze_current -> setListFrozen(true, currentApps(), skipWhitelisted = true, source = "home_current")

            R.id.action_unfreeze_current -> setListFrozen(false, currentApps(), source = "home_current")
            R.id.action_freeze_all -> setListFrozen(true, source = "home_all")
            R.id.action_unfreeze_all -> setListFrozen(false, source = "home_all")
            R.id.action_freeze_non_whitelisted -> setListFrozen(true, skipWhitelisted = true, source = "home_non_whitelisted")

            R.id.action_import_clipboard -> importFromClipboard()
            R.id.action_import_frozen -> lifecycleScope.launch {
                val size = importFrozenApp()
                if (size > 0) {
                    HailData.saveApps()
                    updateCurrentList()
                }
            }

            R.id.action_export_current -> exportToClipboard(currentApps())
            R.id.action_export_all -> exportToClipboard(HailData.checkedList)
        }
        return false
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_home, menu)
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        if (HailData.nineKeySearch) {
            val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            editText.inputType = InputType.TYPE_CLASS_PHONE
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            private var inited = false
            override fun onQueryTextChange(newText: String): Boolean {
                if (inited) {
                    query = newText
                    tabs?.run {
                        isVisible = query.isEmpty() && tabCount > 1
                    }
                    updateCurrentList()
                } else inited = true
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean = true
        })
        menu.findItem(R.id.action_multiselect).updateIcon()
    }

    override fun onDestroyView() {
        activity?.fab?.setOnClickListener(null)
        activity?.fab?.setOnLongClickListener(null)
        binding.recyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
