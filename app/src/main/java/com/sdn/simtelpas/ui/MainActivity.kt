package com.sdn.simtelpas.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sdn.simtelpas.R
import com.sdn.simtelpas.admin.AdminAccessManager
import com.sdn.simtelpas.databinding.ActivityMainBinding
import com.sdn.simtelpas.kiosk.KioskModeManager
import com.sdn.simtelpas.kiosk.KioskWatchdogService
import com.sdn.simtelpas.monitoring.HeartbeatWorker
import com.sdn.simtelpas.provisioning.DevicePreferences
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pendingPermissionRequest: PermissionRequest? = null
    private var isInVideoCall  = false
    private var isPageActive  = false

    private val pageActivityHandler  = Handler(Looper.getMainLooper())
    private val pageActivityRunnable = object : Runnable {
        override fun run() {
            if (isPageActive) {
                resetLockTimer()
                pageActivityHandler.postDelayed(this, 10_000L)
            }
        }
    }

    private val lockTimeoutMs = 2 * 60 * 1000L
    private val lockHandler   = Handler(Looper.getMainLooper())
    private val lockRunnable  = Runnable {
        startActivity(Intent(this, LockscreenActivity::class.java))
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                pendingPermissionRequest?.grant(pendingPermissionRequest?.resources)
            } else {
                pendingPermissionRequest?.deny()
                showToast("Camera/microphone permission is required for video calls")
            }
            pendingPermissionRequest = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUI()
        KioskModeManager.applyRestrictions(this)
        KioskModeManager.enterLockTask(this)
        startWatchdogService()

        if (!DevicePreferences.isRegistered(this)) {
            // adb-provisioned device (dpm set-device-owner) that hasn't received its
            // deviceId/setupToken yet — see SetupActivity / SETUP.md. Stay pinned in
            // kiosk mode and wait rather than leaving a bare launcher exposed; once
            // SetupActivity completes registration it relaunches this activity fresh
            // (FLAG_ACTIVITY_CLEAR_TASK) so onCreate() re-evaluates this check.
            setContentView(R.layout.activity_waiting_setup)
            return
        }

        KioskModeManager.applyFullLockdown(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSwipeRefresh()
        setupWebView()
        sendImmediateHeartbeat()
        startHeartbeatWorker()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        KioskModeManager.enterLockTask(this)
        if (DevicePreferences.isRegistered(this)) {
            KioskModeManager.applyFullLockdown(this)
            sendImmediateHeartbeat()
        }
        resetLockTimer()
        pageActivityHandler.post(pageActivityRunnable)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            AdminAccessManager.onVolumeUpPressed(
                activity = this,
                onDialogShown = { lockHandler.removeCallbacks(lockRunnable) },
                onDialogHidden = { resetLockTimer() },
            )
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        isPageActive = false
        pageActivityHandler.removeCallbacks(pageActivityRunnable)
        lockHandler.removeCallbacks(lockRunnable)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetLockTimer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        if (::binding.isInitialized && binding.webView.canGoBack()) binding.webView.goBack()
    }

    private fun resetLockTimer() {
        lockHandler.removeCallbacks(lockRunnable)
        if (!isInVideoCall) lockHandler.postDelayed(lockRunnable, lockTimeoutMs)
    }

    fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startWatchdogService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, KioskWatchdogService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeColors(0xFF1A3A8F.toInt())
        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.swipeRefreshLayout.isRefreshing = false
            binding.webView.reload()
        }
        binding.webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.swipeRefreshLayout.isEnabled = scrollY == 0
        }
    }

    private fun startHeartbeatWorker() {
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "device_heartbeat",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun sendImmediateHeartbeat() {
        WorkManager.getInstance(this)
            .enqueue(OneTimeWorkRequestBuilder<HeartbeatWorker>().build())
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        binding.webView.setOnTouchListener { _, _ ->
            resetLockTimer()
            false
        }

        binding.webView.settings.apply {
            javaScriptEnabled                     = true
            domStorageEnabled                     = true
            mediaPlaybackRequiresUserGesture      = false
            allowFileAccess                       = true
            allowContentAccess                    = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort                       = true
            loadWithOverviewMode                  = true
            cacheMode                             = WebSettings.LOAD_DEFAULT
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                isInVideoCall = true
                lockHandler.removeCallbacks(lockRunnable)

                val needed = mutableListOf<String>()
                request.resources.forEach { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                            if (!isPermissionGranted(Manifest.permission.CAMERA))
                                needed.add(Manifest.permission.CAMERA)
                        }
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                            if (!isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                                needed.add(Manifest.permission.RECORD_AUDIO)
                                needed.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
                            }
                        }
                    }
                }
                if (needed.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    pendingPermissionRequest = request
                    permissionLauncher.launch(needed.toTypedArray())
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                isInVideoCall = false
                pendingPermissionRequest?.deny()
                pendingPermissionRequest = null
                resetLockTimer()
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isInVideoCall = false
                binding.swipeRefreshLayout.isRefreshing = true
                resetLockTimer()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefreshLayout.isRefreshing = false
                injectDeviceContext()
                injectActivityDetector()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                binding.swipeRefreshLayout.isRefreshing = false
                if (request?.isForMainFrame == true) {
                    showToast("Failed to load page. Pull down to retry.")
                }
            }
        }

        binding.webView.addJavascriptInterface(WebAppInterface(), "Android")
        binding.webView.loadUrl("https://app.simtelpas.com")
    }

    inner class WebAppInterface {
        @android.webkit.JavascriptInterface
        fun onPageActivity() {
            runOnUiThread {
                isPageActive = true
                resetLockTimer()
            }
        }

        @android.webkit.JavascriptInterface
        fun onPageIdle() {
            runOnUiThread {
                isPageActive = false
            }
        }

        @android.webkit.JavascriptInterface
        fun onVideoCallStarted() {
            runOnUiThread {
                isInVideoCall = true
                lockHandler.removeCallbacks(lockRunnable)
            }
        }

        @android.webkit.JavascriptInterface
        fun onVideoCallEnded() {
            runOnUiThread {
                isInVideoCall = false
                resetLockTimer()
            }
        }
    }

    private fun injectDeviceContext() {
        val deviceId = DevicePreferences.getDeviceId(this) ?: return
        val js = "window.__deviceId = ${JSONObject.quote(deviceId)};"
        binding.webView.evaluateJavascript(js, null)
    }

    private fun injectActivityDetector() {
        val js = """
            (function() {
                var activityTimer;
                function notifyActive() {
                    Android.onPageActivity();
                    clearTimeout(activityTimer);
                    activityTimer = setTimeout(function() {
                        Android.onPageIdle();
                    }, 30000);
                }
                document.addEventListener('visibilitychange', function() {
                    if (!document.hidden) notifyActive();
                });
                var videos = document.querySelectorAll('video, audio');
                videos.forEach(function(v) {
                    v.addEventListener('play',  function() { Android.onPageActivity(); Android.onVideoCallStarted(); });
                    v.addEventListener('pause', function() { Android.onVideoCallEnded(); });
                    v.addEventListener('ended', function() { Android.onVideoCallEnded(); });
                });
                new MutationObserver(function() {
                    var vids = document.querySelectorAll('video, audio');
                    vids.forEach(function(v) {
                        if (!v._bound) {
                            v._bound = true;
                            v.addEventListener('play',  function() { Android.onPageActivity(); Android.onVideoCallStarted(); });
                            v.addEventListener('pause', function() { Android.onVideoCallEnded(); });
                            v.addEventListener('ended', function() { Android.onVideoCallEnded(); });
                        }
                    });
                }).observe(document.body, { childList: true, subtree: true });
                var observer = new MutationObserver(function() { notifyActive(); });
                observer.observe(document.body, { childList: true, subtree: true, characterData: true });

                document.addEventListener('keydown', function() { Android.onPageActivity(); });
                document.addEventListener('keyup',   function() { Android.onPageActivity(); });
                document.addEventListener('input',   function() { Android.onPageActivity(); });
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun isPermissionGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showToast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
