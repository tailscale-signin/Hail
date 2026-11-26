package com.aistra.hail.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import com.aistra.hail.R

class DeviceUnlock(private val activity: FragmentActivity) {
    companion object {
        private val authenticators get() = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

        fun available(context: Context) =
            BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS ||
                (Build.VERSION.SDK_INT < 30 && context.getSystemService<KeyguardManager>()?.isDeviceSecure == true)
    }

    private var pending: ((Boolean) -> Unit)? = null
    private val credential = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        complete(it.resultCode == Activity.RESULT_OK)
    }

    private fun complete(allowed: Boolean) {
        val callback = pending
        pending = null
        callback?.invoke(allowed)
    }

    @Suppress("DEPRECATION")
    fun request(result: (Boolean) -> Unit) {
        if (pending != null) return
        pending = result
        if (BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = complete(true)
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = complete(false)
                })
            val info = BiometricPrompt.PromptInfo.Builder().setTitle(activity.getString(R.string.action_biometric))
                .setSubtitle(activity.getString(R.string.msg_biometric)).setAllowedAuthenticators(authenticators)
            if (Build.VERSION.SDK_INT < 30) info.setNegativeButtonText(activity.getString(android.R.string.cancel))
            prompt.authenticate(info.build())
        } else {
            val fallback = if (Build.VERSION.SDK_INT < 30) activity.getSystemService<KeyguardManager>()
                ?.createConfirmDeviceCredentialIntent(activity.getString(R.string.action_biometric), null) else null
            if (fallback == null) complete(false) else credential.launch(fallback)
        }
    }
}
