package com.aistra.hail.ui.api

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import androidx.fragment.app.FragmentActivity
import com.aistra.hail.ui.DeviceUnlock
import com.aistra.hail.ui.PrivateDialogBuilder
import com.aistra.hail.ui.protectContent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.aistra.hail.work.HWork.setAutoFreeze

// Only Android can dispatch to this unexported component on behalf of Hail.
class InternalActionActivity : ApiActivity()

open class ApiActivity : FragmentActivity() {
    private val unlock = DeviceUnlock(this)
    private var requestSource = "api"
    private var actionJob: Job? = null
    private var lastRequest = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        window.protectContent()
        super.onCreate(savedInstanceState)
        dispatch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatch(intent)
    }

    private suspend fun unlocked() = !HailData.biometricLogin || suspendCancellableCoroutine { result ->
        unlock.request { if (result.isActive) result.resume(it) }
    }

    private fun dispatch(request: Intent) = enqueue {
        val internal = this is InternalActionActivity
        requestSource = when {
            !internal -> if (request.action == Intent.ACTION_VIEW) "hail_link" else "api"
            else -> request.getStringExtra(HailApi.EXTRA_ORIGIN) ?: "hail"
        }
        try {
            setContent { }
            val action = HailApi.parse(request)
            setIntent(action)
            if (!unlocked()) return@enqueue true
            if (!internal && action.action != Intent.ACTION_SHOW_APP_INFO && !confirm(action)) return@enqueue true
            handleAction(action.action)
        } catch (e: Exception) {
            if (e !is CancellationException) DecisionLog.begin(requestSource, "dispatch")?.event("dispatch_failed", null,
                "errorType" to e.javaClass.name)
            throw e
        }
    }

    private suspend fun confirm(request: Intent): Boolean = suspendCancellableCoroutine { result ->
        val description = buildList {
            add(getString(HailApi.actionLabel(request.action)))
            request.getStringExtra(HailData.KEY_PACKAGE)?.let { add(it) }
            request.getStringExtra(HailData.KEY_TAG)?.let {
                add(getString(if (request.action == HailApi.ACTION_LAUNCH) R.string.also_unfreeze_tag else R.string.request_tag, it))
            }
        }.joinToString("\n")
        fun answer(allow: Boolean) { if (result.isActive) result.resume(allow) }
        val dialog = PrivateDialogBuilder(this).setTitle(R.string.external_action)
            .setMessage(description).setPositiveButton(R.string.action_continue) { _, _ -> answer(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> answer(false) }.setOnCancelListener { answer(false) }.show()
        result.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
    }

    private fun enqueue(action: suspend () -> Boolean) {
        val previous = actionJob
        val number = ++lastRequest
        actionJob = lifecycleScope.launch {
            previous?.join()
            try {
                if (action() && number == lastRequest) finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (number == lastRequest) setErrorDialog(e)
            }
        }
    }

    private suspend fun handleAction(action: String?): Boolean {
        when (action) {
            Intent.ACTION_SHOW_APP_INFO -> {
                val pkg = requirePackage()
                if (withContext(Dispatchers.IO) { HPackages.getApplicationInfoOrNull(pkg) } == null)
                    throw NameNotFoundException(getString(R.string.app_not_installed))
                setContent { AppTheme { RedirectBottomSheet(pkg) } }
                return false
            }

            HailApi.ACTION_LAUNCH -> launchApp(requirePackage(), intent.getStringExtra(HailData.KEY_TAG)?.let { requireTagId })
            HailApi.ACTION_FREEZE -> setAppFrozen(requirePackage(), true)
            HailApi.ACTION_UNFREEZE -> setAppFrozen(requirePackage(), false)
            HailApi.ACTION_FREEZE_TAG, HailApi.ACTION_UNFREEZE_TAG -> {
                val id = requireTagId
                val freeze = action == HailApi.ACTION_FREEZE_TAG
                setListFrozen(freeze, tagId = id, skipWhitelisted = freeze)
            }

            HailApi.ACTION_FREEZE_ALL -> setListFrozen(true)
            HailApi.ACTION_UNFREEZE_ALL -> setListFrozen(false)
            HailApi.ACTION_FREEZE_NON_WHITELISTED -> setListFrozen(true, skipWhitelisted = true)
            HailApi.ACTION_FREEZE_AUTO -> setAutoFreeze(false, requestSource)
            HailApi.ACTION_LOCK -> lockScreen(false)
            HailApi.ACTION_LOCK_FREEZE -> lockScreen(true)
            else -> throw IllegalArgumentException("Unknown action:\n$action")
        }
        return true
    }

    private fun setErrorDialog(t: Throwable) = setContent { AppTheme { ErrorDialog(t) } }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RedirectBottomSheet(pkg: String) = ModalBottomSheet(
        onDismissRequest = ::finish, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column {
            Text(
                text = HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(packageManager)?.toString() ?: pkg,
                modifier = Modifier.padding(
                    horizontal = dimensionResource(R.dimen.padding_medium),
                    vertical = dimensionResource(R.dimen.padding_small)
                ),
                style = MaterialTheme.typography.headlineSmall
            )
            ClickableItem(
                icon = Icons.AutoMirrored.Outlined.Launch, title = R.string.action_launch
            ) { launchApp(pkg) }
            ClickableItem(
                icon = Icons.Rounded.AcUnit, title = R.string.action_freeze
            ) {
                if (!HailData.isChecked(pkg)) HailData.addCheckedApp(pkg)
                setAppFrozen(pkg, true)
            }
            ClickableItem(
                icon = Icons.Rounded.BrightnessLow, title = R.string.action_unfreeze
            ) { setAppFrozen(pkg, false) }
        }
    }

    @Composable
    private fun ClickableItem(icon: ImageVector, @StringRes title: Int, onClick: suspend () -> Unit) = Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = {
            enqueue { onClick(); true }
        }), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium))
        )
        Text(text = stringResource(title), style = MaterialTheme.typography.bodyLarge)
    }

    @Composable
    private fun ErrorDialog(t: Throwable) = AlertDialog(
        text = { Text(text = t.message ?: t.stackTraceToString()) },
        onDismissRequest = ::finish,
        confirmButton = {
            TextButton(onClick = ::finish) {
                Text(text = stringResource(android.R.string.ok))
            }
        })

    private fun requirePackage(): String = requireNotNull(intent.getStringExtra(HailData.KEY_PACKAGE))

    private val requireTagId: Int
        get() = intent.getStringExtra(HailData.KEY_TAG)?.let {
            HailData.tags.find { tag -> tag.first == it }?.second
                ?: throw IllegalStateException("Tag unavailable:\n$it")
        } ?: throw IllegalArgumentException("Tag must not be null")

    private suspend fun launchApp(pkg: String, tagId: Int? = null) {
        val mode = HailData.workingMode
        val trace = DecisionLog.begin(requestSource, "launch", mode, pkg)
        if (tagId != null) setListFrozen(false, tagId = tagId, trace = trace)
        val target = AppManager.prepareLaunch(pkg, mode, trace) ?: throw ActivityNotFoundException(getString(R.string.activity_not_found))
        try {
            startActivity(target)
            trace?.event("activity_started", pkg)
        } catch (e: RuntimeException) {
            trace?.error("launch_failed", pkg, e)
            throw e
        }
        app.recordLaunch(pkg)
    }

    private suspend fun setAppFrozen(pkg: String, frozen: Boolean) {
        val trace = DecisionLog.change(requestSource, frozen, pkg = pkg)
        if (pkg == packageName) {
            trace?.skipped(pkg, "API cannot change Hail itself")
            throw SecurityException(getString(R.string.permission_denied))
        }
        if (frozen && !HailData.isChecked(pkg)) {
            trace?.skipped(pkg, "API freeze requires a package in the Hail list")
            throw SecurityException("Package not checked")
        }
        val result = AppManager.changeFrozen(listOf(pkg), frozen, trace = trace)
        if (pkg in result.missing || (!frozen && pkg in result.notInstalled)) throw NameNotFoundException(getString(R.string.app_not_installed))
        if (!result.reachedTarget(pkg, frozen)) throw IllegalStateException(AppManager.resultMessage(result, frozen))
        HUI.showToast(AppManager.resultMessage(result, frozen))
    }

    private suspend fun setListFrozen(
        frozen: Boolean, tagId: Int? = null, skipWhitelisted: Boolean = false,
        trace: DecisionTrace? = DecisionLog.change(requestSource, frozen)
    ) {
        val packages = HailData.checkedList.mapNotNull {
            val reason = when {
                tagId != null && tagId !in it.tagIdList -> "Outside the requested tag"
                skipWhitelisted && it.whitelisted -> "Whitelisted for this action"
                else -> null
            }
            if (reason == null) it.packageName else {
                trace?.skipped(it.packageName, reason)
                null
            }
        }
        val result = AppManager.changeFrozen(packages, frozen, trace = trace)
        val message = AppManager.resultMessage(result, frozen)
        if (result.failed.isNotEmpty() && result.changed.isEmpty()) throw IllegalStateException(message)
        HUI.showToast(message)
    }

    private suspend fun lockScreen(freezeAll: Boolean) {
        if (freezeAll) setListFrozen(true)
        if (!withContext(Dispatchers.IO) { AppManager.lockScreen }) throw IllegalStateException(getString(R.string.permission_denied))
    }
}
