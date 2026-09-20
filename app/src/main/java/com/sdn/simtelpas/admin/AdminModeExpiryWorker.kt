package com.sdn.simtelpas.admin

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sdn.simtelpas.kiosk.KioskModeManager
import com.sdn.simtelpas.ui.MainActivity

/**
 * Fires ADMIN_MODE_MINUTES after an admin exits the kiosk via the PIN menu.
 * If admin-mode is still active (extended manually), it's left alone;
 * otherwise the app is relaunched so KioskModeManager.applyRestrictions()
 * and lock-task re-engage automatically.
 */
class AdminModeExpiryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (KioskModeManager.isAdminModeActive(applicationContext)) {
            return Result.success()
        }

        KioskModeManager.endAdminMode(applicationContext)
        applicationContext.startActivity(
            Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        return Result.success()
    }
}
