package com.aistra.hail.app

import android.content.Intent
import com.aistra.hail.BuildConfig
import com.aistra.hail.R

object HailApi {
    const val EXTRA_ORIGIN = "com.aistra.hail.action_origin"
    const val SHORTCUT = "launcher_shortcut"
    const val TILE = "quick_settings_tile"
    const val NOTIFICATION = "notification"

    /** @since 0.5.0 */
    const val ACTION_LAUNCH = "${BuildConfig.APPLICATION_ID}.action.LAUNCH"

    /** @since 0.5.0 */
    const val ACTION_FREEZE = "${BuildConfig.APPLICATION_ID}.action.FREEZE"

    /** @since 0.5.0 */
    const val ACTION_UNFREEZE = "${BuildConfig.APPLICATION_ID}.action.UNFREEZE"

    /** @since 1.1.0 */
    const val ACTION_FREEZE_TAG = "${BuildConfig.APPLICATION_ID}.action.FREEZE_TAG"

    /** @since 1.1.0 */
    const val ACTION_UNFREEZE_TAG = "${BuildConfig.APPLICATION_ID}.action.UNFREEZE_TAG"

    /** @since 0.5.0 */
    const val ACTION_FREEZE_ALL = "${BuildConfig.APPLICATION_ID}.action.FREEZE_ALL"

    /** @since 0.5.0 */
    const val ACTION_UNFREEZE_ALL = "${BuildConfig.APPLICATION_ID}.action.UNFREEZE_ALL"

    /** @since 1.0.0 */
    const val ACTION_FREEZE_NON_WHITELISTED =
        "${BuildConfig.APPLICATION_ID}.action.FREEZE_NON_WHITELISTED"

    /** @since 1.3.0 */
    const val ACTION_FREEZE_AUTO = "${BuildConfig.APPLICATION_ID}.action.FREEZE_AUTO"

    /** @since 0.6.0 */
    const val ACTION_LOCK = "${BuildConfig.APPLICATION_ID}.action.LOCK"

    /** @since 0.6.0 */
    const val ACTION_LOCK_FREEZE = "${BuildConfig.APPLICATION_ID}.action.LOCK_FREEZE"

    private val linkActions = mapOf(
        "launch" to ACTION_LAUNCH, "freeze" to ACTION_FREEZE, "unfreeze" to ACTION_UNFREEZE,
        "freeze_tag" to ACTION_FREEZE_TAG, "unfreeze_tag" to ACTION_UNFREEZE_TAG,
        "freeze_all" to ACTION_FREEZE_ALL, "unfreeze_all" to ACTION_UNFREEZE_ALL,
        "freeze_non_whitelisted" to ACTION_FREEZE_NON_WHITELISTED, "freeze_auto" to ACTION_FREEZE_AUTO,
        "lock" to ACTION_LOCK, "lock_freeze" to ACTION_LOCK_FREEZE
    )
    private val packageNamePattern = Regex("[A-Za-z0-9_.]+")

    fun actionLabel(action: String?) = when (action) {
        ACTION_LAUNCH -> R.string.action_launch
        ACTION_FREEZE -> R.string.action_freeze
        ACTION_UNFREEZE -> R.string.action_unfreeze
        ACTION_FREEZE_TAG -> R.string.action_freeze_tag
        ACTION_UNFREEZE_TAG -> R.string.action_unfreeze_tag
        ACTION_FREEZE_ALL -> R.string.action_freeze_all
        ACTION_UNFREEZE_ALL -> R.string.action_unfreeze_all
        ACTION_FREEZE_NON_WHITELISTED -> R.string.action_freeze_non_whitelisted
        ACTION_FREEZE_AUTO -> R.string.auto_freeze
        ACTION_LOCK -> R.string.action_lock
        ACTION_LOCK_FREEZE -> R.string.action_lock_freeze
        Intent.ACTION_SHOW_APP_INFO -> R.string.action_details
        else -> throw IllegalArgumentException("Unknown Hail action")
    }

    /** The confirmation and the privileged operation share this immutable request snapshot. */
    fun parse(request: Intent): Intent {
        val link = request.action == Intent.ACTION_VIEW
        val action = if (link) {
            val uri = requireNotNull(request.data)
            require(uri.isHierarchical && uri.scheme == "hail")
            requireNotNull(linkActions[uri.host])
        } else request.action
        actionLabel(action)
        fun argument(name: String) = if (link) request.data?.getQueryParameter(name) else request.getStringExtra(name)
        return Intent(action).apply {
            if (action == ACTION_LAUNCH || action == ACTION_FREEZE || action == ACTION_UNFREEZE || action == Intent.ACTION_SHOW_APP_INFO) {
                val pkg = if (action == Intent.ACTION_SHOW_APP_INFO) request.getStringExtra("android.intent.extra.PACKAGE_NAME")
                    else argument(HailData.KEY_PACKAGE)
                require(!pkg.isNullOrEmpty() && pkg.length <= 255 && packageNamePattern.matches(pkg)) { "Invalid package name" }
                putExtra(HailData.KEY_PACKAGE, pkg)
            }
            if (action == ACTION_LAUNCH || action == ACTION_FREEZE_TAG || action == ACTION_UNFREEZE_TAG) {
                val tag = argument(HailData.KEY_TAG)
                if (action != ACTION_LAUNCH) requireNotNull(tag) { "Missing tag" }
                if (tag != null) putExtra(HailData.KEY_TAG, tag)
            }
        }
    }

    fun getIntentForPackage(action: String, packageName: String) =
        Intent(action).putExtra(HailData.KEY_PACKAGE, packageName)

    fun Intent.addTag(tag: String) = putExtra(HailData.KEY_TAG, tag)

    fun getIntentForTag(action: String, tag: String) = Intent(action).addTag(tag)
}