package com.sdn.simtelpas.admin

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sdn.simtelpas.kiosk.KioskModeManager
import com.sdn.simtelpas.provisioning.DevicePreferences

/**
 * Hidden entry point into kiosk-admin actions. Triggered by pressing
 * Volume-Up 5 times within 3 seconds — the key event is still forwarded to
 * the system afterward (normal volume UI still appears), so the sequence
 * is not discoverable as an "exit gesture" the way a visible button would be.
 */
object AdminAccessManager {

    private const val PRESS_THRESHOLD = 5
    private const val PRESS_WINDOW_MS = 3000L

    private var pressCount   = 0
    private var firstPressAt = 0L

    fun onVolumeUpPressed(
        activity: AppCompatActivity,
        onDialogShown: () -> Unit = {},
        onDialogHidden: () -> Unit = {},
    ) {
        val now = System.currentTimeMillis()
        if (now - firstPressAt > PRESS_WINDOW_MS) {
            pressCount = 0
            firstPressAt = now
        }
        pressCount++
        if (pressCount >= PRESS_THRESHOLD) {
            pressCount = 0
            showPinDialog(activity, onDialogShown, onDialogHidden)
        }
    }

    private fun showPinDialog(
        activity: AppCompatActivity,
        onShown: () -> Unit,
        onHidden: () -> Unit,
    ) {
        if (AdminPinManager.isLockedOut(activity)) {
            val seconds = AdminPinManager.lockoutSecondsRemaining(activity)
            toast(activity, "Terlalu banyak percobaan. Coba lagi dalam ${seconds}s.")
            return
        }

        onShown()
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(activity)
            .setTitle("PIN Admin")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (AdminPinManager.verifyPin(activity, input.text.toString())) {
                    showActionMenu(activity)
                } else {
                    toast(activity, "PIN salah")
                }
                onHidden()
            }
            .setNegativeButton("Batal") { _, _ -> onHidden() }
            .setOnCancelListener { onHidden() }
            .setCancelable(true)
            .show()
    }

    private fun showActionMenu(activity: AppCompatActivity) {
        val options = arrayOf(
            "Keluar Kiosk ke Settings",
            "Keluar Kiosk ke Home",
            "Batalkan Registrasi Perangkat",
            "Batal",
        )
        AlertDialog.Builder(activity)
            .setTitle("Aksi Admin")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> KioskModeManager.beginAdminModeAndExit(activity, toHome = false)
                    1 -> KioskModeManager.beginAdminModeAndExit(activity, toHome = true)
                    2 -> {
                        DevicePreferences.clearDeviceInfo(activity)
                        KioskModeManager.beginAdminModeAndExit(activity, toHome = false)
                    }
                }
            }
            .show()
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
