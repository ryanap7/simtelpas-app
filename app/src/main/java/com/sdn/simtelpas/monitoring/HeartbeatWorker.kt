package com.sdn.simtelpas.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sdn.simtelpas.BuildConfig
import com.sdn.simtelpas.provisioning.DevicePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Periodic (15-minute WorkManager cycle, plus one immediate run on every
 * MainActivity resume) POST to /device-logs/heartbeat, keeping the backend's
 * live device list current: online status, battery, network type/IP, and
 * app/OS version. On failure, marks the device offline locally
 * (DevicePreferences — surfaced on LockscreenActivity) and returns
 * Result.retry() so WorkManager's own backoff handles resubmission; the
 * caller (MainActivity) never needs to know a heartbeat failed.
 */
class HeartbeatWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val client       = OkHttpClient()
    private val baseUrl      = "https://api.simtelpas.com/api"
    private val deviceApiKey = BuildConfig.DEVICE_API_KEY

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val deviceId   = DevicePreferences.getDeviceId(context)   ?: return@withContext Result.failure()
            val deviceName = DevicePreferences.getDeviceName(context) ?: return@withContext Result.failure()
            val deviceType = DevicePreferences.getDeviceType(context) ?: "TABLET"
            val location   = DevicePreferences.getLocation(context)   ?: ""

            val payload = JSONObject().apply {
                put("deviceId",   deviceId)
                put("deviceName", deviceName)
                put("deviceType", deviceType)
                put("status",     "ONLINE")
                put("location",   location)
                put("osVersion",  "Android ${Build.VERSION.RELEASE}")
                put("appVersion", getAppVersion())
                put("isCharging", isCharging())
                put("screenOn",   true)
                put("networkType", getNetworkType())

                // Backend validates these strictly (must be a valid IPv4 /
                // 0-100 respectively) and treats them as optional — omit the
                // key entirely rather than send "" or -1 for "unknown".
                getBatteryPercent().takeIf { it in 0..100 }?.let { put("batteryPercent", it) }
                getIpAddress().takeIf { it.isNotBlank() }?.let { put("ipAddress", it) }
            }

            val request = Request.Builder()
                .url("$baseUrl/device-logs/heartbeat")
                .addHeader("X-Device-Key", deviceApiKey)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                DevicePreferences.setIsOnline(context, true)
                DevicePreferences.setLastOnlineTime(context, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                Result.success()
            } else {
                DevicePreferences.setIsOnline(context, false)
                Result.retry()
            }

        } catch (_: Exception) {
            DevicePreferences.setIsOnline(context, false)
            Result.retry()
        }
    }

    private fun getBatteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    private fun getNetworkType(): String {
        val cm  = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4g"
            else                                                     -> "offline"
        }
    }

    private fun getIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull {
                    !it.isLoopbackAddress &&
                            !it.hostAddress.isNullOrEmpty() &&
                            !it.hostAddress!!.contains(':')
                }
                ?.hostAddress ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
}
