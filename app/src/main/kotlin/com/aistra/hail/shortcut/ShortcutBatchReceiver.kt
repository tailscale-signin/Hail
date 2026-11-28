package com.aistra.hail.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistra.hail.BuildConfig
import com.aistra.hail.utils.HLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Explicit immutable PendingIntents can deliver results even after Hail's process was recreated. */
class ShortcutBatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PINNED && intent.action != CANCEL) return
        val completion = goAsync()
        scope.launch {
            try {
                ShortcutBatchController.initialize(context)
                if (intent.action == CANCEL) {
                    if (intent.data?.schemeSpecificPart != ShortcutBatchController.state.value.batchId) return@launch
                    ShortcutBatchController.cancel()
                    ShortcutBatchController.awaitSaved()
                } else {
                    val uri = intent.data ?: return@launch
                    if (uri.scheme != "hail-shortcut-result" || uri.pathSegments.size != 2) return@launch
                    val batchId = uri.authority ?: return@launch
                    ShortcutBatchController.callbackReceived(batchId, uri.pathSegments[0], uri.pathSegments[1])
                }
            } catch (e: Exception) {
                HLog.e(e)
            } finally {
                completion.finish()
            }
        }
    }

    companion object {
        internal const val PINNED = "${BuildConfig.APPLICATION_ID}.shortcut.PINNED"
        internal const val CANCEL = "${BuildConfig.APPLICATION_ID}.shortcut.CANCEL"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
