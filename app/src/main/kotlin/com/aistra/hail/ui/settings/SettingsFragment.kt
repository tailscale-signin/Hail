package com.aistra.hail.ui.settings

import android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.Shortcut
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.aistra.hail.ui.PrivateDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.*
import rikka.shizuku.Shizuku

class SettingsFragment : MainFragment(), MenuProvider {
    private val backups: BackupViewModel by viewModels()
    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let { uri -> backups.open(uri, restore = false) }
    }
    private val loadBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> backups.open(uri, restore = true) }
    }

    private val saveDebugLog = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) app.exportDebugLog(uri)
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    ProvidePreferenceLocals {
                        SettingsScreen()
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        BackupDialog()
        val autoFreezeAfterLock = rememberPreferenceState(HailData.AUTO_FREEZE_AFTER_LOCK, false)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            listPreference(
                key = HailData.WORKING_MODE,
                defaultValue = HailData.MODE_DEFAULT,
                onValueChange = ::onWorkingModeChange,
                values = HailData.WORKING_MODE_VALUES,
                valueToText = { it.toEntry(HailData.WORKING_MODE_VALUES, R.array.working_mode_entries) },
                summary = { mode ->
                    val name = mode.toEntry(HailData.WORKING_MODE_VALUES, R.array.working_mode_entries)
                    val detail = when (mode.substringAfterLast('_')) {
                        HailData.DISABLE -> R.string.privacy_disable
                        HailData.HIDE -> R.string.privacy_hide
                        HailData.SUSPEND -> R.string.privacy_suspend
                        HailData.STOP -> R.string.privacy_stop
                        else -> null
                    }
                    if (detail == null) name else "$name\n${stringResource(detail)}"
                },
                titleId = R.string.working_mode,
                icon = Icons.Outlined.Adb,
                type = ListPreferenceType.ALERT_DIALOG
            )
            switchPreference(
                key = HailData.BIOMETRIC_LOGIN,
                defaultValue = false,
                onValueChange = { _, value ->
                    if (value && !com.aistra.hail.ui.DeviceUnlock.available(requireContext())) {
                        HUI.showToast(R.string.unlock_unavailable)
                        false
                    } else {
                        if (value) resetDynamicShortcuts()
                        true
                    }
                },
                titleId = R.string.action_biometric,
                icon = Icons.Outlined.Fingerprint
            )
            horizontalDivider()
            preferenceCategory(key = "backup", title = { Text(stringResource(R.string.backup_title)) })
            preference(key = "backup_save", title = { Text(stringResource(R.string.backup_save)) },
                icon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                onClick = { saveBackup.launch("Hail-backup.json") })
            preference(key = "backup_restore", title = { Text(stringResource(R.string.backup_restore)) },
                icon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                onClick = { loadBackup.launch(arrayOf("application/json", "text/*", "application/octet-stream")) })
            horizontalDivider()
            preferenceCategory(key = "debug", title = { Text(stringResource(R.string.debug_mode)) })
            item(key = DecisionLog.PREF) {
                val recording = remember { mutableStateOf(DecisionLog.enabled) }
                SwitchPreference(value = recording.value, onValueChange = {
                    DecisionLog.setEnabled(it)
                    recording.value = it
                }, title = { Text(stringResource(R.string.debug_logging)) },
                    summary = { Text(stringResource(R.string.debug_logging_summary)) },
                    icon = { Icon(Icons.Outlined.BugReport, contentDescription = null) })
            }
            preference(key = "erase_debug_log", title = { Text(stringResource(R.string.erase_debug_log)) },
                icon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
                onClick = {
                    PrivateDialogBuilder(requireContext()).setMessage(R.string.erase_debug_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.erase_debug_log) { _, _ ->
                            lifecycleScope.launch {
                                runCatching { DecisionLog.clear() }
                                    .onSuccess { HUI.showToast(R.string.debug_log_cleared) }
                                    .onFailure { HUI.showToast(R.string.debug_clear_failed) }
                            }
                        }.show()
                })
            preference(key = "export_debug_log", title = { Text(stringResource(R.string.debug_export)) },
                icon = { Icon(Icons.Outlined.SaveAlt, contentDescription = null) },
                onClick = { saveDebugLog.launch(DecisionLog.suggestedName()) })
            horizontalDivider()
            preferenceCategory(key = "customize", title = { Text(text = stringResource(R.string.title_customize)) })
            listPreference(
                key = HailData.APP_THEME,
                defaultValue = HailData.THEME_DARK,
                onValueChange = { _, value ->
                    app.setAppTheme(value)
                    true
                },
                values = HailData.APP_THEME_VALUES,
                entriesId = R.array.app_theme_entries,
                titleId = R.string.app_theme,
                icon = Icons.Outlined.DarkMode
            )
            listPreference(
                key = HailData.ICON_PACK,
                defaultValue = HailData.ACTION_NONE,
                onValueChange = { _, _ ->
                    AppIconCache.clear()
                    true
                },
                values = mutableListOf(HailData.ACTION_NONE).apply {
                    addAll(Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME").let {
                        if (HTarget.T) app.packageManager.queryIntentActivities(
                            it, PackageManager.ResolveInfoFlags.of(0)
                        ) else app.packageManager.queryIntentActivities(it, 0)
                    }.map { it.activityInfo.packageName })
                },
                titleId = R.string.icon_pack,
                icon = Icons.Outlined.Palette,
                summary = { iconPackName(it) },
                valueToText = ::iconPackName
            )
            switchPreference(
                key = HailData.GRAYSCALE_ICON,
                defaultValue = true,
                titleId = R.string.grayscale_icon,
                icon = Icons.Outlined.FilterBAndW
            )
            switchPreference(
                key = HailData.COMPACT_ICON,
                defaultValue = false,
                titleId = R.string.compact_icon,
                icon = Icons.Outlined.Apps
            )
            switchPreference(
                key = HailData.SYNTHESIZE_ADAPTIVE_ICONS,
                defaultValue = false,
                titleId = R.string.synthesize_adaptive_icons,
                icon = Icons.Outlined.Layers
            )
            sliderPreference(
                key = HailData.HOME_FONT_SIZE,
                defaultValue = 14f,
                title = { Text(text = stringResource(R.string.home_font_size)) },
                valueRange = 11f..16f,
                valueSteps = 4,
                icon = { Icon(imageVector = Icons.Outlined.TextFields, contentDescription = null) },
                valueText = { Text(text = "%.0f".format(it)) },
            )
            switchPreference(
                key = HailData.FUZZY_SEARCH,
                defaultValue = false,
                titleId = R.string.fuzzy_search,
                icon = Icons.AutoMirrored.Outlined.ManageSearch
            )
            switchPreference(
                key = HailData.NINE_KEY_SEARCH,
                defaultValue = false,
                titleId = R.string.nine_key,
                icon = Icons.Outlined.Dialpad
            )
            listPreference(
                key = HailData.TILE_ACTION,
                defaultValue = HailData.tileAction,
                values = HailData.TILE_ACTION_VALUES,
                entriesId = R.array.tile_action_entries,
                titleId = R.string.tile_action,
                icon = Icons.Outlined.DashboardCustomize
            )
            horizontalDivider()
            preferenceCategory(key = "auto_freeze", title = { Text(text = stringResource(R.string.auto_freeze)) })
            switchPreference(
                rememberState = { autoFreezeAfterLock },
                onValueChange = { _, value ->
                    app.setAutoFreezeService(value)
                    true
                },
                titleId = R.string.auto_freeze_after_lock,
                icon = Icons.Outlined.ScreenLockPortrait
            )
            sliderPreference(
                key = HailData.AUTO_FREEZE_DELAY,
                defaultValue = 0f,
                title = { Text(text = stringResource(R.string.auto_freeze_delay)) },
                valueRange = 0f..30f,
                valueSteps = 29,
                enabled = { autoFreezeAfterLock.value },
                icon = { Icon(imageVector = Icons.Outlined.LockClock, contentDescription = null) },
                valueText = { Text(text = "%.0f".format(it)) },
            )
            switchPreference(
                key = HailData.SKIP_WHILE_CHARGING,
                defaultValue = false,
                titleId = R.string.skip_while_charging,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.BatteryChargingFull
            )
            switchPreference(
                key = HailData.SKIP_FOREGROUND_APP,
                defaultValue = false,
                onValueChange = { _, value ->
                    if (value && !HSystem.checkOpUsageStats(requireContext())) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        false
                    } else true
                },
                titleId = R.string.skip_foreground_app,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.Android
            )
            switchPreference(
                key = HailData.SKIP_NOTIFYING_APP,
                defaultValue = false,
                onValueChange = { _, value ->
                    val isGranted = NotificationManagerCompat.getEnabledListenerPackages(requireContext())
                        .contains(requireContext().packageName)
                    if (value && !isGranted) {
                        app.setAutoFreezeServiceEnabled(true)
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        false
                    } else true
                },
                titleId = R.string.skip_notifying_app,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.NotificationsActive
            )
            horizontalDivider()
            preferenceCategory(key = "shortcuts", title = { Text(text = stringResource(R.string.title_shortcuts)) })
            switchPreference(key = HailData.RECENT_SHORTCUTS, defaultValue = false,
                titleId = R.string.recent_shortcuts, icon = Icons.Outlined.AppShortcut,
                onValueChange = { _, enabled ->
                    if (!enabled) app.clearRecentShortcuts()
                    true
                })
            preference(
                key = "add_pin_shortcut",
                title = { Text(text = stringResource(R.string.action_add_pin_shortcut)) },
                icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.Shortcut, contentDescription = null) },
                onClick = ::addPinShortcut
            )
            preference(
                key = "add_all_app_shortcuts",
                title = { Text(stringResource(R.string.add_all_app_shortcuts)) },
                summary = { Text(stringResource(R.string.add_all_app_shortcuts_summary)) },
                icon = { Icon(Icons.Outlined.Apps, contentDescription = null) },
                onClick = {
                    val dialogs = childFragmentManager
                    if (!dialogs.isStateSaved && dialogs.findFragmentByTag(BulkShortcutDialog.TAG) == null) {
                        BulkShortcutDialog().showNow(dialogs, BulkShortcutDialog.TAG)
                    }
                }
            )
            listPreference(
                key = HailData.DYNAMIC_SHORTCUT_ACTION,
                defaultValue = HailData.ACTION_NONE,
                onValueChange = { _, action ->
                    HShortcuts.removeAllDynamicShortcuts()
                    HShortcuts.addDynamicShortcutAction(action)
                    true
                },
                values = HailData.DYNAMIC_SHORTCUT_ACTIONS,
                entriesId = R.array.dynamic_shortcut_entries,
                titleId = R.string.dynamic_shortcut_action,
                icon = Icons.Outlined.AppShortcut
            )
            preference(
                key = "clear_dynamic_shortcuts",
                title = { Text(text = stringResource(R.string.action_clear_dynamic_shortcuts)) },
                icon = { Icon(imageVector = Icons.Outlined.CleaningServices, contentDescription = null) },
                onClick = ::resetDynamicShortcuts
            )
        }
    }

    @Composable
    private fun BackupDialog() {
        val state by backups.state.collectAsState()
        val current = state
        if (current == BackupViewModel.State.Idle) return
        val dismiss = {
            backups.dismiss()
            if (current is BackupViewModel.State.Restored) activity.recreate()
        }
        AlertDialog(
            onDismissRequest = { if (current != BackupViewModel.State.Busy) dismiss() },
            title = { Text(stringResource(R.string.backup_title)) },
            text = {
                when (current) {
                    is BackupViewModel.State.Preview -> Column {
                        Text(stringResource(R.string.backup_preview, current.data.apps.size, current.data.tags.size))
                        if (current.data.frozenStates.isNotEmpty()) {
                            if (HailData.workingMode != HailData.MODE_DEFAULT) Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.toggleable(current.applyStates, role = Role.Checkbox,
                                    onValueChange = backups::toggleStates)
                            ) {
                                Checkbox(checked = current.applyStates, onCheckedChange = null)
                                Text(stringResource(R.string.backup_apply_states, current.data.frozenStates.size))
                            } else Text(stringResource(R.string.backup_states_need_mode))
                        }
                    }
                    BackupViewModel.State.Busy -> CircularProgressIndicator()
                    BackupViewModel.State.Saved -> Text(stringResource(R.string.backup_saved))
                    is BackupViewModel.State.Restored -> Column {
                        Text(stringResource(R.string.backup_restored))
                        if (current.changed != null) Text(stringResource(R.string.unfreeze_counts_not_installed,
                            current.changed, current.unchanged, current.notInstalled, current.unavailable, current.failed))
                    }
                    else -> Text(stringResource(R.string.backup_failed))
                }
            },
            confirmButton = {
                if (current is BackupViewModel.State.Preview) TextButton(onClick = backups::confirm) {
                    Text(stringResource(R.string.backup_replace))
                } else if (current != BackupViewModel.State.Busy) TextButton(onClick = dismiss) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                if (current is BackupViewModel.State.Preview) TextButton(onClick = dismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    private fun LazyListScope.horizontalDivider() = item { HorizontalDivider() }

    private fun LazyListScope.switchPreference(
        rememberState: @Composable () -> MutableState<Boolean>,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int,
        enabled: Boolean = true,
        icon: ImageVector,
    ) = item(key = titleId, contentType = "SwitchPreference") {
        val state = rememberState()
        SwitchPreference(
            value = state.value,
            onValueChange = { if (onValueChange(state, it)) state.value = it },
            title = { Text(text = stringResource(titleId)) },
            enabled = enabled,
            icon = { Icon(imageVector = icon, contentDescription = null) })
    }

    private fun LazyListScope.switchPreference(
        key: String,
        defaultValue: Boolean,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int,
        enabled: Boolean = true,
        icon: ImageVector,
    ) = switchPreference(
        rememberState = { rememberPreferenceState(key, defaultValue) },
        onValueChange = onValueChange,
        titleId = titleId,
        enabled = enabled,
        icon = icon
    )

    private fun LazyListScope.listPreference(
        key: String,
        defaultValue: String,
        onValueChange: (MutableState<String>, String) -> Boolean = { _, _ -> true },
        values: List<String>,
        @StringRes titleId: Int,
        icon: ImageVector,
        summary: @Composable (String) -> String,
        type: ListPreferenceType = ListPreferenceType.DROPDOWN_MENU,
        valueToText: (String) -> String
    ) = item(key = key, contentType = "ListPreference") {
        val state = rememberPreferenceState(key, defaultValue)
        ListPreference(
            value = state.value,
            onValueChange = { if (onValueChange(state, it)) state.value = it },
            values = values,
            title = { Text(text = stringResource(titleId)) },
            icon = { Icon(imageVector = icon, contentDescription = null) },
            summary = { Text(text = summary(state.value)) },
            type = type,
            valueToText = { AnnotatedString(valueToText(it)) })
    }

    private fun LazyListScope.listPreference(
        key: String,
        defaultValue: String,
        onValueChange: (MutableState<String>, String) -> Boolean = { _, _ -> true },
        values: List<String>,
        @ArrayRes entriesId: Int,
        @StringRes titleId: Int,
        icon: ImageVector,
        type: ListPreferenceType = ListPreferenceType.DROPDOWN_MENU
    ) = listPreference(
        key = key,
        defaultValue = defaultValue,
        onValueChange = onValueChange,
        values = values,
        titleId = titleId,
        icon = icon,
        summary = { it.toEntry(values, entriesId) },
        type = type,
        valueToText = { it.toEntry(values, entriesId) })

    private fun String.toEntry(values: List<String>, @ArrayRes entriesId: Int): String =
        resources.getStringArray(entriesId)[values.indexOf(this)]

    private fun resetDynamicShortcuts() {
        HShortcuts.removeAllDynamicShortcuts()
        HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction)
    }

    private fun iconPackName(pack: String): String = if (pack == HailData.ACTION_NONE) getString(R.string.action_none)
    else HPackages.getApplicationInfoOrNull(pack)?.loadLabel(app.packageManager)?.toString() ?: pack

    private fun addPinShortcut() {
        PrivateDialogBuilder(requireActivity()).setTitle(R.string.action_add_pin_shortcut)
            .setItems(R.array.pin_shortcut_entries) { _, which ->
                when (which) {
                    0 -> PrivateDialogBuilder(requireActivity()).setTitle(R.string.action_freeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            val tag = HailData.tags[index].first
                            HShortcuts.addPinShortcut(
                                AppCompatResources.getDrawable(
                                    requireContext(), R.drawable.ic_round_frozen_shortcut
                                )!!,
                                HailApi.ACTION_FREEZE_TAG + tag,
                                tag,
                                HailApi.getIntentForTag(HailApi.ACTION_FREEZE_TAG, tag)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    1 -> PrivateDialogBuilder(requireActivity()).setTitle(R.string.action_unfreeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            val tag = HailData.tags[index].first
                            HShortcuts.addPinShortcut(
                                AppCompatResources.getDrawable(
                                    requireContext(), R.drawable.ic_round_unfrozen_shortcut
                                )!!,
                                HailApi.ACTION_UNFREEZE_TAG + tag,
                                tag,
                                HailApi.getIntentForTag(HailApi.ACTION_UNFREEZE_TAG, tag)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    2 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_frozen_shortcut
                        )!!,
                        HailApi.ACTION_FREEZE_ALL,
                        getString(R.string.action_freeze_all),
                        Intent(HailApi.ACTION_FREEZE_ALL)
                    )

                    3 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_unfrozen_shortcut
                        )!!,
                        HailApi.ACTION_UNFREEZE_ALL,
                        getString(R.string.action_unfreeze_all),
                        Intent(HailApi.ACTION_UNFREEZE_ALL)
                    )

                    4 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_frozen_shortcut
                        )!!,
                        HailApi.ACTION_FREEZE_NON_WHITELISTED,
                        getString(R.string.action_freeze_non_whitelisted),
                        Intent(HailApi.ACTION_FREEZE_NON_WHITELISTED)
                    )

                    5 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_outline_lock_shortcut
                        )!!, HailApi.ACTION_LOCK, getString(R.string.action_lock), Intent(HailApi.ACTION_LOCK)
                    )

                    6 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_outline_lock_shortcut
                        )!!,
                        HailApi.ACTION_LOCK_FREEZE,
                        getString(R.string.action_lock_freeze),
                        Intent(HailApi.ACTION_LOCK_FREEZE)
                    )
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    fun onWorkingModeChange(rememberState: MutableState<String>, mode: String): Boolean {
        val trace = DecisionLog.begin("settings", "select_mode", mode)
        trace?.event("mode_requested", null, "previousMode" to HailData.workingMode)
        fun decision(accepted: Boolean, reason: String): Boolean {
            trace?.event("mode_decision", null, "accepted" to accepted, "reason" to reason)
            return accepted
        }
        // Show/hide terminal menu.
        activity.invalidateOptionsMenu()
        when {
            mode.startsWith(HailData.OWNER) -> if (!HPolicy.isDeviceOwnerActive) {
                PrivateDialogBuilder(requireActivity()).setTitle(R.string.title_set_owner)
                    .setMessage(getString(R.string.msg_set_owner, HPolicy.ADB_COMMAND))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(android.R.string.copy) { _, _ -> HUI.copyText(HPolicy.ADB_COMMAND) }.show()
                    .findViewById<MaterialTextView>(android.R.id.message)?.setTextIsSelectable(true)
                return decision(false, "Device owner is inactive")
            }

            mode.startsWith(HailData.DHIZUKU) -> return runCatching {
                Dhizuku.init(app)
                when {
                    Dhizuku.isPermissionGranted() -> decision(true, "Dhizuku permission granted")
                    else -> {
                        lifecycleScope.launch {
                            val result = callbackFlow {
                                Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                                    override fun onRequestPermission(grantResult: Int) {
                                        trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                    }
                                })
                                awaitClose()
                            }.first()
                            decision(result, "Dhizuku permission response")
                            if (result) {
                                rememberState.value = mode
                                if (HTarget.O) HDhizuku.setDelegatedScopes()
                            }
                        }
                        decision(false, "Waiting for Dhizuku permission")
                    }
                }
            }.getOrElse {
                trace?.error("mode_check_failed", null, it)
                HLog.e(it)
                HUI.showToast(R.string.permission_denied)
                false
            }

            mode.startsWith(HailData.SU) -> if (!HShell.checkSU) {
                HUI.showToast(R.string.permission_denied)
                return decision(false, "Root shell unavailable")
            }

            mode == HailData.MODE_JAILBREAK_DISABLE -> if (
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    CHANGE_COMPONENT_ENABLED_STATE
                ) == PackageManager.PERMISSION_DENIED
                && !HShell.grantPermission(requireContext().packageName, CHANGE_COMPONENT_ENABLED_STATE)
            ) {
                HUI.showToast(R.string.permission_denied)
                return decision(false, "CHANGE_COMPONENT_ENABLED_STATE unavailable")
            }

            mode.startsWith(HailData.SHIZUKU) -> return runCatching {
                when {
                    Shizuku.isPreV11() -> throw IllegalStateException("unsupported shizuku version")
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> decision(true, "Shizuku permission granted")
                    Shizuku.shouldShowRequestPermissionRationale() -> {
                        HUI.showToast(R.string.permission_denied)
                        decision(false, "Shizuku permission denied")
                    }

                    else -> {
                        lifecycleScope.launch {
                            val result = callbackFlow {
                                val shizukuRequestCode = 0
                                val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                                    if (requestCode != shizukuRequestCode) return@OnRequestPermissionResultListener
                                    trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                }
                                Shizuku.addRequestPermissionResultListener(listener)
                                Shizuku.requestPermission(shizukuRequestCode)
                                awaitClose {
                                    Shizuku.removeRequestPermissionResultListener(listener)
                                }
                            }.first()
                            decision(result, "Shizuku permission response")
                            if (result) rememberState.value = mode
                        }
                        decision(false, "Waiting for Shizuku permission")
                    }
                }
            }.getOrElse {
                trace?.error("mode_check_failed", null, it)
                HLog.e(it)
                HUI.showToast(R.string.shizuku_missing)
                false
            }

            mode.startsWith(HailData.ISLAND) -> return runCatching {
                when {
                    mode == HailData.MODE_ISLAND_HIDE && HIsland.freezePermissionGranted() -> decision(true, "Island freeze permission granted")
                    mode == HailData.MODE_ISLAND_SUSPEND && HIsland.suspendPermissionGranted() -> decision(true, "Island suspend permission granted")
                    else -> {
                        lifecycleScope.launch {
                            requestPermissionLauncher.launch(
                                if (mode == HailData.MODE_ISLAND_HIDE) HIsland.PERMISSION_FREEZE_PACKAGE
                                else HIsland.PERMISSION_SUSPEND_PACKAGE
                            )
                        }
                        decision(false, "Waiting for Island permission")
                    }
                }
            }.getOrElse {
                trace?.error("mode_check_failed", null, it)
                HLog.e(it)
                HUI.showToast(R.string.permission_denied)
                false
            }.also {
                if (it) {
                    HIsland.checkOwnerApp()
                }
            }

            mode.startsWith(HailData.PRIVAPP) -> {
                val permission = if (mode == HailData.MODE_PRIVAPP_DISABLE) CHANGE_COMPONENT_ENABLED_STATE else "android.permission.FORCE_STOP_PACKAGES"
                if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                    PrivateDialogBuilder(requireContext()).setMessage(R.string.privileged_mode_permission)
                        .setPositiveButton(android.R.string.ok, null).show()
                    return decision(false, "Required Android permission denied")
                }
            }
        }

        return decision(true, "Selected mode is available")
    }

    private suspend fun onTerminalResult(exitValue: Int, msg: String?) = withContext(Dispatchers.Main) {
        if (exitValue == 0 && msg.isNullOrBlank()) return@withContext
        PrivateDialogBuilder(requireActivity()).apply {
            if (!msg.isNullOrBlank()) {
                if (exitValue != 0) {
                    setTitle(getString(R.string.operation_failed, exitValue.toString()))
                }
                setMessage(msg)
                setNeutralButton(android.R.string.copy) { _, _ -> HUI.copyText(msg) }
            } else if (exitValue != 0) {
                setMessage(getString(R.string.operation_failed, exitValue.toString()))
            }
        }.setPositiveButton(android.R.string.ok, null).show().findViewById<MaterialTextView>(android.R.id.message)
            ?.setTextIsSelectable(true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_terminal -> showTerminalDialog()
            R.id.action_remove_owner -> (requireActivity() as MainActivity).ownerRemoveDialog()
            R.id.action_help -> HUI.openLink(HailData.URL_README)
        }
        return false
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_settings, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        super.onPrepareMenu(menu)
        if (HailData.workingMode.startsWith(HailData.SU) || HailData.workingMode.startsWith(
                HailData.SHIZUKU
            )
        ) menu.findItem(R.id.action_terminal).isVisible = true
        else if (HPolicy.isDeviceOwnerActive) menu.findItem(R.id.action_remove_owner).isVisible = true
    }

    private fun showTerminalDialog() {
        val binding = DialogInputBinding.inflate(layoutInflater)
        binding.inputLayout.setHint(R.string.command)
        binding.editText.run {
            setSingleLine()
            filters = arrayOf()
        }
        PrivateDialogBuilder(requireActivity()).setTitle(R.string.action_terminal).setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val result = AppManager.execute(binding.editText.text.toString())
                    onTerminalResult(result.first, result.second)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
