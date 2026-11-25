package com.aistra.hail.ui.api

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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

class ApiActivity : ComponentActivity() {
    private var actionJob: Job? = null
    private var lastRequest = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatch(intent)
    }

    private val requestSource: String get() = when (intent.getStringExtra(HailApi.EXTRA_ORIGIN)) {
        HailApi.SHORTCUT -> HailApi.SHORTCUT
        HailApi.TILE -> HailApi.TILE
        HailApi.NOTIFICATION -> HailApi.NOTIFICATION
        else -> if (intent.action == Intent.ACTION_VIEW) "hail_link" else "api"
    }

    private fun dispatch(request: Intent) = enqueue {
        setIntent(request)
        try {
            handleAction(request.action)
        } catch (e: Exception) {
            if (e !is CancellationException) DecisionLog.begin(requestSource, "dispatch")?.event("dispatch_failed", null,
                "errorType" to e.javaClass.name)
            throw e
        }
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

            Intent.ACTION_VIEW -> return handleSchema(intent.data)

            HailApi.ACTION_LAUNCH -> launchApp(requirePackage(), runCatching { requireTagId }.getOrNull())
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

    /**
     * Handle schema actions
     *
     * hail://launch?package=xxx
     * hail://freeze?package=xxx
     * hail://unfreeze?package=xxx
     * hail://freeze_tag?tag=xxx
     * hail://unfreeze_tag?tag=xxx
     * hail://freeze_all
     * hail://unfreeze_all
     * hail://freeze_non_whitelisted
     * hail://freeze_auto
     * hail://lock
     * hail://lock_freeze
     */
    private suspend fun handleSchema(uri: Uri?): Boolean {
        if (uri?.scheme != "hail") throw IllegalArgumentException("Unknown scheme:\n${uri?.scheme}")
        return handleAction(
            when (uri.host) {
                "launch" -> HailApi.ACTION_LAUNCH
                "freeze" -> HailApi.ACTION_FREEZE
                "unfreeze" -> HailApi.ACTION_UNFREEZE
                "freeze_tag" -> HailApi.ACTION_FREEZE_TAG
                "unfreeze_tag" -> HailApi.ACTION_UNFREEZE_TAG
                "freeze_all" -> HailApi.ACTION_FREEZE_ALL
                "unfreeze_all" -> HailApi.ACTION_UNFREEZE_ALL
                "freeze_non_whitelisted" -> HailApi.ACTION_FREEZE_NON_WHITELISTED
                "freeze_auto" -> HailApi.ACTION_FREEZE_AUTO
                "lock" -> HailApi.ACTION_LOCK
                "lock_freeze" -> HailApi.ACTION_LOCK_FREEZE
                else -> throw IllegalArgumentException("Unknown host:\n${uri.host}")
            }
        )
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

    private fun requirePackage(): String {
        val pkg = intent.run {
            if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_PACKAGE)
            else getStringExtra(
                if (action != Intent.ACTION_SHOW_APP_INFO) HailData.KEY_PACKAGE
                else if (HTarget.N) Intent.EXTRA_PACKAGE_NAME
                else "android.intent.extra.PACKAGE_NAME"
            )
        } ?: throw IllegalArgumentException("Package must not be null")
        return pkg
    }

    private val requireTagId: Int
        get() = intent.run {
            if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_TAG)
            else getStringExtra(HailData.KEY_TAG)
        }?.let {
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
        if (pkg in result.missing) throw NameNotFoundException(getString(R.string.app_not_installed))
        if (!result.reachedTarget(pkg)) throw IllegalStateException(AppManager.resultMessage(result, frozen))
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
