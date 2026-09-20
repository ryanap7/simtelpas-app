package com.sdn.simtelpas.provisioning

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * Single-line-per-step provisioning trail for field verification during
 * per-device adb imaging. Always goes to Logcat (filter with
 * `adb logcat -s SimtelpasProvisioning`) and is mirrored into SharedPreferences
 * for later inspection on debuggable builds (`adb shell run-as com.sdn.simtelpas
 * cat /data/data/com.sdn.simtelpas/shared_prefs/simtelpas_provisioning_log.xml`).
 * On release builds that second channel is only readable before
 * DISALLOW_DEBUGGING_FEATURES takes effect (see KioskModeManager.applyFullLockdown),
 * so logcat during the provisioning window is the source of truth there.
 */
object ProvisioningLog {
    private const val TAG = "SimtelpasProvisioning"
    private const val PREF_NAME = "simtelpas_provisioning_log"

    fun log(context: Context, step: String, detail: String = "ok") {
        Log.i(TAG, "$step: $detail")
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putString(step, "${System.currentTimeMillis()} $detail")
        }
    }
}
