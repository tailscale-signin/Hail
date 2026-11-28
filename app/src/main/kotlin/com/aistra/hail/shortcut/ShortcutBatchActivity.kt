package com.aistra.hail.shortcut

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.DeviceUnlock
import com.aistra.hail.ui.protectContent
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.HLog
import com.aistra.hail.widget.AllAppsWidgetCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A regular dark screen; progress and errors remain here instead of opening status popups. */
class ShortcutBatchActivity : FragmentActivity() {
    private val unlock = DeviceUnlock(this)
    private val authenticated = mutableStateOf(false)
    private val assistantEnabled = mutableStateOf(false)
    private val assistantConnected = mutableStateOf(false)
    private val preparing = mutableStateOf(false)
    private var authenticating = false
    private var startWhenReady = false
    private var stopped = false
    private var authorizedBatchId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        window.protectContent()
        super.onCreate(savedInstanceState)
        startWhenReady = savedInstanceState?.getBoolean(AUTO_START)
            ?: intent.getBooleanExtra(AUTO_START, false)
        setContent { AppTheme { BatchScreen() } }
        lifecycleScope.launch {
            ShortcutBatchController.initialize(this@ShortcutBatchActivity)
            maybeStartRequestedBatch()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    assistantEnabled.value = ShortcutPinAccessibilityService.isEnabled(this@ShortcutBatchActivity)
                    assistantConnected.value = ShortcutPinAccessibilityService.isConnected()
                    maybeStartRequestedBatch()
                    delay(500)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val current = ShortcutBatchController.state.value
        if (HailData.biometricLogin && stopped && (!current.active || current.batchId != authorizedBatchId)) {
            authenticated.value = false
        }
        stopped = false
        authenticate()
    }

    private fun authenticate() {
        if (!HailData.biometricLogin) {
            authenticated.value = true
            maybeStartRequestedBatch()
        } else if (!authenticated.value && !authenticating) {
            authenticating = true
            unlock.request { allowed ->
                authenticating = false
                authenticated.value = allowed
                if (allowed) maybeStartRequestedBatch() else finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startWhenReady = intent.getBooleanExtra(AUTO_START, false)
        // Reopening through the notification is a new access to the protected progress screen.
        if (HailData.biometricLogin) authenticated.value = false
        authenticate()
    }

    override fun onStop() {
        // Keep one authorization across the batch's launcher prompts, not across unrelated visits.
        val current = ShortcutBatchController.state.value
        if (HailData.biometricLogin) {
            if (!current.active) authenticated.value = false
            else if (authenticated.value) authorizedBatchId = current.batchId
        }
        stopped = true
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(AUTO_START, startWhenReady)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ShortcutBatchController.activityFocusChanged(hasFocus)
    }

    private fun maybeStartRequestedBatch() {
        val current = ShortcutBatchController.state.value
        if (!startWhenReady || !authenticated.value || !current.initialized || preparing.value) return
        if (current.active || current.canResume) {
            // A recreated/previous batch is always a deliberate Resume, never an implicit restart.
            startWhenReady = false
            return
        }
        if (ShortcutPinAccessibilityService.isEnabled(this) && ShortcutPinAccessibilityService.isConnected()) {
            startWhenReady = false
            startBatch(automatic = true)
        }
    }

    private fun startBatch(automatic: Boolean) {
        if (!authenticated.value || preparing.value) return
        startWhenReady = false
        preparing.value = true
        lifecycleScope.launch {
            try {
                val packages = withContext(Dispatchers.IO) { AllAppsWidgetCatalog.load(this@ShortcutBatchActivity).map { it.packageName } }
                ShortcutBatchController.start(this@ShortcutBatchActivity, packages, automatic)
            } catch (e: Exception) {
                HLog.e(e)
                ShortcutBatchController.showMessage(getString(R.string.individual_batch_request_failed))
            } finally {
                preparing.value = false
            }
        }
    }

    @Composable
    private fun BatchScreen() {
        val current by ShortcutBatchController.state.collectAsState()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.individual_batch_title), style = MaterialTheme.typography.headlineSmall)
                if (!authenticated.value || !current.initialized || preparing.value) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.individual_batch_preparing))
                    return@Column
                }
                Text(stringResource(R.string.individual_batch_description))
                if (current.batchId != null) {
                    Text(stringResource(R.string.individual_batch_counts, current.completed, current.total),
                        style = MaterialTheme.typography.titleMedium)
                    if (current.total > 0) LinearProgressIndicator(
                        progress = { current.completed.toFloat() / current.total }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.individual_batch_results, current.added.size, current.existing.size, current.skipped.size))
                    current.awaiting?.let { Text(it.label, style = MaterialTheme.typography.titleMedium) }
                }
                current.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    current.active -> {
                        if (!current.automatic) Text(stringResource(R.string.individual_batch_approve_manually))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { ShortcutBatchController.pause(getString(R.string.individual_batch_paused)) }) {
                                Text(stringResource(R.string.individual_batch_pause))
                            }
                            TextButton(onClick = ShortcutBatchController::cancel) { Text(stringResource(R.string.individual_batch_cancel)) }
                        }
                    }
                    current.canResume -> {
                        Text(stringResource(R.string.individual_batch_paused), style = MaterialTheme.typography.titleMedium)
                        if (current.automatic && !assistantEnabled.value) AssistantSetup()
                        Button(onClick = { ShortcutBatchController.resume(this@ShortcutBatchActivity) },
                            enabled = !current.automatic || assistantConnected.value) {
                            Text(stringResource(R.string.individual_batch_resume))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { ShortcutBatchController.retry(this@ShortcutBatchActivity) },
                                enabled = !current.automatic || assistantConnected.value) {
                                Text(stringResource(R.string.individual_batch_retry))
                            }
                            TextButton(onClick = { ShortcutBatchController.skip(this@ShortcutBatchActivity) },
                                enabled = !current.automatic || assistantConnected.value) {
                                Text(stringResource(R.string.individual_batch_skip))
                            }
                        }
                        if (current.automatic) OutlinedButton(onClick = {
                            ShortcutBatchController.continueManually(this@ShortcutBatchActivity)
                        }) { Text(stringResource(R.string.individual_batch_continue_manual)) }
                        TextButton(onClick = ShortcutBatchController::cancel) { Text(stringResource(R.string.individual_batch_cancel)) }
                    }
                    else -> {
                        if (current.status == BatchStatus.COMPLETED) Text(stringResource(
                            if (current.total == 0) R.string.individual_batch_empty else R.string.individual_batch_finished),
                            style = MaterialTheme.typography.titleMedium)
                        if (!assistantEnabled.value) AssistantSetup()
                        else if (!assistantConnected.value) Text(stringResource(R.string.individual_batch_assistant_connecting))
                        Button(onClick = { startBatch(automatic = true) }, enabled = assistantConnected.value) {
                            Text(stringResource(R.string.individual_batch_start))
                        }
                        Text(stringResource(R.string.individual_batch_manual_description))
                        OutlinedButton(onClick = { startBatch(automatic = false) }) {
                            Text(stringResource(R.string.individual_batch_start_manual))
                        }
                    }
                }
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.individual_batch_close)) }
            }
        }
    }

    @Composable
    private fun AssistantSetup() {
        Text(stringResource(R.string.individual_batch_assistant_explanation))
        OutlinedButton(onClick = { ShortcutPinAccessibilityService.openSettings(this) }) {
            Text(stringResource(R.string.individual_batch_enable_assistant))
        }
    }

    companion object {
        private const val AUTO_START = "start_new_shortcut_batch"
        fun intent(context: Context) = Intent(context, ShortcutBatchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        fun launch(context: Context) {
            context.startActivity(intent(context).putExtra(AUTO_START, true).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
