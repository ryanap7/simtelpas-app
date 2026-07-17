package com.sdn.simtelpas

import android.content.Context
import androidx.core.content.edit

object DevicePreferences {

    private const val PREF_NAME       = "simtelpas_device"
    private const val KEY_DEVICE_ID   = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DEVICE_TYPE = "device_type"
    private const val KEY_LOCATION    = "location"
    private const val KEY_TOKEN       = "auth_token"
    private const val KEY_IS_ONLINE   = "is_online"
    private const val KEY_LAST_ONLINE = "last_online_time"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isRegistered(context: Context): Boolean =
        prefs(context).getString(KEY_DEVICE_ID, null) != null

    fun getDeviceId(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_ID, null)

    fun getDeviceName(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_NAME, null)

    fun getDeviceType(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_TYPE, null)

    fun getLocation(context: Context): String? =
        prefs(context).getString(KEY_LOCATION, null)

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    fun getIsOnline(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_ONLINE, false)

    fun setIsOnline(context: Context, online: Boolean) {
        prefs(context).edit { putBoolean(KEY_IS_ONLINE, online) }
    }

    fun getLastOnlineTime(context: Context): String =
        prefs(context).getString(KEY_LAST_ONLINE, "-") ?: "-"

    fun setLastOnlineTime(context: Context, time: String) {
        prefs(context).edit { putString(KEY_LAST_ONLINE, time) }
    }

    fun saveDeviceInfo(
        context: Context,
        deviceId: String,
        deviceName: String,
        deviceType: String,
        location: String,
    ) {
        prefs(context).edit {
            putString(KEY_DEVICE_ID,   deviceId)
            putString(KEY_DEVICE_NAME, deviceName)
            putString(KEY_DEVICE_TYPE, deviceType)
            putString(KEY_LOCATION,    location)
        }
    }

    fun saveToken(context: Context, token: String) {
        prefs(context).edit { putString(KEY_TOKEN, token) }
    }
}