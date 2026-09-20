package com.sdn.simtelpas.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sdn.simtelpas.AdminReceiver
import com.sdn.simtelpas.BuildConfig
import com.sdn.simtelpas.admin.AdminModeExpiryWorker
import com.sdn.simtelpas.provisioning.DevicePreferences
import com.sdn.simtelpas.provisioning.ProvisioningLog
import com.sdn.simtelpas.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Central place for kiosk lock-task setup/teardown, applied from
 * MainActivity.onCreate() and from AdminReceiver right after QR/NFC
 * device-owner provisioning completes.
 */
object KioskModeManager {

    private const val PREF_NAME         = "simtelpas_kiosk_state"
    private const val KEY_ADMIN_UNTIL   = "admin_mode_until"
    private const val ADMIN_MODE_MINUTES = 10L
    const val ADMIN_MODE_WORK_NAME      = "admin_mode_expiry"

    /**
     * Safe to apply the moment device-owner is granted (AdminReceiver.onEnabled),
     * before any deviceId/setupToken exists. Deliberately excludes
     * DISALLOW_DEBUGGING_FEATURES — see [applyFullLockdown].
     */
    private val baseUserRestrictions: List<String> by lazy {
        val restrictions = mutableListOf(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_ADD_USER,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            restrictions += UserManager.DISALLOW_APPS_CONTROL
        }
        restrictions
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun adminComponent(context: Context) =
        ComponentName(context, AdminReceiver::class.java)

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** Idempotent: safe to call from every relevant onCreate(). */
    fun applyRestrictions(context: Context) {
        val dpm = dpm(context)
        val admin = adminComponent(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        } catch (_: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Deliberately excludes HOME, OVERVIEW, GLOBAL_ACTIONS, NOTIFICATIONS,
                // KEYGUARD — SYSTEM_INFO only shows clock/battery, no shade/quick-settings.
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
            } catch (_: Exception) {
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                dpm.setStatusBarDisabled(admin, true)
            } catch (_: Exception) {
            }
        }

        baseUserRestrictions.forEach { restriction ->
            try {
                dpm.addUserRestriction(admin, restriction)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Called once registration (deviceId/setupToken) is known — from
     * DeviceProvisioningManager.completeRegistration(), via either the QR/NFC
     * callback or SetupActivity's adb path. Adds the persistent-preferred-HOME
     * grant, locks down keyguard shortcuts, and — release builds only — disables
     * USB debugging. That last one must NOT run in [applyRestrictions] (called
     * from onEnabled()) because it would sever the adb session before the
     * follow-up `adb shell am start SetupActivity` command has a chance to run;
     * see SETUP.md for the required command order.
     */
    fun applyFullLockdown(context: Context) {
        applyRestrictions(context)

        val dpm = dpm(context)
        val admin = adminComponent(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(
                admin,
                filter,
                ComponentName(context, MainActivity::class.java),
            )
        } catch (_: Exception) {
        }

        try {
            dpm.setKeyguardDisabledFeatures(admin, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL)
        } catch (_: Exception) {
        }

        if (!BuildConfig.DEBUG) {
            try {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
            } catch (_: Exception) {
            }
        }

        ProvisioningLog.log(context, "full_lockdown_applied")
    }

    /**
     * Relaxes ONLY the UI-navigation restrictions an admin needs to physically
     * leave the kiosk screen: persistent-preferred-HOME, keyguard lockdown, and
     * the lock-task feature bitmask / status bar. It must NEVER touch
     * DISALLOW_SAFE_BOOT / DISALLOW_FACTORY_RESET / DISALLOW_INSTALL_APPS /
     * DISALLOW_UNINSTALL_APPS / DISALLOW_ADD_USER / DISALLOW_APPS_CONTROL /
     * DISALLOW_DEBUGGING_FEATURES. Those are applied once in [applyFullLockdown]
     * and are meant to hold for the device's entire time in service — this
     * function runs on every admin-PIN exit (see AdminAccessManager), so if it
     * ever clears one of those, the admin PIN becomes a way to open USB
     * debugging or uninstall the app without a factory reset, for as long as
     * the PIN is known (including a leaked/guessed/shared PIN). If a real
     * workflow needs one of those lifted temporarily, that's a new, deliberate,
     * separately-reviewed decision — not something this function should do as
     * a side effect of "let the admin see the screen again". There is
     * currently no un-enrollment path in this app (no dpm.clearDeviceOwnerApp()
     * / dpm.wipeData() call anywhere) — decommissioning a device is out of
     * scope until that's built.
     */
    private fun relaxRestrictions(context: Context) {
        val dpm = dpm(context)
        val admin = adminComponent(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        } catch (_: Exception) {
        }
        try {
            dpm.setKeyguardDisabledFeatures(admin, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                        DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                        DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS,
                )
            } catch (_: Exception) {
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                dpm.setStatusBarDisabled(admin, false)
            } catch (_: Exception) {
            }
        }
    }

    fun enterLockTask(activity: Activity) {
        try {
            activity.startLockTask()
        } catch (_: Exception) {
        }
    }

    fun exitLockTask(activity: Activity) {
        try {
            activity.stopLockTask()
        } catch (_: Exception) {
        }
    }

    fun isAdminModeActive(context: Context): Boolean =
        prefs(context).getLong(KEY_ADMIN_UNTIL, 0L) > System.currentTimeMillis()

    /**
     * Called after a PIN-authenticated admin picks an exit action. Temporarily
     * relaxes restrictions/lock-task for [ADMIN_MODE_MINUTES] and schedules an
     * auto re-lock so a forgetful admin doesn't leave the device unlocked.
     */
    fun beginAdminModeAndExit(activity: Activity, toHome: Boolean) {
        val context = activity.applicationContext
        prefs(context).edit {
            putLong(KEY_ADMIN_UNTIL, System.currentTimeMillis() + ADMIN_MODE_MINUTES * 60_000L)
        }

        relaxRestrictions(context)
        exitLockTask(activity)
        scheduleAdminModeExpiry(context)

        val target = if (toHome) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(target)
        } catch (_: Exception) {
        }
        activity.finish()
    }

    /**
     * Ends admin-mode immediately and re-applies kiosk restrictions in place.
     * Must not escalate to [applyFullLockdown] (which adds DISALLOW_DEBUGGING_FEATURES
     * on release builds) until registration has actually completed — otherwise a
     * pre-registration admin-PIN excursion whose 10-minute window simply expires
     * would cut USB debugging and lock Settings' factory-reset option before
     * SetupActivity ever had a chance to run, stranding the device.
     */
    fun endAdminMode(context: Context) {
        prefs(context).edit { putLong(KEY_ADMIN_UNTIL, 0L) }
        if (DevicePreferences.isRegistered(context)) {
            applyFullLockdown(context)
        } else {
            applyRestrictions(context)
        }
        WorkManager.getInstance(context).cancelUniqueWork(ADMIN_MODE_WORK_NAME)
    }

    private fun scheduleAdminModeExpiry(context: Context) {
        val request = OneTimeWorkRequestBuilder<AdminModeExpiryWorker>()
            .setInitialDelay(ADMIN_MODE_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ADMIN_MODE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
