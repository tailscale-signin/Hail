package com.aistra.hail.ui.settings

import android.app.Dialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HLog
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.utils.HTarget
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class BulkShortcutDialog : DialogFragment() {
    companion object {
        const val TAG = "bulk_app_shortcuts"
        private const val CALLBACK_SCHEME = "hail-pin"
    }

    private lateinit var batch: MutableStateFlow<ShortcutBatch>
    private val focused = MutableStateFlow(false)
    private val onFocus = ViewTreeObserver.OnWindowFocusChangeListener { focused.value = it }
    private val callbackAction get() = "${requireContext().packageName}.SHORTCUT_PIN_RESULT"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            batch.update { it.confirm(intent.data?.schemeSpecificPart) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restored = savedInstanceState?.let { BundleCompat.getSerializable(it, TAG, ShortcutBatch::class.java) }
        batch = MutableStateFlow(restored ?: ShortcutBatch.from(HailData.checkedList.map { it.packageName }))
        val filter = IntentFilter(callbackAction).apply { addDataScheme(CALLBACK_SCHEME) }
        ContextCompat.registerReceiver(requireContext(), receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(batch, focused) { item, focus -> item to focus }.collectLatest { (item, focus) ->
                    val supported = HShortcuts.supportsPinning
                    render(item, supported)
                    val pkg = item.current
                    if (focus && supported && pkg != null && !item.failed) requestCurrent(item, pkg)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.add_all_app_shortcuts)
            .setMessage(" ").setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).setNeutralButton(R.string.action_skip, null).create()

    override fun onStart() {
        super.onStart()
        val root = requireDialog().window!!.decorView
        root.viewTreeObserver.addOnWindowFocusChangeListener(onFocus)
        focused.value = root.hasWindowFocus()
        render(batch.value, HShortcuts.supportsPinning)
    }

    override fun onStop() {
        dialog?.window?.decorView?.viewTreeObserver?.removeOnWindowFocusChangeListener(onFocus)
        focused.value = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(TAG, batch.value)
    }

    override fun onDestroy() {
        requireContext().unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun stillCurrent(item: ShortcutBatch) = batch.value == item && isResumed &&
            dialog?.window?.decorView?.hasWindowFocus() == true

    private suspend fun requestCurrent(item: ShortcutBatch, pkg: String) {
        var attempt = item
        try {
            val reconciled = withContext(Dispatchers.IO) { item.reconcile(HShortcuts.pinnedIds()) }
            if (!stillCurrent(item)) return
            if (reconciled != item) {
                batch.value = reconciled
                return
            }
            if (item.requestId != null) return
            val shortcut = withContext(Dispatchers.IO) { HShortcuts.appPinShortcut(AppInfo(pkg)) }
            if (!stillCurrent(item)) return
            val id = UUID.randomUUID().toString()
            val context = requireContext()
            val intent = Intent(callbackAction, Uri.fromParts(CALLBACK_SCHEME, id, null)).setPackage(context.packageName)
            val callback = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            attempt = item.copy(requestId = id)
            batch.value = attempt
            if (!HShortcuts.requestPin(shortcut, callback.intentSender)) {
                callback.cancel()
                batch.value = item.copy(failed = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            HLog.e(e)
            batch.compareAndSet(attempt, item.copy(failed = true))
        }
    }

    private fun render(item: ShortcutBatch, supported: Boolean) {
        val alert = dialog as? AlertDialog ?: return
        val active = supported && item.current != null
        val recover = active && (item.requestId != null || item.failed)
        alert.setMessage(message(item, supported))
        alert.getButton(DialogInterface.BUTTON_POSITIVE).apply {
            isVisible = !active || recover
            setText(if (active) R.string.shortcut_batch_retry else android.R.string.ok)
            setOnClickListener { if (active) batch.compareAndSet(item, item.retry()) else dismiss() }
        }
        alert.getButton(DialogInterface.BUTTON_NEUTRAL).apply {
            isVisible = recover
            setOnClickListener { batch.compareAndSet(item, item.skip()) }
        }
        alert.getButton(DialogInterface.BUTTON_NEGATIVE).isVisible = active
    }

    private fun message(item: ShortcutBatch, supported: Boolean): String {
        if (item.packages.isEmpty()) return getString(R.string.shortcut_batch_empty)
        if (!supported) return getString(R.string.shortcut_batch_unsupported)
        val pkg = item.current ?: return if (HTarget.O) {
            getString(R.string.shortcut_batch_result, item.added, item.existing, item.skipped)
        } else getString(R.string.shortcut_batch_legacy_result, item.added, item.skipped)
        val label = AppInfo(pkg).name.ifBlank { pkg }
        val progress = getString(R.string.shortcut_batch_progress, label, item.position + 1, item.packages.size)
        val instruction = when {
            item.failed -> R.string.shortcut_batch_failed
            HTarget.O -> R.string.shortcut_batch_prompt
            else -> R.string.shortcut_batch_legacy
        }
        return progress + "\n\n" + getString(instruction)
    }
}
