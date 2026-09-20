package com.sdn.simtelpas.provisioning

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sdn.simtelpas.kiosk.KioskModeManager

/**
 * Single entry point that turns a device-owner install with a known
 * deviceId/setupToken into a fully registered, fully locked kiosk. Called from
 * both provisioning paths this app supports:
 *  - QR/NFC managed provisioning (AdminReceiver.onProfileProvisioningComplete),
 *    which carries deviceName/deviceType/location in the same extras bundle.
 *  - The adb companion flow (SetupActivity, see SETUP.md), which only has
 *    deviceId + setupToken to work with — `adb shell dpm set-device-owner` has
 *    no extras-bundle mechanism, so the rest fall back to placeholders.
 */
object DeviceProvisioningManager {

    fun completeRegistration(
        context: Context,
        deviceId: String,
        setupToken: String,
        deviceName: String? = null,
        deviceType: String? = null,
        location: String? = null,
    ) {
        DevicePreferences.saveDeviceInfo(
            context = context,
            deviceId = deviceId,
            deviceName = deviceName ?: deviceId,
            deviceType = deviceType ?: "TABLET",
            location = location ?: "-",
        )
        ProvisioningLog.log(context, "registration_saved", "deviceId=$deviceId")

        enqueueClaim(context, deviceId, setupToken)
        ProvisioningLog.log(context, "claim_worker_enqueued", "deviceId=$deviceId")

        KioskModeManager.applyFullLockdown(context)
    }

    private fun enqueueClaim(context: Context, deviceId: String, setupToken: String) {
        val request = OneTimeWorkRequestBuilder<DeviceClaimWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    DeviceClaimWorker.KEY_DEVICE_ID to deviceId,
                    DeviceClaimWorker.KEY_SETUP_TOKEN to setupToken,
                )
            )
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }
}
