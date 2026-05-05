package com.kooo.evcam.v2.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.kooo.evcam.R
import com.kooo.evcam.databinding.ActivityV2MainA7Binding
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands
import com.kooo.evcam.v2.ui.playback.V2VideoPlaybackActivity

class V2MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_AUTO_START_FROM_BOOT = "auto_start_from_boot"
        const val EXTRA_SILENT_MODE = "silent_mode"
        private const val EMERGENCY_RECORDING_DURATION_MS = V2CameraForegroundService.EMERGENCY_RECORDING_DURATION_MS
        private const val SMALL_WINDOW_CHECK_DELAY_MS = 500L
        private const val FLYME_MINI_WINDOW_MODE = 11
        @Volatile private var lastKnownRecording = false
    }

    private lateinit var binding: ActivityV2MainA7Binding
    private lateinit var previewBinder: V2MainPreviewBinder
    private lateinit var recordingUi: V2MainRecordingUiController
    private lateinit var bootAutoRecorder: V2MainBootAutoRecorder
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: V2CameraForegroundService? = null
    private var bound = false
    private var bindingService = false
    private var startServiceWhenPermissionsGranted = false
    private val dateTimeTicker = object : Runnable {
        override fun run() {
            binding.tvDatetime.text = V2TimeWatermark.format()
            mainHandler.postDelayed(this, V2TimeWatermark.nextSecondDelayMs())
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? V2CameraForegroundService.LocalBinder)?.service()
            bound = true
            bindingService = false
            V2AppLog.i("V2MainActivity", "service connected name=$name serviceReady=${service != null}")
            service?.ensureReadyAfterPermissions()
            service?.setUiStatusListener { status ->
                binding.tvRecordingStats.post {
                    binding.tvRecordingStats.text = status
                    previewBinder.updatePreviewPlaceholders(service?.isPreviewPausedByAvoidance() == true)
                    syncRecordButtonFromService()
                }
            }
            service?.setUiEmergencyRecordingListener { active, endsAtMs ->
                runOnUiThread { recordingUi.setEmergencyRecordingActive(active, endsAtMs) }
            }
            service?.setUiVisibility(true) { moveTaskToBack(true) }
            previewBinder.bindPreviews()
            previewBinder.updatePreviewPlaceholders(service?.isPreviewPausedByAvoidance() == true)
            syncRecordButtonFromService()
            bootAutoRecorder.maybeStart()
        }
        override fun onServiceDisconnected(name: ComponentName?) { V2AppLog.w("V2MainActivity", "service disconnected name=$name"); bound = false; bindingService = false; service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        V2AppLog.init(this)
        V2AppLog.i("V2MainActivity", "onCreate")
        if (closeIfLaunchedInSmallWindow("create")) return
        binding = ActivityV2MainA7Binding.inflate(layoutInflater)
        setContentView(binding.root)
        previewBinder = V2MainPreviewBinder(binding, mainHandler) { service }
        recordingUi = V2MainRecordingUiController(binding, mainHandler, EMERGENCY_RECORDING_DURATION_MS) { recording ->
            lastKnownRecording = recording
        }
        bootAutoRecorder = V2MainBootAutoRecorder(
            mainHandler = mainHandler,
            service = { service },
            isBound = { bound },
            recordingUi = recordingUi,
            moveTaskToBack = { moveTaskToBack(true) },
        )
        V2TimeWatermark.applyStyle(binding.tvDatetime)
        bootAutoRecorder.consume(intent)
        binding.btnStartRecord.setOnClickListener { toggleRecordingWithToast() }
        binding.btnExit.setOnClickListener {
            startActivity(Intent(this, V2VideoPlaybackActivity::class.java))
        }
        binding.btnClose.setOnClickListener { closeApp() }
        binding.btnVideoPlayback.setOnClickListener { startEmergencyRecordingWithToast() }
        dateTimeTicker.run()
        recordingUi.updateNormalRecording(lastKnownRecording)
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        if (closeIfLaunchedInSmallWindow("resume")) return
        mainHandler.postDelayed({ closeIfLaunchedInSmallWindow("resume-delayed") }, SMALL_WINDOW_CHECK_DELAY_MS)
        restoreMainWindowMode()
        V2AppLog.i("V2MainActivity", "onResume hasPermissions=${hasPermissions()}")
        if (hasPermissions()) continueAfterPermissionsGranted("resume")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) closeIfLaunchedInSmallWindow("focus")
    }

    private fun closeIfLaunchedInSmallWindow(reason: String): Boolean {
        if (isFinishing || isDestroyed) return true
        val multiWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode
        val flymeSmallWindow = isCurrentConfigurationInFlymeSmallWindow() || isCurrentTaskInFlymeSmallWindow() || isCurrentWindowPortraitLike()
        if (!multiWindow && !flymeSmallWindow) return false
        V2AppLog.w("V2MainActivity", "main preview launched in small window, closing task reason=$reason multiWindow=$multiWindow flymeSmallWindow=$flymeSmallWindow taskId=$taskId")
        service?.setUiVisibility(false)
        finishCurrentTaskFromSmallWindow()
        return true
    }

    private fun isCurrentConfigurationInFlymeSmallWindow(): Boolean = runCatching {
        val windowConfiguration = resources.configuration.javaClass.methods
            .firstOrNull { it.name == "getWindowConfiguration" && it.parameterTypes.isEmpty() }
            ?.invoke(resources.configuration)
            ?: return@runCatching false
        val description = windowConfiguration.toString()
        val mode = runCatching {
            windowConfiguration.javaClass.getMethod("getWindowingMode").invoke(windowConfiguration) as? Int
        }.getOrNull()
        description.contains("flyme-mini-window", ignoreCase = true) || mode == FLYME_MINI_WINDOW_MODE
    }.onFailure {
        V2AppLog.d("V2MainActivity", "small window configuration check unavailable: ${it.javaClass.simpleName}")
    }.getOrDefault(false)

    private fun isCurrentTaskInFlymeSmallWindow(): Boolean = runCatching {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        activityManager.getRunningTasks(20).orEmpty().any { task ->
            task.id == taskId && task.toString().contains("flyme-mini-window", ignoreCase = true)
        }
    }.onFailure {
        V2AppLog.w("V2MainActivity", "small window task check failed", it)
    }.getOrDefault(false)

    private fun isCurrentWindowPortraitLike(): Boolean {
        val decorWidth = window.decorView.width
        val decorHeight = window.decorView.height
        if (decorWidth > 0 && decorHeight > 0 && decorHeight > decorWidth) return true
        val metrics = resources.displayMetrics
        return metrics.widthPixels > 0 && metrics.heightPixels > 0 && metrics.heightPixels > metrics.widthPixels
    }

    private fun finishCurrentTaskFromSmallWindow() {
        runCatching {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            activityManager.appTasks.firstOrNull { appTask ->
                runCatching { appTask.taskInfo?.id == taskId }.getOrDefault(false)
            }?.let { appTask ->
                V2AppLog.w("V2MainActivity", "remove current small-window task taskId=$taskId")
                appTask.finishAndRemoveTask()
                return
            }
        }.onFailure {
            V2AppLog.w("V2MainActivity", "remove current small-window task failed", it)
        }
        finishAffinity()
        finishAndRemoveTask()
    }

    private fun restoreMainWindowMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bootAutoRecorder.consume(intent)
        bootAutoRecorder.maybeStart()
    }

    override fun onUserLeaveHint() {
        previewBinder.unbindPreviews()
        super.onUserLeaveHint()
    }

    override fun onPause() {
        V2AppLog.i("V2MainActivity", "onPause")
        service?.setUiStatusListener(null)
        service?.setUiEmergencyRecordingListener(null)
        service?.setUiVisibility(false)
        if (::previewBinder.isInitialized) previewBinder.unbindPreviews()
        if (bound) { unbindService(connection); bound = false; service = null }
        super.onPause()
    }

    override fun onDestroy() {
        V2AppLog.i("V2MainActivity", "onDestroy finishing=$isFinishing bound=$bound")
        mainHandler.removeCallbacksAndMessages(null)
        if (::recordingUi.isInitialized) recordingUi.destroy()
        if (::previewBinder.isInitialized) previewBinder.releasePreviewSurfaces()
        V2AppLog.saveToPersistentLog(this)
        super.onDestroy()
    }

    private fun closeApp() {
        V2AppLog.w("V2MainActivity", "close app requested")
        service?.shutdownFromUi() ?: V2CameraServiceCommands.stop(this)
        finishAndRemoveTask()
    }

    private fun syncRecordButtonFromService() {
        service?.let { recordingUi.updateNormalRecording(it.isNormalRecording()) }
    }

    private fun toggleRecordingWithToast() {
        val cameraService = service
        if (cameraService == null) {
            V2AppLog.w("V2MainActivity", "toggle recording skipped: service null")
            Toast.makeText(this, "相机服务启动中", Toast.LENGTH_SHORT).show()
            return
        }
        val wasRecording = cameraService.isNormalRecording()
        val isRecording = cameraService.toggleRecording()
        V2AppLog.i("V2MainActivity", "toggle recording was=$wasRecording now=$isRecording")
        recordingUi.updateNormalRecording(isRecording)
        val message = when {
            !wasRecording && isRecording -> "开始录制"
            wasRecording && !isRecording -> "停止录制"
            !wasRecording && !isRecording -> "录制启动失败"
            else -> if (isRecording) "正在录制" else "已停止录制"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startEmergencyRecordingWithToast() {
        if (recordingUi.isEmergencyRecording) {
            recordingUi.emergencyRepeatToastMessage()?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val cameraService = service
        if (cameraService == null) {
            V2AppLog.w("V2MainActivity", "emergency recording skipped: service null")
            Toast.makeText(this, "相机服务启动中", Toast.LENGTH_SHORT).show()
            return
        }
        val started = cameraService.startEmergencyRecording(EMERGENCY_RECORDING_DURATION_MS) { active ->
            runOnUiThread { recordingUi.setEmergencyRecordingActive(active) }
        }
        V2AppLog.i("V2MainActivity", "emergency recording requested started=$started")
        if (!started) Toast.makeText(this, "紧急录制启动失败", Toast.LENGTH_SHORT).show()
    }

    private fun ensurePermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA)
        val missing = perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        V2AppLog.i("V2MainActivity", "ensurePermissions missing=$missing")
        if (missing) {
            startServiceWhenPermissionsGranted = true
            ActivityCompat.requestPermissions(this, perms, 2001)
        } else {
            continueAfterPermissionsGranted("ensure")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001) {
            V2AppLog.i("V2MainActivity", "permission result grants=${grantResults.joinToString()} hasPermissions=${hasPermissions()}")
            if (hasPermissions()) continueAfterPermissionsGranted("permission_result") else Toast.makeText(this, "相机权限未授予", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasPermissions() = arrayOf(Manifest.permission.CAMERA).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun continueAfterPermissionsGranted(reason: String) {
        if (!startServiceWhenPermissionsGranted && bound) return
        startServiceWhenPermissionsGranted = false
        V2AppLog.i("V2MainActivity", "continueAfterPermissionsGranted reason=$reason bound=$bound")
        binding.root.post { startAndBindService() }
    }

    private fun startAndBindService() {
        V2AppLog.i("V2MainActivity", "startAndBindService bound=$bound binding=$bindingService")
        val intent = Intent(this, V2CameraForegroundService::class.java)
        V2CameraServiceCommands.start(this)
        if (!bound && !bindingService) {
            bindingService = true
            if (!bindService(intent, connection, BIND_AUTO_CREATE)) bindingService = false
        }
    }

}
