package com.aistra.hail.ui.home

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogPackageStatusBinding
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HUI
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aistra.hail.utils.HShizuku
import com.aistra.hail.utils.PackageLookup
import rikka.shizuku.Shizuku

class PackageStatusDialog : DialogFragment() {
    companion object {
        const val RESULT = "package_status"
        const val REMOVE = "remove"
        private const val PKG = "package"
        private const val MESSAGE = "message"

        fun show(manager: FragmentManager, pkg: String, message: String? = null) {
            if (!manager.isStateSaved && manager.findFragmentByTag(RESULT) == null) {
                PackageStatusDialog().apply { arguments = bundleOf(PKG to pkg, MESSAGE to message) }.show(manager, RESULT)
            }
        }
    }

    private val pkg get() = requireArguments().getString(PKG)!!
    private lateinit var binding: DialogPackageStatusBinding
    private var message: String? = null
    private var remove = false
    private var work: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogPackageStatusBinding.inflate(layoutInflater)
        binding.report.text = pkg
        message = (savedInstanceState ?: requireArguments()).getString(MESSAGE)
        return MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.package_status_controls).setView(binding.root)
            .setPositiveButton(R.string.action_details, null).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.copy_details, null).create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(MESSAGE, message)
    }

    override fun onResume() {
        super.onResume()
        val alert = requireDialog() as AlertDialog
        alert.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
            } catch (_: android.content.ActivityNotFoundException) {
                HUI.showToast(R.string.app_info_unavailable)
            }
        }
        alert.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { HUI.copyText(binding.report.text.toString()) }
        if (work?.isActive != true) refresh()
    }

    private fun refresh() {
        work = lifecycleScope.launch { render() }
    }

    private suspend fun render() {
        val mode = HailData.workingMode
        val shizukuUid = if (mode.endsWith(HailData.DISABLE) && mode != HailData.MODE_SHIZUKU_DISABLE) null else
            withContext(Dispatchers.IO) { if (HShizuku.isAuthorized()) runCatching { Shizuku.getUid() }.getOrNull() else null }
        val method = when {
            mode.endsWith(HailData.DISABLE) -> mode
            shizukuUid != null -> HailData.MODE_SHIZUKU_DISABLE
            else -> null
        }
        val lookup = PackageLookup(method ?: mode)
        val record = withContext(Dispatchers.IO) { lookup.find(pkg) }
        val setting = withContext(Dispatchers.IO) { record?.setting() }
        val enabled = record?.takeIf { it.installed }?.canDisable(setting)
        val upgrade = enabled == false && setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
            withContext(Dispatchers.IO) { lookup.disableSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED }
        val alert = dialog as? AlertDialog ?: return
        val modeName = resources.getStringArray(R.array.working_mode_entries).getOrNull(HailData.WORKING_MODE_VALUES.indexOf(mode)) ?: mode
        val label = record?.info?.loadLabel(requireContext().packageManager)?.toString() ?: pkg
        alert.setTitle(label)
        val status = when (enabled) {
            true -> if (setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
                R.string.package_until_used else R.string.package_enabled
            false -> if (upgrade) R.string.package_disabled_user else R.string.package_disabled
            null -> if (record == null) R.string.package_unknown_here else R.string.package_retained_here
        }
        binding.report.text = listOfNotNull(pkg, getString(R.string.package_user_mode, HPackages.myUserId, modeName),
            shizukuUid?.let { getString(if (it == 0) R.string.package_shizuku_root else R.string.package_shizuku_uid, it) },
            getString(status), if (enabled != null && method == null) getString(R.string.package_native_guidance) else null,
            message).joinToString("\n\n")
        alert.getButton(DialogInterface.BUTTON_POSITIVE).isVisible = record != null
        binding.removeFromHome.isVisible = enabled == null && HailData.isChecked(pkg)
        binding.removeFromHome.setOnClickListener { remove = true; dismiss() }
        binding.fullDisable.isVisible = upgrade && pkg != requireContext().packageName
        binding.fullDisable.isEnabled = true
        binding.fullDisable.setOnClickListener { if (method != null) change(true, method) }
        binding.packageAction.apply {
            isVisible = enabled == null || (method != null && pkg != requireContext().packageName)
            isEnabled = true
            setText(when {
                enabled == null -> R.string.check_again
                method != mode -> if (enabled) R.string.disable_with_shizuku else R.string.enable_with_shizuku
                enabled -> R.string.disable_app
                else -> R.string.enable_app
            })
            setOnClickListener {
                if (enabled != null && method != null) change(enabled, method)
                else { isEnabled = false; message = null; refresh() }
            }
        }
    }

    private fun change(disable: Boolean, mode: String) {
        binding.packageAction.isEnabled = false
        binding.fullDisable.isEnabled = false
        work = lifecycleScope.launch {
            val result = AppManager.changeFrozen(listOf(pkg), disable, mode = mode)
            message = if (result.reachedTarget(pkg)) null else AppManager.resultMessage(result, disable)
            render()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (isAdded) parentFragmentManager.setFragmentResult(RESULT, if (remove) bundleOf(REMOVE to pkg) else Bundle())
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        work?.cancel()
        super.onDestroyView()
    }
}
