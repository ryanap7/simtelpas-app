package com.sdn.simtelpas.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sdn.simtelpas.R
import com.sdn.simtelpas.provisioning.DeviceProvisioningManager
import com.sdn.simtelpas.provisioning.DevicePreferences
import com.sdn.simtelpas.provisioning.ProvisioningLog

/**
 * adb companion to QR/NFC provisioning, run once right after
 * `dpm set-device-owner` finishes:
 *
 *   adb shell am start -n com.sdn.simtelpas/.ui.SetupActivity \
 *       --es deviceId "<id>" --es setupToken "<token>"
 *
 * Carries the same deviceId/setupToken a QR payload would have carried in
 * PROVISIONING_ADMIN_EXTRAS_BUNDLE (see AdminReceiver.onProfileProvisioningComplete),
 * since plain `dpm set-device-owner` has no extras mechanism of its own. See
 * SETUP.md for the full imaging sequence.
 */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        if (DevicePreferences.isRegistered(this)) {
            // Already claimed by a previous run — refuse to re-register over an
            // in-service device from a stray/repeated adb command.
            ProvisioningLog.log(this, "setup_activity_rejected", "already registered")
            finishToMain()
            return
        }

        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val setupToken = intent.getStringExtra(EXTRA_SETUP_TOKEN)

        if (deviceId.isNullOrBlank() || setupToken.isNullOrBlank()) {
            ProvisioningLog.log(this, "setup_activity_rejected", "missing deviceId/setupToken extras")
            finishToMain()
            return
        }

        ProvisioningLog.log(this, "setup_activity_started", "deviceId=$deviceId")
        DeviceProvisioningManager.completeRegistration(
            context = applicationContext,
            deviceId = deviceId,
            setupToken = setupToken,
        )
        finishToMain()
    }

    private fun finishToMain() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_SETUP_TOKEN = "setupToken"
    }
}
