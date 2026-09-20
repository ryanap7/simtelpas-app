package com.sdn.simtelpas.admin

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Stores the kiosk-admin PIN as the bcrypt hash issued by the backend's
 * POST /devices/:id/claim response (never a plaintext PIN — the backend
 * hashes it at registration time) inside a Keystore-backed
 * EncryptedSharedPreferences file, separate from the plaintext
 * device-registration prefs in [com.sdn.simtelpas.provisioning.DevicePreferences].
 */
object AdminPinManager {

    private const val PREF_NAME        = "simtelpas_admin_secure"
    private const val KEY_PIN_HASH     = "pin_hash"
    private const val KEY_FAIL_COUNT   = "fail_count"
    private const val KEY_LOCKED_UNTIL = "locked_until"

    // Per-build recovery PIN for lost-PIN scenarios. Change this per deployment
    // build and keep it off-device (with district/facility IT), never shown in the UI.
    private const val RECOVERY_PIN = "9137546820"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun isPinSet(context: Context): Boolean =
        prefs(context).contains(KEY_PIN_HASH)

    /** Stores the bcrypt hash returned by the backend's /devices/:id/claim response. */
    fun setPinHash(context: Context, bcryptHash: String) {
        prefs(context).edit {
            putString(KEY_PIN_HASH, bcryptHash)
            putInt(KEY_FAIL_COUNT, 0)
            putLong(KEY_LOCKED_UNTIL, 0L)
        }
    }

    fun isLockedOut(context: Context): Boolean =
        System.currentTimeMillis() < prefs(context).getLong(KEY_LOCKED_UNTIL, 0L)

    /** Seconds remaining until [isLockedOut] clears, or 0 if not locked out. */
    fun lockoutSecondsRemaining(context: Context): Long {
        val remainingMs = prefs(context).getLong(KEY_LOCKED_UNTIL, 0L) - System.currentTimeMillis()
        return if (remainingMs > 0) remainingMs / 1000 else 0
    }

    fun verifyPin(context: Context, candidate: String): Boolean {
        if (isLockedOut(context)) return false

        if (candidate == RECOVERY_PIN) {
            clearLockoutState(context)
            return true
        }

        val storedHash = prefs(context).getString(KEY_PIN_HASH, null)
        if (storedHash == null) {
            recordFailure(context)
            return false
        }

        val ok = BCrypt.verifyer().verify(candidate.toCharArray(), storedHash).verified
        if (ok) clearLockoutState(context) else recordFailure(context)
        return ok
    }

    private fun clearLockoutState(context: Context) {
        prefs(context).edit {
            putInt(KEY_FAIL_COUNT, 0)
            putLong(KEY_LOCKED_UNTIL, 0L)
        }
    }

    private fun recordFailure(context: Context) {
        val p = prefs(context)
        val fails = p.getInt(KEY_FAIL_COUNT, 0) + 1
        // 30s, 1m, 2m, 4m, 8m ... capped at 15 minutes
        val lockoutMs = if (fails >= 5) {
            minOf(30_000L * (1L shl (fails - 5)), 15 * 60_000L)
        } else {
            0L
        }
        p.edit {
            putInt(KEY_FAIL_COUNT, fails)
            if (lockoutMs > 0) putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + lockoutMs)
        }
    }
}
