package com.aistra.hail.ui

import android.content.Context
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.aistra.hail.utils.HTarget
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Window.protectContent() {
    addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (HTarget.S) setHideOverlayWindows(true)
}

class PrivateDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {
    override fun create(): AlertDialog = super.create().also { it.window?.protectContent() }
}
