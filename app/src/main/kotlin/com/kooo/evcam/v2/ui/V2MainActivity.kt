package com.kooo.evcam.v2.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.databinding.ActivityV2MainA7Binding
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.service.V2CameraServiceCommands
import com.kooo.evcam.v2.ui.playback.V2VideoPlaybackActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class V2MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_AUTO_START_FROM_BOOT = "auto_start_from_boot"
        const val EXTRA_SILENT_MODE = "silent_mode"
        private const val BOOT_RECORDING_DELAY_MS = 3_000L
        private const val BOOT_MOVE_BACK_DELAY_MS = 1_500L
        private const val EMERGENCY_RECORDING_DURATION_MS = V2CameraForegroundService.EMERGENCY_RECORDING_DURATION_MS
        @Volatile private var lastKnownRecording = false
    }

    private lateinit var binding: ActivityV2MainA7Binding
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: V2CameraForegroundService? = null
    private var bound = false
    private var bindingService = false
    private val fpsCounters = Array(4) { FpsCounter() }
    private val previewSizeLabels = Array(4) { "--×--" }
    private val previewSurfaces = arrayOfNulls<Surface>(4)
    private val dateTimeFormat = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA)
    private var normalRecordingAnimator: ObjectAnimator? = null
    private var emergencyProgressAnimator: ValueAnimator? = null
    private var recordingDotBlinking = false
    private var recordingDotVisible = true
    private var emergencyRecording = false
    private var emergencyRecordingEndsAtMs = 0L
    private var lastEmergencyRepeatToastMs = 0L
    private var autoStartFromBoot = false
    private var silentMode = false
    private var autoRecordingRequested = false
    private var startServiceWhenPermissionsGranted = false
    private val dateTimeTicker = object : Runnable {
        override fun run() {
            binding.tvDatetime.text = dateTimeFormat.format(Date())
            mainHandler.postDelayed(this, 1000L)
        }
    }
    private val emergencyRecordingTicker = object : Runnable {
        override fun run() {
            updateRecordingPillText()
            if (emergencyRecording) mainHandler.postDelayed(this, 250L)
        }
    }
    private val recordingDotBlinker = object : Runnable {
        override fun run() {
            if (!::binding.isInitialized || binding.tvRecordingPill.visibility != View.VISIBLE) return
            recordingDotVisible = !recordingDotVisible
            binding.recordingDot.alpha = if (recordingDotVisible) 1f else 0f
            mainHandler.postDelayed(this, 650L)
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
                    updatePreviewPlaceholders(service?.isPreviewPausedByAvoidance() == true)
                    syncRecordButtonFromService()
                }
            }
            service?.setUiEmergencyRecordingListener { active, endsAtMs ->
                runOnUiThread { setEmergencyRecordingActive(active, endsAtMs) }
            }
            service?.setUiVisibility(true) { moveTaskToBack(true) }
            bindPreviews()
            updatePreviewPlaceholders(service?.isPreviewPausedByAvoidance() == true)
            syncRecordButtonFromService()
            maybeStartBootRecording()
        }
        override fun onServiceDisconnected(name: ComponentName?) { V2AppLog.w("V2MainActivity", "service disconnected name=$name"); bound = false; bindingService = false; service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        V2AppLog.init(this)
        V2AppLog.i("V2MainActivity", "onCreate")
        binding = ActivityV2MainA7Binding.inflate(layoutInflater)
        setContentView(binding.root)
        consumeBootIntent(intent)
        binding.btnStartRecord.setOnClickListener { toggleRecordingWithToast() }
        binding.btnExit.setOnClickListener {
            startActivity(Intent(this, V2VideoPlaybackActivity::class.java))
        }
        binding.btnClose.setOnClickListener { closeApp() }
        binding.btnVideoPlayback.setOnClickListener { startEmergencyRecordingWithToast() }
        dateTimeTicker.run()
        updateRecordButton(lastKnownRecording)
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        restoreMainWindowMode()
        V2AppLog.i("V2MainActivity", "onResume hasPermissions=${hasPermissions()}")
        if (hasPermissions()) continueAfterPermissionsGranted("resume")
    }

    private fun restoreMainWindowMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
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
        consumeBootIntent(intent)
        maybeStartBootRecording()
    }

    override fun onPause() {
        V2AppLog.i("V2MainActivity", "onPause")
        if (isFinishing) unbindPreviews()
        service?.setUiStatusListener(null)
        service?.setUiEmergencyRecordingListener(null)
        service?.setUiVisibility(false)
        if (bound) { unbindService(connection); bound = false; service = null }
        super.onPause()
    }

    override fun onDestroy() {
        V2AppLog.i("V2MainActivity", "onDestroy finishing=$isFinishing bound=$bound")
        mainHandler.removeCallbacksAndMessages(null)
        stopNormalRecordingAnimation()
        stopEmergencyProgressAnimation()
        stopRecordingDotAnimation()
        if (isFinishing && bound) unbindPreviews()
        V2AppLog.saveToPersistentLog(this)
        super.onDestroy()
    }

    private fun consumeBootIntent(intent: Intent?) {
        val fromBoot = intent?.getBooleanExtra(EXTRA_AUTO_START_FROM_BOOT, false) == true
        if (!fromBoot) return
        autoStartFromBoot = true
        silentMode = intent.getBooleanExtra(EXTRA_SILENT_MODE, false)
        autoRecordingRequested = true
        intent.removeExtra(EXTRA_AUTO_START_FROM_BOOT)
        intent.removeExtra(EXTRA_SILENT_MODE)
        V2AppLog.i("V2MainActivity", "boot auto start intent consumed silent=$silentMode")
    }

    private fun maybeStartBootRecording() {
        if (!autoStartFromBoot || !autoRecordingRequested || !bound) return
        val cameraService = service ?: return
        autoRecordingRequested = false
        V2AppLog.i("V2MainActivity", "schedule boot auto recording alreadyRecording=${cameraService.isRecording()}")
        mainHandler.postDelayed({
            val readyService = service
            if (readyService == null || !bound) {
                V2AppLog.w("V2MainActivity", "boot auto recording skipped: service unavailable")
                autoRecordingRequested = true
                return@postDelayed
            }
            if (!readyService.isRecording()) {
                V2AppLog.i("V2MainActivity", "boot auto recording start")
                readyService.startRecording()
                updateRecordButton(readyService.isRecording())
            }
            mainHandler.postDelayed({
                if (silentMode && service?.isRecording() == true) {
                    V2AppLog.i("V2MainActivity", "boot auto recording active, move task to back")
                    moveTaskToBack(true)
                }
            }, BOOT_MOVE_BACK_DELAY_MS)
        }, BOOT_RECORDING_DELAY_MS)
    }

    private fun closeApp() {
        V2AppLog.w("V2MainActivity", "close app requested")
        service?.shutdownFromUi() ?: V2CameraServiceCommands.stop(this)
        finishAndRemoveTask()
    }

    private fun updateRecordButton(recording: Boolean) {
        lastKnownRecording = recording
        binding.tvRecordingPill.visibility = if (recording || emergencyRecording) View.VISIBLE else View.GONE
        val normalRecording = recording
        binding.btnStartRecord.isChecked = normalRecording
        binding.normalRecordingProgress.visibility = if (normalRecording) View.VISIBLE else View.GONE
        if (normalRecording) startNormalRecordingAnimation() else stopNormalRecordingAnimation()
        binding.btnStartRecord.contentDescription = if (recording) "停止录制" else "开始录制"
        if (recording || emergencyRecording) startRecordingDotAnimation() else stopRecordingDotAnimation()
        updateRecordingPillText()
    }

    private fun setEmergencyRecordingActive(active: Boolean, endsAtMs: Long = 0L) {
        emergencyRecording = active
        binding.btnVideoPlayback.isChecked = active
        binding.btnStartRecord.isEnabled = !active || lastKnownRecording
        binding.emergencyRecordingProgress.visibility = if (active) View.VISIBLE else View.GONE
        stopEmergencyProgressAnimation()
        binding.btnVideoPlayback.contentDescription = if (active) "停止紧急录制" else "紧急录制"
        mainHandler.removeCallbacks(emergencyRecordingTicker)
        if (active) {
            emergencyRecordingEndsAtMs = endsAtMs.takeIf { it > SystemClock.elapsedRealtime() }
                ?: (SystemClock.elapsedRealtime() + EMERGENCY_RECORDING_DURATION_MS)
            startEmergencyProgressAnimation()
            emergencyRecordingTicker.run()
        } else {
            emergencyRecordingEndsAtMs = 0L
            binding.emergencyRecordingProgress.progress = 0f
            updateRecordingPillText()
        }
        binding.tvRecordingPill.visibility = if (lastKnownRecording || active) View.VISIBLE else View.GONE
        updateRecordButton(lastKnownRecording)
    }

    private fun updateRecordingPillText() {
        if (!::binding.isInitialized) return
        binding.tvRecordingStateText.text = if (emergencyRecording) {
            val remainingMs = (emergencyRecordingEndsAtMs - SystemClock.elapsedRealtime())
                .coerceIn(0L, EMERGENCY_RECORDING_DURATION_MS)
            val remainingSeconds = ((remainingMs + 999L) / 1000L)
                .coerceIn(0L, EMERGENCY_RECORDING_DURATION_MS / 1000L)
            "录制中 (${remainingSeconds}s)"
        } else {
            "录制中"
        }
    }

    private fun startNormalRecordingAnimation() {
        if (normalRecordingAnimator?.isStarted == true) return
        normalRecordingAnimator = ObjectAnimator.ofFloat(binding.normalRecordingProgress, View.ROTATION, 0f, 360f).apply {
            duration = 3000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopNormalRecordingAnimation() {
        normalRecordingAnimator?.cancel()
        normalRecordingAnimator = null
        if (::binding.isInitialized) binding.normalRecordingProgress.rotation = 0f
    }

    private fun startEmergencyProgressAnimation() {
        val remainingMs = (emergencyRecordingEndsAtMs - SystemClock.elapsedRealtime())
            .coerceIn(0L, EMERGENCY_RECORDING_DURATION_MS)
        val progress = 1f - (remainingMs.toFloat() / EMERGENCY_RECORDING_DURATION_MS.toFloat())
        binding.emergencyRecordingProgress.progress = progress
        emergencyProgressAnimator = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = remainingMs
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                binding.emergencyRecordingProgress.progress = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun stopEmergencyProgressAnimation() {
        emergencyProgressAnimator?.cancel()
        emergencyProgressAnimator = null
    }

    private fun startRecordingDotAnimation() {
        if (recordingDotBlinking) return
        recordingDotBlinking = true
        mainHandler.removeCallbacks(recordingDotBlinker)
        recordingDotVisible = true
        binding.recordingDot.alpha = 1f
        mainHandler.postDelayed(recordingDotBlinker, 650L)
    }

    private fun stopRecordingDotAnimation() {
        recordingDotBlinking = false
        mainHandler.removeCallbacks(recordingDotBlinker)
        recordingDotVisible = true
        if (::binding.isInitialized) binding.recordingDot.alpha = 1f
    }

    private fun syncRecordButtonFromService() {
        service?.let { updateRecordButton(it.isRecording()) }
    }

    private fun toggleRecordingWithToast() {
        val cameraService = service
        if (cameraService == null) {
            V2AppLog.w("V2MainActivity", "toggle recording skipped: service null")
            Toast.makeText(this, "相机服务启动中", Toast.LENGTH_SHORT).show()
            return
        }
        val wasRecording = cameraService.isRecording()
        val isRecording = cameraService.toggleRecording()
        V2AppLog.i("V2MainActivity", "toggle recording was=$wasRecording now=$isRecording")
        updateRecordButton(isRecording)
        val message = when {
            !wasRecording && isRecording -> "开始录制"
            wasRecording && !isRecording -> "停止录制"
            !wasRecording && !isRecording -> "录制启动失败"
            else -> if (isRecording) "正在录制" else "已停止录制"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startEmergencyRecordingWithToast() {
        if (emergencyRecording) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmergencyRepeatToastMs > 1_500L) {
                lastEmergencyRepeatToastMs = now
                val remainingSeconds = ((emergencyRecordingEndsAtMs - now + 999L) / 1000L).coerceAtLeast(0L)
                Toast.makeText(this, "紧急录制中（${remainingSeconds}s）", Toast.LENGTH_SHORT).show()
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
            runOnUiThread { setEmergencyRecordingActive(active) }
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

    private fun bindPreviews() {
        val sizeViews = listOf(binding.fpsFront, binding.fpsBack, binding.fpsLeft, binding.fpsRight)
        listOf(binding.textureFront, binding.textureBack, binding.textureLeft, binding.textureRight).forEachIndexed { index, texture ->
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    V2AppLog.i("V2MainActivity", "preview surface available index=$index size=${width}x$height")
                    fpsCounters[index].reset()
                    previewSizeLabels[index] = service?.previewInputSizeLabel(index) ?: "--×--"
                    sizeViews[index].text = "${previewSizeLabels[index]}\n-- fps"
                    attachPreviewSurface(index, surface)
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean { V2AppLog.i("V2MainActivity", "preview surface destroyed index=$index"); detachPreviewSurface(index); return true }
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                    fpsCounters[index].onFrame()?.let { fps -> sizeViews[index].text = "${previewSizeLabels[index]}\n$fps fps" }
                }
            }
            if (texture.isAvailable && texture.surfaceTexture != null) attachPreviewSurface(index, texture.surfaceTexture!!)
        }
    }

    private fun attachPreviewSurface(index: Int, surfaceTexture: android.graphics.SurfaceTexture) {
        detachPreviewSurface(index)
        val surface = Surface(surfaceTexture)
        previewSurfaces[index] = surface
        V2AppLog.d("V2MainActivity", "attachPreviewSurface index=$index valid=${surface.isValid}")
        service?.attachPreviewSurface(index, surface)
        updatePreviewPlaceholders(service?.isPreviewPausedByAvoidance() == true)
        previewSizeLabels[index] = service?.previewInputSizeLabel(index) ?: "--×--"
        listOf(binding.fpsFront, binding.fpsBack, binding.fpsLeft, binding.fpsRight).getOrNull(index)?.text = "${previewSizeLabels[index]}\n-- fps"
    }

    private fun detachPreviewSurface(index: Int) {
        V2AppLog.d("V2MainActivity", "detachPreviewSurface index=$index hadSurface=${previewSurfaces[index] != null}")
        service?.detachPreviewSurface(index)
        previewSurfaces[index]?.release()
        previewSurfaces[index] = null
    }

    private fun unbindPreviews() { repeat(4) { detachPreviewSurface(it) } }

    private fun updatePreviewPlaceholders(paused: Boolean) {
        val visibility = if (paused) View.VISIBLE else View.GONE
        listOf(
            binding.previewPlaceholderFront,
            binding.previewPlaceholderBack,
            binding.previewPlaceholderLeft,
            binding.previewPlaceholderRight
        ).forEach { it.visibility = visibility }
    }

    private class FpsCounter {
        private var frames = 0
        private var startedMs = 0L

        fun reset() {
            frames = 0
            startedMs = SystemClock.elapsedRealtime()
        }

        fun onFrame(): Int? {
            val now = SystemClock.elapsedRealtime()
            if (startedMs == 0L) startedMs = now
            frames += 1
            val elapsed = now - startedMs
            if (elapsed < 1000L) return null
            val value = ((frames * 1000f) / elapsed).roundToInt().coerceAtLeast(1)
            frames = 0
            startedMs = now
            return value
        }
    }
}
