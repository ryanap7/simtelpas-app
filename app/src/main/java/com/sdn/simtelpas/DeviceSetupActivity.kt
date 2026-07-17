package com.sdn.simtelpas

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sdn.simtelpas.databinding.ActivityDeviceSetupBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class DeviceSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceSetupBinding

    private val lockTimeoutMs = 5 * 60 * 1000L
    private val lockHandler   = Handler(Looper.getMainLooper())
    private val lockRunnable  = Runnable {
        startActivity(Intent(this, LockscreenActivity::class.java))
    }

    private val client       = OkHttpClient()
    private val scope        = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val baseUrl      = "https://api.simtelpas.com/api"
    private val deviceApiKey = "simtelpas-device-ee64add93d15b695a6c6cbcdba65b7ecde51d597d71f1b72e39f0e874ee67db1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDeviceSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()
        enableKioskMode()
        setupSpinner()
        setupRegisterButton()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetLockTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        resetLockTimer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() { }

    override fun onPause() {
        super.onPause()
        lockHandler.removeCallbacks(lockRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        lockHandler.removeCallbacks(lockRunnable)
        scope.cancel()
    }

    private fun resetLockTimer() {
        lockHandler.removeCallbacks(lockRunnable)
        lockHandler.postDelayed(lockRunnable, lockTimeoutMs)
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun enableKioskMode() {
        try {
            val dpm            = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, AdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            }
            startLockTask()
        } catch (_: Exception) {
            try { startLockTask() } catch (_: Exception) { }
        }
    }

    private fun setupSpinner() {
        val deviceTypes = listOf("TABLET", "DESKTOP")
        binding.spinnerDeviceType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            deviceTypes
        )
    }


    private fun setupRegisterButton() {
        binding.btnRegister.setOnClickListener {
            val deviceName = binding.etDeviceName.text.toString().trim()
            val location   = binding.etLocation.text.toString().trim()
            val deviceType = binding.spinnerDeviceType.selectedItem.toString()

            if (!validateInputs(deviceName, location)) return@setOnClickListener

            setLoading(true)

            scope.launch {
                val deviceId = registerDevice(deviceName, location, deviceType)
                setLoading(false)

                if (deviceId != null) {
                    DevicePreferences.saveDeviceInfo(
                        context    = this@DeviceSetupActivity,
                        deviceId   = deviceId,
                        deviceName = deviceName,
                        deviceType = deviceType,
                        location   = location,
                    )
                    showToast(getString(R.string.toast_register_success))
                    navigateToMain()
                } else {
                    showToast(getString(R.string.toast_register_failed))
                }
            }
        }
    }

    private fun validateInputs(deviceName: String, location: String): Boolean {
        if (deviceName.isEmpty()) {
            binding.etDeviceName.error = getString(R.string.error_device_name_required)
            binding.etDeviceName.requestFocus()
            return false
        }
        if (location.isEmpty()) {
            binding.etLocation.error = getString(R.string.error_location_required)
            binding.etLocation.requestFocus()
            return false
        }
        return true
    }

    private fun setLoading(isLoading: Boolean) {
        binding.btnRegister.isEnabled  = !isLoading
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun navigateToMain() {
        lockHandler.removeCallbacks(lockRunnable)
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private suspend fun registerDevice(
        deviceName: String,
        location: String,
        deviceType: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("deviceName", deviceName)
                put("deviceType", deviceType)
                put("location",   location)
            }

            val request = Request.Builder()
                .url("$baseUrl/device-logs/register")
                .addHeader("X-Device-Key", deviceApiKey)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            JSONObject(body).getJSONObject("data").getString("deviceId")

        } catch (_: Exception) {
            null
        }
    }

    private fun showToast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}