package com.sdn.simtelpas

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

class HeartbeatWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val client       = OkHttpClient()
    private val baseUrl      = "https://api.simtelpas.com/api"
    private val deviceApiKey = "simtelpas-device-ee64add93d15b695a6c6cbcdba65b7ecde51d597d71f1b72e39f0e874ee67db1"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val deviceId   = DevicePreferences.getDeviceId(context)   ?: return@withContext Result.failure()
            val deviceName = DevicePreferences.getDeviceName(context) ?: return@withContext Result.failure()
            val deviceType = DevicePreferences.getDeviceType(context) ?: "TABLET"
            val location   = DevicePreferences.getLocation(context)   ?: ""

            val payload = JSONObject().apply {
                put("deviceId",       deviceId)
                put("deviceName",     deviceName)
                put("deviceType",     deviceType)
                put("status",         "ONLINE")
                put("location",       location)
                put("osVersion",      "Android ${Build.VERSION.RELEASE}")
                put("appVersion",     getAppVersion())
                put("batteryPercent", getBatteryPercent())
                put("isCharging",     isCharging())
                put("screenOn",       true)
                put("networkType",    getNetworkType())
                put("ipAddress",      getIpAddress())
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