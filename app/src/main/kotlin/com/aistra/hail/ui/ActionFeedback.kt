package com.aistra.hail.ui

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.FreezeResult

/** Failures stay in the page; successful app actions do not interrupt the user. */
fun View.showActionFeedback(message: CharSequence?) {
    val panel = findViewById<View>(R.id.action_feedback_panel) ?: return
    val report = panel.findViewById<TextView>(R.id.action_feedback_message)
    report.text = message.orEmpty()
    panel.isVisible = !message.isNullOrEmpty()
    panel.findViewById<View>(R.id.dismiss_action_feedback).setOnClickListener {
        report.text = ""
        panel.isVisible = false
    }
}

fun FreezeResult.failureDetails(frozen: Boolean): String? {
    if (failed.isEmpty() && missing.isEmpty() && (frozen || notInstalled.isEmpty())) return null
    return buildString {
        append(AppManager.resultMessage(this@failureDetails, frozen))
        for (pkg in failed) {
            append("\n\n").append(pkg)
            failureReasons[pkg]?.let { append('\n').append(it) }
        }
        for (pkg in missing + if (frozen) emptySet() else notInstalled) {
            append("\n\n").append(pkg)
        }
    }
}
