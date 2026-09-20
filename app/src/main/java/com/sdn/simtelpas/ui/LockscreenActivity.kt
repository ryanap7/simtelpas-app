package com.sdn.simtelpas.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sdn.simtelpas.R
import com.sdn.simtelpas.databinding.ActivityLockscreenBinding
import com.sdn.simtelpas.provisioning.DevicePreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class LockscreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockscreenBinding
    private lateinit var gestureDetector: GestureDetector

    private val clockHandler  = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        @SuppressLint("SetTextI18n")
        override fun run() {
            binding.tvClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        binding = ActivityLockscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()
        setupGestureDetector()
        startClock()
        updateServerStatus()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        updateServerStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() { }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val swipeThreshold         = 150
            private val swipeVelocityThreshold = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val deltaY = (e1?.rawY ?: 0f) - e2.rawY
                if (deltaY > swipeThreshold && abs(velocityY) > swipeVelocityThreshold) {
                    unlockToMain()
                    return true
                }
                return false
            }
        })

        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startClock() {
        clockHandler.post(clockRunnable)

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }

        binding.tvVersion.text = "v$appVersion • Kiosk"
        binding.tvDate.text    = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id")).format(Date())
    }

    @SuppressLint("SetTextI18n")
    private fun updateServerStatus() {
        val isOnline       = DevicePreferences.getIsOnline(this)
        val lastOnlineTime = DevicePreferences.getLastOnlineTime(this)

        if (isOnline) {
            binding.ivWifiIcon.setImageResource(R.drawable.ic_wifi_on)
            binding.tvStatus.text = "ONLINE"
            binding.tvStatus.setTextColor(0xFF4CAF50.toInt())
            binding.tvError.visibility = View.GONE
        } else {
            binding.ivWifiIcon.setImageResource(R.drawable.ic_wifi_off)
            binding.tvStatus.text = "OFFLINE"
            binding.tvStatus.setTextColor(0xFFE53935.toInt())
            binding.tvError.visibility = View.VISIBLE
            binding.tvError.text = "Cannot reach server • $lastOnlineTime"
        }
    }

    private fun unlockToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }
}
