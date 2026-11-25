package com.aistra.hail.ui.apps

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.CompoundButton
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.FragmentAppsBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.HFiles
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HPolicy
import com.aistra.hail.utils.HUI
import com.aistra.hail.views.HRecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream

class AppsFragment : MainFragment(), AppsAdapter.OnItemClickListener, AppsAdapter.OnItemCheckedChangeListener,
    MenuProvider {

    private val model: AppsViewModel by viewModels()

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!
    private lateinit var appsAdapter: AppsAdapter

    private var contextApp: ApplicationInfo? = null
    private var contextTargetDisabled = false


    private var exportApkPkg: String? = null
    private val exportApk =
        registerForActivityResult(CreateDocument("application/vnd.android.package-archive")) { uri ->
            val exportApkPkg = exportApkPkg
            this.exportApkPkg = null
            if (exportApkPkg == null || uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val applicationInfo = HPackages.getApplicationInfoOrNull(exportApkPkg) ?: return@launch
                val dialog =
                    MaterialAlertDialogBuilder(activity).setView(R.layout.dialog_progress).setCancelable(false).show()
                runCatching {
                    withContext(Dispatchers.IO) {
                        FileInputStream(applicationInfo.sourceDir).use { source ->
                            activity.contentResolver.openOutputStream(uri, "rwt").use { target ->
                                if (target == null) return@withContext
                                HFiles.copy(source, target)
                            }
                        }
                    }
                }.onSuccess {
                    HUI.showToast(R.string.msg_extract_apk, uri.toString())
                }.onFailure {
                    HUI.showToast(R.string.operation_failed, it.localizedMessage ?: "Unknown", true)
                }
                dialog.dismiss()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        appsAdapter = AppsAdapter().apply {
            onItemClickListener = this@AppsFragment
            onItemCheckedChangeListener = this@AppsFragment
        }
        binding.refresh.apply {
            setOnRefreshListener { updateAppList() }
            applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true) }
        }
        binding.recyclerView.apply {
            activity.appbar.setLiftOnScrollTargetView(this)
            layoutManager = GridLayoutManager(activity, resources.getInteger(R.integer.apps_span))
            adapter = appsAdapter
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
            registerForContextMenu(this)
        }

        model.isRefreshing.observe(viewLifecycleOwner) {
            binding.refresh.isRefreshing = it
        }
        model.displayApps.observe(viewLifecycleOwner) {
            appsAdapter.submitList(it)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AppManager.changes.collect { updateAppList() }
            }
        }
        return binding.root
    }

    override fun onItemClick(buttonView: CompoundButton) {
        buttonView.toggle()
    }

    override fun onCreateContextMenu(
        menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        val holder = (menuInfo as? HRecyclerView.RecyclerViewContextMenuInfo)?.viewHolder as? AppsAdapter.ViewHolder ?: return
        val row = appsAdapter.currentList.getOrNull(holder.bindingAdapterPosition) ?: return
        val info = row.info
        contextApp = info
        contextTargetDisabled = if (HailData.workingMode.endsWith(HailData.DISABLE)) !row.frozen else info.enabled
        menu.setHeaderTitle(info.loadLabel(activity.packageManager))
        activity.menuInflater.inflate(R.menu.menu_apps_action, menu)
        if (HPackages.isSystemApp(info) && row.installed &&
            info.packageName != app.packageName) {
            menu.findItem(R.id.action_system_enabled).apply {
                isVisible = true
                setTitle(if (HailData.workingMode.endsWith(HailData.DISABLE)) {
                    if (contextTargetDisabled) R.string.disable_app else R.string.enable_app
                } else {
                    if (info.enabled) R.string.disable_in_settings else R.string.enable_in_settings
                })
            }
        }
        super.onCreateContextMenu(menu, v, menuInfo)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = contextApp ?: return super.onContextItemSelected(item)
        val name = info.loadLabel(app.packageManager)
        val pkg = info.packageName
        when (item.itemId) {
            R.id.action_system_enabled -> changeSystemAppEnabled(info)
            R.id.action_details -> openAppInfo(pkg)

            R.id.action_export_clipboard -> {
                HUI.copyText(pkg)
                HUI.showToast(R.string.msg_text_copied, pkg)
            }

            R.id.action_extract_apk -> extractApk(pkg)
            R.id.action_uninstall -> uninstallApp(name, pkg)
            R.id.action_reinstall -> {
                if (AppManager.reinstallApp(pkg)) updateAppList()
                else HUI.showToast(R.string.operation_failed, name)
            }

            else -> return super.onContextItemSelected(item)
        }
        return true
    }

    private fun openAppInfo(pkg: String) {
        if (!HUI.startActivity(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HPackages.packageUri(pkg))) {
            HUI.showToast(R.string.app_info_unavailable)
        }
    }

    private fun changeSystemAppEnabled(info: ApplicationInfo) {
        val pkg = info.packageName
        if (!HailData.workingMode.endsWith(HailData.DISABLE)) {
            openAppInfo(pkg)
            return
        }
        // The action was labelled from this snapshot. Repeating it must never reverse a newer change.
        val disable = contextTargetDisabled
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppManager.changeFrozen(listOf(pkg), disable)
            when {
                result.reachedTarget(pkg) -> HUI.showToast(AppManager.resultMessage(result, disable))
                pkg in result.missing -> HUI.showToast(R.string.app_not_installed)
                isResumed -> MaterialAlertDialogBuilder(requireContext())
                    .setTitle(info.loadLabel(app.packageManager))
                    .setMessage(AppManager.resultMessage(result, disable))
                    .setPositiveButton(R.string.action_details) { _, _ -> openAppInfo(pkg) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            if (result.changed.isEmpty()) updateAppList()
        }
    }

    private fun extractApk(pkg: String) {
        exportApkPkg = pkg
        exportApk.launch(HPackages.getUnhiddenPackageInfoOrNull(pkg)?.exportFileName ?: pkg)
    }

    private fun uninstallApp(name: CharSequence, pkg: String) {
        when {
            HPackages.isAppUninstalled(pkg) -> HUI.showToast(R.string.app_not_installed)

            pkg == app.packageName -> {
                when {
                    HPolicy.isDeviceOwnerActive -> activity.ownerRemoveDialog()
                    HPolicy.isProfileOwner -> HPolicy.removeProfileOwner()
                    HPolicy.isAdminActive -> HPolicy.removeActiveAdmin()
                    else -> showUninstallDialog(name, pkg)
                }
            }

            HailData.workingMode == HailData.MODE_DEFAULT -> AppManager.uninstallApp(pkg)
            else -> showUninstallDialog(name, pkg)
        }
    }

    private fun showUninstallDialog(name: CharSequence, pkg: String) {
        MaterialAlertDialogBuilder(activity).setTitle(name).setMessage(R.string.msg_uninstall)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (AppManager.uninstallApp(pkg)) updateAppList()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onItemCheckedChange(
        buttonView: CompoundButton, isChecked: Boolean, packageName: String
    ) {
        if (isChecked) HailData.addCheckedApp(packageName)
        else HailData.removeCheckedApp(packageName)
        buttonView.isChecked = HailData.isChecked(packageName)
        model.setChecked(packageName, buttonView.isChecked)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_apps, menu)
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        if (HailData.nineKeySearch) {
            val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            editText.inputType = InputType.TYPE_CLASS_PHONE
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                model.postQuery(newText, if (newText.isEmpty()) 0L else 150L)
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                model.postQuery(query, 0L)
                return true
            }
        })
    }

    override fun onPrepareMenu(menu: Menu) {
        super.onPrepareMenu(menu)
        menu.findItem(
            when (HailData.sortBy) {
                HailData.SORT_INSTALL -> R.id.sort_by_install
                HailData.SORT_UPDATE -> R.id.sort_by_update
                else -> R.id.sort_by_name
            }
        ).isChecked = true
        menu.findItem(
            if (HailData.filterSystemApps) R.id.filter_system_apps else R.id.filter_user_apps
        ).isChecked = true
        menu.findItem(R.id.filter_frozen_apps).isChecked = HailData.filterFrozenApps
        menu.findItem(R.id.filter_unfrozen_apps).isChecked = HailData.filterUnfrozenApps
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sort_by_name -> changeAppsSort(HailData.SORT_NAME, item)
            R.id.sort_by_install -> changeAppsSort(HailData.SORT_INSTALL, item)
            R.id.sort_by_update -> changeAppsSort(HailData.SORT_UPDATE, item)
            R.id.filter_user_apps -> changeAppsFilter(HailData.FILTER_USER_APPS, item)
            R.id.filter_system_apps -> MaterialAlertDialogBuilder(activity)
                .setMessage(getString(R.string.system_app_controls) + "\n\n" + getString(R.string.freeze_system_app))
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    changeAppsFilter(HailData.FILTER_SYSTEM_APPS, item)
                }.setNegativeButton(android.R.string.cancel, null).show()

            R.id.filter_frozen_apps -> changeAppsFilter(HailData.FILTER_FROZEN_APPS, item)
            R.id.filter_unfrozen_apps -> changeAppsFilter(HailData.FILTER_UNFROZEN_APPS, item)
            else -> return false
        }
        return true
    }

    private fun changeAppsSort(sort: String, item: MenuItem) {
        item.isChecked = true
        HailData.changeAppsSort(sort)
        updateDisplayAppList()
    }

    private fun changeAppsFilter(filter: String, item: MenuItem) {
        when (item.itemId) {
            R.id.filter_user_apps -> {
                item.isChecked = true
                HailData.changeAppsFilter(filter, item.isChecked)
                HailData.changeAppsFilter(HailData.FILTER_SYSTEM_APPS, false)
            }

            R.id.filter_system_apps -> {
                item.isChecked = true
                HailData.changeAppsFilter(filter, item.isChecked)
                HailData.changeAppsFilter(HailData.FILTER_USER_APPS, false)
            }

            else -> {
                item.isChecked = !item.isChecked
                HailData.changeAppsFilter(filter, item.isChecked)
            }
        }
        updateDisplayAppList()
    }

    private fun updateAppList() = model.updateAppList()
    private fun updateDisplayAppList() = model.updateDisplayAppList()

    override fun onResume() {
        super.onResume()
        updateAppList()
    }

    override fun onDestroyView() {
        contextApp = null
        binding.recyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
