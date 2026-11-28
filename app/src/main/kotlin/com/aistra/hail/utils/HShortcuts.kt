package com.aistra.hail.utils

import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.api.ApiActivity
import com.aistra.hail.ui.api.InternalActionActivity
import kotlinx.coroutines.CancellationException
import me.zhanghai.android.appiconloader.AppIconLoader
import kotlin.math.roundToInt

object HShortcuts {
    // Legacy pin broadcasts launch as the home app and use the public confirmation screen.
    private val shortcutActivity get() = if (HTarget.O) InternalActionActivity::class.java else ApiActivity::class.java

    private val iconLoader by lazy {
        AppIconLoader(
            app.resources.getDimensionPixelSize(R.dimen.app_icon_size),
            HailData.synthesizeAdaptiveIcons,
            app
        )
    }

    fun addPinShortcut(icon: Drawable, id: String, label: CharSequence, intent: Intent) {
        addPinShortcut(buildPinShortcut(getDrawableIcon(icon), id, label, intent))
    }

    fun addPinShortcut(appInfo: AppInfo, id: String, label: CharSequence, intent: Intent) {
        addPinShortcut(appPinShortcut(appInfo, id, label, intent))
    }

    fun appPinShortcut(
        info: AppInfo,
        id: String = info.packageName,
        label: CharSequence = info.name,
        intent: Intent = HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, info.packageName)
    ): ShortcutInfoCompat {
        val icon = appShortcutIcon(info.applicationInfo)
        return buildPinShortcut(icon, id, label.ifBlank { id }, intent)
    }

    private fun buildPinShortcut(icon: IconCompat, id: String, label: CharSequence, intent: Intent) =
        ShortcutInfoCompat.Builder(app, id).setIcon(icon).setShortLabel(label)
            .setIntent(Intent(intent).putExtra(HailApi.EXTRA_ORIGIN, HailApi.SHORTCUT).setClass(app, shortcutActivity)).build()

    val supportsPinning get() = ShortcutManagerCompat.isRequestPinShortcutSupported(app)

    fun pinnedIds(): Set<String> = if (HTarget.O) {
        // Read only the platform records needed for IDs; rebuilding every compat shortcut on each
        // item adds unnecessary intent/icon conversion work as a large batch grows.
        requireNotNull(app.getSystemService(ShortcutManager::class.java)) { "Shortcut service unavailable" }
            .pinnedShortcuts
            .mapTo(mutableSetOf()) { it.id }
    } else emptySet()

    fun requestPin(shortcut: ShortcutInfoCompat, callback: IntentSender? = null) =
        supportsPinning && ShortcutManagerCompat.requestPinShortcut(app, shortcut, callback)

    private fun addPinShortcut(shortcut: ShortcutInfoCompat) {
        if (!requestPin(shortcut)) HUI.showToast(
            R.string.operation_failed, app.getString(R.string.action_add_pin_shortcut)
        )
    }

    fun addDynamicShortcut(packageName: String) {
        if (HailData.biometricLogin || !HailData.recentShortcuts) return
        val applicationInfo = HPackages.getApplicationInfoOrNull(packageName)
        val shortcut =
            ShortcutInfoCompat.Builder(app, packageName.hashCode().toString()) // Make id different from pin
                .setIcon(appShortcutIcon(applicationInfo)).setShortLabel(
                    applicationInfo?.loadLabel(app.packageManager) ?: packageName
                ).setIntent(HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, packageName).putExtra(HailApi.EXTRA_ORIGIN, HailApi.SHORTCUT).setClass(app, shortcutActivity)).build()
        ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
        addDynamicShortcutAction(HailData.dynamicShortcutAction)
    }

    fun addDynamicShortcutAction(action: String) {
        if (action == HailData.ACTION_NONE) return
        val id = when (action) {
            HailData.ACTION_FREEZE_ALL -> HailApi.ACTION_FREEZE_ALL
            HailData.ACTION_FREEZE_NON_WHITELISTED -> HailApi.ACTION_FREEZE_NON_WHITELISTED
            HailData.ACTION_LOCK -> HailApi.ACTION_LOCK
            HailData.ACTION_LOCK_FREEZE -> HailApi.ACTION_LOCK_FREEZE
            else -> HailApi.ACTION_UNFREEZE_ALL
        }
        val icon = when (action) {
            HailData.ACTION_FREEZE_ALL, HailData.ACTION_FREEZE_NON_WHITELISTED -> R.drawable.ic_round_frozen_shortcut
            HailData.ACTION_LOCK, HailData.ACTION_LOCK_FREEZE -> R.drawable.ic_outline_lock_shortcut
            else -> R.drawable.ic_round_unfrozen_shortcut
        }
        val label = when (action) {
            HailData.ACTION_FREEZE_ALL -> R.string.action_freeze_all
            HailData.ACTION_FREEZE_NON_WHITELISTED -> R.string.action_freeze_non_whitelisted
            HailData.ACTION_LOCK -> R.string.action_lock
            HailData.ACTION_LOCK_FREEZE -> R.string.action_lock_freeze
            else -> R.string.action_unfreeze_all
        }
        val shortcut = ShortcutInfoCompat.Builder(app, id).setIcon(
            getDrawableIcon(
                AppCompatResources.getDrawable(
                    app, icon
                )!!
            )
        ).setShortLabel(app.getString(label)).setIntent(Intent(id).putExtra(HailApi.EXTRA_ORIGIN, HailApi.SHORTCUT).setClass(app, shortcutActivity)).build()
        ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
    }

    fun removeRecentApps() {
        val recentIds = ShortcutManagerCompat.getDynamicShortcuts(app)
            .filter { it.intent?.action == HailApi.ACTION_LAUNCH }
            .map { it.id }
        if (recentIds.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(app, recentIds)
    }

    fun removeAllDynamicShortcuts() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(app)
    }

    /**
     * Update platform records that still exist, preserving their IDs and action arguments.
     * Android restores shortcut metadata without bitmap icons. Foreground repair can regenerate
     * those icons; it cannot recreate a record already removed by the launcher or the platform.
     */
    @Synchronized
    fun refreshPinnedAppShortcuts(regenerateIcons: Boolean = false): Boolean {
        if (!HTarget.O) return true
        return try {
            // Old backups can contain disabled records without an intent. Read the native
            // nullable fields instead of forcing those records through the compat conversion.
            val pins = requireNotNull(app.getSystemService(ShortcutManager::class.java)) {
                "Shortcut service unavailable"
            }.pinnedShortcuts
            val updates = ArrayList<ShortcutInfoCompat>()
            var complete = true
            fun submit(): Boolean {
                if (updates.isEmpty()) return true
                val applied = ShortcutManagerCompat.updateShortcuts(app, updates)
                updates.clear()
                return applied
            }
            for (pin in pins) {
                if (pin.isImmutable) continue
                val request = pin.intent ?: continue
                val action = try {
                    HailApi.parse(request)
                } catch (_: IllegalArgumentException) {
                    // Unknown or malformed legacy records cannot safely be inferred from an ID.
                    continue
                }
                val pkg = action.getStringExtra(HailData.KEY_PACKAGE)
                val info = pkg?.let {
                    if (regenerateIcons) HPackages.findForUser(it, listed = { null })
                        ?.takeIf { target -> target.installed }?.info
                    else HPackages.getApplicationInfoOrNull(it)
                }
                if (pkg != null && info == null) {
                    // An app may not have finished installing during device restore. Preserve the
                    // pin and report a partial repair so first-open repair can retry next time.
                    if (regenerateIcons) complete = false
                    continue
                }
                val label = if (action.action == HailApi.ACTION_LAUNCH && info != null) {
                    info.loadLabel(app.packageManager).ifBlank { pkg ?: pin.id }
                } else pin.shortLabel?.takeIf { it.isNotBlank() } ?: pin.id
                val update = ShortcutInfoCompat.Builder(app, pin.id)
                    .setShortLabel(label)
                    .setIntent(Intent(request).putExtra(HailApi.EXTRA_ORIGIN, HailApi.SHORTCUT)
                        .setClass(app, InternalActionActivity::class.java))
                if (regenerateIcons) {
                    val icon = when (action.action) {
                        HailApi.ACTION_LAUNCH, HailApi.ACTION_FREEZE, HailApi.ACTION_UNFREEZE,
                        Intent.ACTION_SHOW_APP_INFO -> appShortcutIconOrNull(info)
                        HailApi.ACTION_UNFREEZE_TAG, HailApi.ACTION_UNFREEZE_ALL ->
                            actionShortcutIconOrNull(R.drawable.ic_round_unfrozen_shortcut)
                        HailApi.ACTION_LOCK, HailApi.ACTION_LOCK_FREEZE ->
                            actionShortcutIconOrNull(R.drawable.ic_outline_lock_shortcut)
                        else -> actionShortcutIconOrNull(R.drawable.ic_round_frozen_shortcut)
                    }
                    if (icon == null) {
                        // Keep the old icon and retry a failed decoder/resource on a later visit.
                        complete = false
                        continue
                    }
                    update.setIcon(icon)
                }
                updates.add(update.build())
                // Two 256px ARGB icons use at most 512KiB before parcel overhead. Metadata-only
                // refresh omits icons and retains the user's existing launcher image.
                if (regenerateIcons && updates.size == ICON_REFRESH_BATCH_SIZE && !submit()) return false
            }
            submit() && complete
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            HLog.e(e)
            false
        }
    }

    private fun actionShortcutIconOrNull(resource: Int): IconCompat? = loadIconOrNull {
        AppCompatResources.getDrawable(app, resource)?.let {
            IconCompat.createWithBitmap(getBitmapFromDrawable(it))
        }
    }

    private fun appShortcutIcon(info: ApplicationInfo?): IconCompat =
        appShortcutIconOrNull(info) ?: defaultShortcutIcon()

    private fun appShortcutIconOrNull(info: ApplicationInfo?): IconCompat? = loadIconOrNull {
        info?.let {
            val bitmap = IconPack.loadIcon(it.packageName) ?: iconLoader.loadIcon(it)
            IconCompat.createWithBitmap(boundedBitmap(bitmap))
        }
    }

    private fun getDrawableIcon(drawable: Drawable): IconCompat = loadIconOrNull {
        IconCompat.createWithBitmap(getBitmapFromDrawable(drawable))
    } ?: defaultShortcutIcon()

    private fun defaultShortcutIcon(): IconCompat = loadIconOrNull {
        IconCompat.createWithBitmap(getBitmapFromDrawable(app.packageManager.defaultActivityIcon))
    } ?: IconCompat.createWithResource(app, R.mipmap.ic_launcher)

    /** One broken icon must not stop all later apps in a bulk shortcut request. */
    private inline fun loadIconOrNull(load: () -> IconCompat?): IconCompat? = try {
        load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        HLog.e(e)
        null
    }

    private fun iconLimits(): Pair<Int, Int> {
        val fallback = (96 * app.resources.displayMetrics.density).roundToInt()
            .coerceIn(1, MAX_ICON_EDGE)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return fallback to fallback
        return try {
            val manager = app.getSystemService(ShortcutManager::class.java)
            val width = manager?.iconMaxWidth?.takeIf { it > 0 } ?: fallback
            val height = manager?.iconMaxHeight?.takeIf { it > 0 } ?: fallback
            width.coerceAtMost(MAX_ICON_EDGE) to height.coerceAtMost(MAX_ICON_EDGE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            HLog.e(e)
            fallback to fallback
        }
    }

    private fun boundedSize(width: Int, height: Int): Pair<Int, Int> {
        val (maxWidth, maxHeight) = iconLimits()
        // Some framework/default/vector drawables have no intrinsic size (-1).
        val sourceWidth = width.takeIf { it > 0 } ?: maxWidth
        val sourceHeight = height.takeIf { it > 0 } ?: maxHeight
        val scale = minOf(1.0, maxWidth.toDouble() / sourceWidth, maxHeight.toDouble() / sourceHeight)
        return (sourceWidth * scale).toInt().coerceAtLeast(1) to
            (sourceHeight * scale).toInt().coerceAtLeast(1)
    }

    private fun boundedBitmap(bitmap: Bitmap): Bitmap {
        val (width, height) = boundedSize(bitmap.width, bitmap.height)
        val bounded = if (width == bitmap.width && height == bitmap.height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true)
        // Wide-gamut bitmaps can use twice the bytes per pixel. Keep the parcel's pixel budget
        // predictable even when an icon pack or decoder supplies RGBA_F16/HARDWARE bitmaps.
        return if (bounded.config == Bitmap.Config.ARGB_8888) bounded
        else requireNotNull(bounded.copy(Bitmap.Config.ARGB_8888, false))
    }

    private fun getBitmapFromDrawable(drawable: Drawable): Bitmap {
        val (width, height) = boundedSize(drawable.intrinsicWidth, drawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val previousBounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
        } finally {
            drawable.bounds = previousBounds
        }
        return bitmap
    }

    // Even a launcher reporting an excessive maximum must not receive a multi-megabyte icon.
    private const val MAX_ICON_EDGE = 256
    private const val ICON_REFRESH_BATCH_SIZE = 2
}
