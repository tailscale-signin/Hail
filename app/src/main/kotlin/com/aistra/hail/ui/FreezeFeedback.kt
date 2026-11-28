package com.aistra.hail.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.FreezeResult
import com.google.android.material.snackbar.Snackbar
import java.lang.ref.WeakReference

/** Brief completion feedback for explicit freeze actions, never for routine background work. */
object FreezeFeedback {
    private var overlay: WeakReference<Snackbar>? = null
    private var toast: Toast? = null

    @MainThread
    fun show(result: FreezeResult, anchor: View? = null) {
        // Retained packages can have their stored setting changed without being installed.
        val count = result.changed.count { it !in result.retainedSettings }
        val message = app.resources.getQuantityString(R.plurals.apps_frozen_count, count, count)
        overlay?.get()?.dismiss()
        toast?.cancel()
        if (anchor == null) {
            // Launcher/widget actions finish their transparent Activity immediately. A short
            // system text overlay survives that finish without a new window permission.
            toast = Toast.makeText(app, message, Toast.LENGTH_SHORT).also { it.show() }
            return
        }
        if (!anchor.isAttachedToWindow || !anchor.isShown) return
        val snackbar = Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT)
            .setAnimationMode(Snackbar.ANIMATION_MODE_FADE)
            .setBackgroundTint(ContextCompat.getColor(anchor.context, R.color.md_theme_surfaceContainerHighest))
            .setTextColor(ContextCompat.getColor(anchor.context, R.color.md_theme_onSurface))
        snackbar.view.apply {
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.md_theme_surfaceContainerHighest))
                cornerRadius = resources.getDimension(R.dimen.padding_large)
            }
            layoutParams = layoutParams.apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                when (this) {
                    is CoordinatorLayout.LayoutParams -> gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    is FrameLayout.LayoutParams -> gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
            }
            findViewById<TextView>(com.google.android.material.R.id.snackbar_text).gravity = Gravity.CENTER
        }
        overlay = WeakReference(snackbar)
        snackbar.show()
    }
}
