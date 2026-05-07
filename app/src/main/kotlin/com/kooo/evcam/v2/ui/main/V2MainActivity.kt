package com.kooo.evcam.v2.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
        private const val SMALL_WINDOW_CHECK_DELAY_MS = 500L
        @Volatile private var lastKnownRecording = false
    }

    private lateinit var binding: ActivityV2MainA7Binding
    private lateinit var previewBinder: V2MainPreviewBinder
    private lateinit var recordingUi: V2MainRecordingUiController
    private lateinit var bootAutoRecorder: V2MainBootAutoRecorder
    private lateinit var serviceBinder: V2MainCameraServiceBinder
    private lateinit var smallWindowGuard: V2MainSmallWindowGuard
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startServiceWhenPermissionsGranted = false
    private val service: V2CameraForegroundService?
        get() = if (::serviceBinder.isInitialized) serviceBinder.service else null

    private val dateTimeTicker = object : Runnable {
        override fun run() {
            binding.tvDatetime.text = V2TimeWatermark.format()
            mainHandler.postDelayed(this, V2TimeWatermark.nextSecondDelayMs())
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        V2AppLog.init(this)
        V2AppLog.i("V2MainActivity", "onCreate")
        serviceBinder = V2MainCameraServiceBinder(this, ::handleCameraServiceConnected)
        smallWindowGuard = V2MainSmallWindowGuard(this) { service }
        if (smallWindowGuard.closeIfLaunchedInSmallWindow("create")) return
        binding = ActivityV2MainA7Binding.inflate(layoutInflater)
        setContentView(binding.root)
        previewBinder = V2MainPreviewBinder(binding, mainHandler) { service }
        recordingUi = V2MainRecordingUiController(binding, mainHandler) { recording ->
            lastKnownRecording = recording
        }
        bootAutoRecorder = V2MainBootAutoRecorder(
            mainHandler = mainHandler,
            service = { service },
            isBound = { serviceBinder.isBound },
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
        dateTimeTicker.run()
        recordingUi.updateNormalRecording(lastKnownRecording)
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        if (smallWindowGuard.closeIfLaunchedInSmallWindow("resume")) return
        mainHandler.postDelayed(
            { smallWindowGuard.closeIfLaunchedInSmallWindow("resume-delayed") },
            SMALL_WINDOW_CHECK_DELAY_MS
        )
        smallWindowGuard.restoreMainWindowMode()
        V2AppLog.i("V2MainActivity", "onResume hasPermissions=${hasPermissions()}")
        if (hasPermissions()) continueAfterPermissionsGranted("resume")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) smallWindowGuard.closeIfLaunchedInSmallWindow("focus")
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
        service?.setUiVisibility(false)
        if (::previewBinder.isInitialized) previewBinder.unbindPreviews()
        if (::serviceBinder.isInitialized) serviceBinder.unbind()
        super.onPause()
    }

    override fun onDestroy() {
        V2AppLog.i(
            "V2MainActivity",
            "onDestroy finishing=$isFinishing bound=${::serviceBinder.isInitialized && serviceBinder.isBound}"
        )
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

    private fun handleCameraServiceConnected(cameraService: V2CameraForegroundService?) {
        cameraService?.ensureReadyAfterPermissions()
        cameraService?.setUiStatusListener { status ->
            binding.tvRecordingStats.post {
                binding.tvRecordingStats.text = status
                previewBinder.updatePreviewPlaceholders(service?.isPreviewPausedByAvoidance() == true)
                syncRecordButtonFromService()
            }
        }
        cameraService?.setUiVisibility(true) { moveTaskToBack(true) }
        previewBinder.bindPreviews()
        previewBinder.updatePreviewPlaceholders(service?.isPreviewPausedByAvoidance() == true)
        syncRecordButtonFromService()
        bootAutoRecorder.maybeStart()
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
        if (!startServiceWhenPermissionsGranted && serviceBinder.isBound) return
        startServiceWhenPermissionsGranted = false
        V2AppLog.i("V2MainActivity", "continueAfterPermissionsGranted reason=$reason bound=${serviceBinder.isBound}")
        binding.root.post { serviceBinder.startAndBind() }
    }

}
