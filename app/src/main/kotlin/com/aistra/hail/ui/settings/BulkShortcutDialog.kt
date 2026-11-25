package com.aistra.hail.ui.settings

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HLog
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.utils.HTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun BulkShortcutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val action = "${context.packageName}.SHORTCUT_PIN_RESULT"
    val scheme = "hail-pin"
    val supported = remember { HShortcuts.supportsPinning }
    var batch by rememberSaveable { mutableStateOf(ShortcutBatch.from(HailData.checkedList.map { it.packageName })) }
    val item = batch
    val pkg = item.current
    val active = supported && pkg != null

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                batch = batch.confirm(intent.data?.schemeSpecificPart)
            }
        }
        val filter = IntentFilter(action).apply { addDataScheme(scheme) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_all_app_shortcuts)) },
        text = {
            // Use the dialog window. The activity window also loses focus to this dialog.
            val window = LocalWindowInfo.current
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val state by lifecycle.currentStateAsState()
            LaunchedEffect(item, window.isWindowFocused, state) {
                if (!active || item.failed || !window.isWindowFocused || state != Lifecycle.State.RESUMED) {
                    return@LaunchedEffect
                }
                try {
                    val reconciled = withContext(Dispatchers.IO) { item.reconcile(HShortcuts.pinnedIds()) }
                    if (batch != item) return@LaunchedEffect
                    if (reconciled != item) {
                        batch = reconciled
                        return@LaunchedEffect
                    }
                    if (item.requestId != null) return@LaunchedEffect
                    val shortcut = withContext(Dispatchers.IO) { HShortcuts.appPinShortcut(AppInfo(checkNotNull(pkg))) }
                    if (batch != item || !window.isWindowFocused || lifecycle.currentState != Lifecycle.State.RESUMED) {
                        return@LaunchedEffect
                    }
                    val id = UUID.randomUUID().toString()
                    val intent = Intent(action, Uri.fromParts(scheme, id, null)).setPackage(context.packageName)
                    val callback = PendingIntent.getBroadcast(
                        context, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                    batch = item.copy(requestId = id)
                    if (!HShortcuts.requestPin(shortcut, callback.intentSender)) {
                        callback.cancel()
                        batch = item.copy(failed = true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    HLog.e(e)
                    batch = item.copy(failed = true)
                }
            }

            val message = when {
                item.packages.isEmpty() -> stringResource(R.string.shortcut_batch_empty)
                !supported -> stringResource(R.string.shortcut_batch_unsupported)
                pkg != null -> {
                    val label = remember(pkg) { AppInfo(pkg).name.toString().ifBlank { pkg } }
                    val progress = stringResource(R.string.shortcut_batch_progress, label, item.position + 1, item.packages.size)
                    val instruction = when {
                        item.failed -> R.string.shortcut_batch_failed
                        HTarget.O -> R.string.shortcut_batch_prompt
                        else -> R.string.shortcut_batch_legacy
                    }
                    progress + "\n\n" + stringResource(instruction)
                }
                HTarget.O -> stringResource(R.string.shortcut_batch_result, item.added, item.existing, item.skipped)
                else -> stringResource(R.string.shortcut_batch_legacy_result, item.added, item.skipped)
            }
            Text(message)
        },
        confirmButton = {
            if (!active) TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            else if (item.requestId != null || item.failed) Row {
                TextButton(onClick = { batch = batch.skip() }) { Text(stringResource(R.string.action_skip)) }
                TextButton(onClick = { batch = batch.retry() }) { Text(stringResource(R.string.shortcut_batch_retry)) }
            }
        },
        dismissButton = {
            if (active) TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
