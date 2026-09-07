package com.seamless.player.ui.common

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.seamless.player.R
import com.seamless.player.util.Log

/**
 * Fingerprint / face / device-credential gate for the app and for individual folders.
 *
 * This deliberately stores no PIN of its own. The fallback is the system's DEVICE_CREDENTIAL
 * authenticator, so the phone's existing lock screen does the work: nothing secret is ever
 * written into the app's preferences, there is no home-grown comparison to get wrong, and
 * the user gets whatever they already use to unlock the device.
 *
 * Note what this is and is not. It keeps folders out of casual view; it is not encryption.
 * The files stay exactly where they were and any other app can still read them.
 */
object AppLock {

    /** Authenticators to request, which differ by platform version. */
    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            // Combining the two is not supported before API 30; the deprecated
            // setDeviceCredentialAllowed path is used there instead.
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

    /** True when the device has a fingerprint, face or screen lock that we can use. */
    fun isAvailable(activity: FragmentActivity): Boolean {
        val manager = BiometricManager.from(activity)
        val biometric = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (biometric == BiometricManager.BIOMETRIC_SUCCESS) return true
        // No enrolled biometric is fine as long as there is a screen lock to fall back to.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } else {
            false
        }
    }

    /**
     * Prompts, then calls [onSuccess] or [onFailure]. [onFailure] fires when the user backs
     * out as well as on a hard error, so callers should treat it as "not authorised".
     */
    fun authenticate(
        activity: FragmentActivity,
        titleRes: Int,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {},
    ) {
        if (!isAvailable(activity)) {
            // Nothing to authenticate against; refusing entry would lock the user out for
            // good, so let them through and let Settings explain.
            Log.w("AppLock", "no biometric or device credential enrolled; allowing")
            onSuccess()
            return
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(titleRes))
            .apply {
                subtitle?.let { setSubtitle(it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(authenticators)
                } else {
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
                // A negative button must not be set when device credential is allowed.
            }
            .build()

        val prompt = BiometricPrompt(
            activity,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) = onSuccess()

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    Log.d("AppLock", "authentication error $code: $message")
                    onFailure()
                }

                // A single bad fingerprint is not a decision; the prompt stays up.
                override fun onAuthenticationFailed() = Unit
            },
        )
        prompt.authenticate(info)
    }

    /** Convenience for the common "gate this folder" case. */
    fun forFolder(
        activity: FragmentActivity,
        folderName: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {},
    ) = authenticate(
        activity,
        R.string.lock_folder_prompt_title,
        folderName,
        onSuccess,
        onFailure,
    )
}
