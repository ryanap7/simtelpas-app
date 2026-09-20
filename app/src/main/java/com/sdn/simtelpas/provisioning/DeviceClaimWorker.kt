package com.sdn.simtelpas.provisioning

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sdn.simtelpas.admin.AdminPinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Calls the one-time-use POST /devices/:id/claim endpoint after QR/NFC
 * device-owner provisioning finishes, trading the setupToken carried in
 * PROVISIONING_ADMIN_EXTRAS_BUNDLE for the backend-issued bcrypt adminPinHash.
 * The backend marks the token used on first successful call, so this must
 * only be enqueued once per provisioning (see AdminReceiver).
 */
class DeviceClaimWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val client  = OkHttpClient()
    private val baseUrl = "https://api.simtelpas.com/api"

    companion object {
        const val KEY_DEVICE_ID   = "deviceId"
        const val KEY_SETUP_TOKEN = "setupToken"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (AdminPinManager.isPinSet(context)) {
            // Already claimed on a previous run; the token is one-time-use
            // so retrying would just get a 400 from the backend.
            return@withContext Result.success()
        }

        val deviceId = inputData.getString(KEY_DEVICE_ID) ?: return@withContext Result.failure()
        val setupToken = inputData.getString(KEY_SETUP_TOKEN) ?: return@withContext Result.failure()

        try {
            val request = Request.Builder()
                .url("$baseUrl/devices/$deviceId/claim?token=$setupToken")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    // 400 (token already used) / 401 (invalid) / 404 (not found) won't
                    // succeed on retry.
                    ProvisioningLog.log(context, "device_claim_failed", "http=${it.code}")
                    return@withContext Result.failure()
                }

                val body = it.body?.string().orEmpty()
                val pinHash = JSONObject(body).getJSONObject("data").getString("adminPinHash")
                AdminPinManager.setPinHash(context, pinHash)
                ProvisioningLog.log(context, "device_claimed")
                Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
