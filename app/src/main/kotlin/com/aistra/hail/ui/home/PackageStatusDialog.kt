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
import com.aistra.hail.utils.DecisionLog
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HUI
import com.aistra.hail.ui.PrivateDialogBuilder
import com.aistra.hail.ui.FreezeFeedback
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
        private const val SOURCE = "source"

        fun show(manager: FragmentManager, pkg: String, message: String? = null, source: String = "home_status") {
            if (!manager.isStateSaved && manager.findFragmentByTag(RESULT) == null) {
                PackageStatusDialog().apply { arguments = bundleOf(PKG to pkg, MESSAGE to message, SOURCE to source) }.show(manager, RESULT)
            }
        }
    }

    private val pkg get() = requireArguments().getString(PKG)!!
    private val source get() = requireArguments().getString(SOURCE) ?: "home_status"
    private lateinit var binding: DialogPackageStatusBinding
    private var message: String? = null
    private var remove = false
    private var work: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogPackageStatusBinding.inflate(layoutInflater)
        binding.report.text = pkg
        message = (savedInstanceState ?: requireArguments()).getString(MESSAGE)
        return PrivateDialogBuilder(requireContext()).setTitle(R.string.package_status_controls).setView(binding.root)
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
                message = getString(R.string.app_info_unavailable)
                refresh()
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
        val trace = DecisionLog.begin(source, "inspect", mode, pkg)
        val shizukuUid = if (mode.endsWith(HailData.DISABLE) && mode != HailData.MODE_SHIZUKU_DISABLE) null else
            withContext(Dispatchers.IO) { if (HShizuku.isAuthorized(trace)) runCatching { Shizuku.getUid() }.getOrNull() else null }
        val method = when {
            mode.endsWith(HailData.DISABLE) -> mode
            shizukuUid != null -> HailData.MODE_SHIZUKU_DISABLE
            else -> null
        }
        val lookup = PackageLookup(method ?: mode, trace)
        val record = withContext(Dispatchers.IO) { lookup.find(pkg) }
        val hasLauncher = if (record?.installed == true) withContext(Dispatchers.IO) {
            HPackages.hasLauncherActivity(pkg, trace)
        } else null
        val setting = withContext(Dispatchers.IO) { record?.setting() }
        val enabled = record?.takeIf { it.installed }?.canDisable(setting)
        val storedSetting = record?.takeIf { !it.installed && it.info?.let(HPackages::isSystemApp) == true }
            ?.enabledSetting
        val canChangeStoredSetting = storedSetting != null && method == HailData.MODE_SHIZUKU_DISABLE && shizukuUid != null
        val storedDisabled = storedSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            storedSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        val upgrade = enabled == false && setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
            withContext(Dispatchers.IO) { lookup.disableSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED }
        val manifestDisabled = enabled == false && setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        trace?.event("status_controls", pkg, "workingMode" to mode, "effectiveMode" to method, "serviceUid" to shizukuUid,
            "installed" to record?.installed, "enabled" to enabled, "observedSetting" to setting, "offerFullDisable" to upgrade,
            "offerStoredSetting" to canChangeStoredSetting)
        val alert = dialog as? AlertDialog ?: return
        val modeName = resources.getStringArray(R.array.working_mode_entries).getOrNull(HailData.WORKING_MODE_VALUES.indexOf(mode)) ?: mode
        val label = record?.info?.loadLabel(requireContext().packageManager)?.toString() ?: pkg
        alert.setTitle(label)
        val status = when (enabled) {
            true -> if (setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
                R.string.package_until_used else R.string.package_enabled
            false -> when {
                manifestDisabled -> R.string.package_manifest_disabled
                upgrade -> R.string.package_disabled_user
                else -> R.string.package_disabled
            }
            null -> if (record == null) R.string.package_unknown_here else R.string.package_retained_here
        }
        val stopConfirmed = record?.info?.let { !it.enabled && it.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0 } == true
        val checkStop = method != null && enabled == false && !upgrade && !manifestDisabled && !stopConfirmed
        val storedStatus = when (storedSetting) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> R.string.package_retained_setting_default
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> R.string.package_retained_setting_enabled
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ->
                R.string.package_retained_setting_disabled
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> R.string.package_retained_setting_until_used
            else -> null
        }
        binding.report.text = listOfNotNull(pkg, getString(R.string.package_user_mode, HPackages.myUserId, modeName),
            shizukuUid?.let { getString(if (it == 0) R.string.package_shizuku_root else R.string.package_shizuku_uid, it) },
            if (mode == HailData.MODE_SHIZUKU_DISABLE && shizukuUid == null)
                getString(if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) R.string.authorize_shizuku else R.string.start_shizuku)
            else null,
            getString(status),
            if (hasLauncher == false) getString(R.string.package_no_launcher) else null,
            storedStatus?.let { getString(it) },
            if (canChangeStoredSetting) getString(R.string.package_retained_setting_guidance) else null,
            if (enabled == false && method != null) getString(if (stopConfirmed) R.string.disable_confirmed else R.string.disable_unconfirmed) else null,
            if (method == HailData.MODE_SHIZUKU_DISABLE && shizukuUid == 2000 &&
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) getString(R.string.package_until_used_shell) else null,
            if (enabled != null && method == null) getString(R.string.package_native_guidance) else null,
            message).joinToString("\n\n")
        alert.getButton(DialogInterface.BUTTON_POSITIVE).isVisible = record != null
        binding.removeFromHome.isVisible = enabled == null && HailData.isChecked(pkg)
        binding.removeFromHome.setOnClickListener { remove = true; dismiss() }
        binding.enableInstead.isVisible = method != null && (manifestDisabled || upgrade || checkStop) && pkg != requireContext().packageName
        binding.enableInstead.isEnabled = true
        binding.enableInstead.setOnClickListener { if (method != null) change(false, method) }
        binding.packageAction.apply {
            isVisible = enabled == null || (method != null && pkg != requireContext().packageName)
            isEnabled = true
            setText(when {
                canChangeStoredSetting -> if (storedDisabled) R.string.enable_app else R.string.disable_app
                manifestDisabled -> R.string.apply_explicit_disable
                checkStop -> R.string.verify_stop
                enabled == null -> R.string.check_again
                upgrade -> R.string.apply_full_disable
                method != mode -> if (enabled) R.string.disable_with_shizuku else R.string.enable_with_shizuku
                enabled -> R.string.disable_app
                else -> R.string.enable_app
            })
            setOnClickListener {
                if (canChangeStoredSetting) change(!storedDisabled, method)
                else if (enabled != null && method != null) change(enabled || manifestDisabled || upgrade || checkStop, method)
                else { isEnabled = false; message = null; refresh() }
            }
        }
    }

    private fun change(disable: Boolean, mode: String) {
        binding.packageAction.isEnabled = false
        binding.enableInstead.isEnabled = false
        work = lifecycleScope.launch {
            val result = AppManager.changeFrozen(listOf(pkg), disable, mode = mode, source = source)
            message = if (result.reachedTarget(pkg, disable)) null else AppManager.resultMessage(result, disable)
            if (disable && isResumed) FreezeFeedback.show(result, binding.root)
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
