package com.sdn.simtelpas

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import com.sdn.simtelpas.kiosk.KioskModeManager
import com.sdn.simtelpas.provisioning.DeviceProvisioningManager
import com.sdn.simtelpas.provisioning.ProvisioningLog

class AdminReceiver : DeviceAdminReceiver() {

    /**
     * Fired the moment device-owner is granted — including the `adb shell dpm
     * set-device-owner` path, which (unlike QR/NFC) carries no extras bundle and
     * never triggers onProfileProvisioningComplete(). Applies only the
     * restrictions that are safe before a deviceId/setupToken exists (see
     * KioskModeManager.applyRestrictions vs applyFullLockdown) so the kiosk is
     * pinned immediately without waiting for the follow-up `adb shell am start
     * SetupActivity` step described in SETUP.md.
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        ProvisioningLog.log(context, "device_owner_enabled")
        KioskModeManager.applyRestrictions(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {}

    /**
     * Fired once after QR/NFC device-owner provisioning finishes. The QR
     * payload's PROVISIONING_ADMIN_EXTRAS_BUNDLE carries device registration
     * data (pre-created by an admin on the backend, deviceId included) plus
     * a one-time setupToken so the field technician never types anything
     * on-device. The admin PIN itself is never in the QR — it's fetched via
     * POST /devices/:id/claim as a bcrypt hash (see DeviceClaimWorker).
     *
     * Not fired for the adb `dpm set-device-owner` path — that path calls the
     * same DeviceProvisioningManager.completeRegistration() from SetupActivity
     * instead, once an admin supplies deviceId/setupToken via `adb shell am start`.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)

        val extras = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }

        val deviceId    = extras?.getString("deviceId")
        val deviceName  = extras?.getString("deviceName")
        val deviceType  = extras?.getString("deviceType")
        val location    = extras?.getString("location")
        val setupToken  = extras?.getString("setupToken")

        if (!deviceId.isNullOrBlank() && !deviceName.isNullOrBlank() &&
            !deviceType.isNullOrBlank() && !location.isNullOrBlank() && !setupToken.isNullOrBlank()
        ) {
            DeviceProvisioningManager.completeRegistration(
                context    = context,
                deviceId   = deviceId,
                setupToken = setupToken,
                deviceName = deviceName,
                deviceType = deviceType,
                location   = location,
            )
        } else {
            // No usable extras bundle — same situation as the adb path, just
            // arrived via QR/NFC. Kiosk stays pinned (onEnabled already applied
            // base restrictions); MainActivity shows its waiting-for-setup screen
            // until SetupActivity or a corrected QR payload supplies deviceId/setupToken.
            KioskModeManager.applyRestrictions(context)
        }
    }
}
