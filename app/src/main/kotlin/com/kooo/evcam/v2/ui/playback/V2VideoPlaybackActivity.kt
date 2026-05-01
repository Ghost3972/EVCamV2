package com.kooo.evcam.v2.ui.playback

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.kooo.evcam.R
import com.kooo.evcam.databinding.ActivityV2VideoPlaybackBinding
import com.kooo.evcam.v2.storage.V2PlaybackCacheMaintainer
import com.kooo.evcam.v2.ui.settings.V2SettingsActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class V2VideoPlaybackActivity : AppCompatActivity() {
    private lateinit var binding: ActivityV2VideoPlaybackBinding
    private lateinit var adapter: V2VideoPlaybackAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private val thumbnailExecutor = Executors.newSingleThreadExecutor()
    private val progressHandler = Handler(Looper.getMainLooper())
    private val requestedThumbnailKeys = mutableSetOf<String>()
    private var selected: V2VideoGroup? = null
    private var pendingVideo: File? = null
    private var userSeeking = false
    private var showingPlayer = false
    private var playerUiVisible = true
    private var playerSystemTopInset = 0
    private var playerSystemBottomInset = 0
    private val playerUiInterpolator = AccelerateDecelerateInterpolator()
    private val playerTitleDateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
    private val playerTitleTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    @Volatile private var loadGeneration = 0
    private val progressUpdater = object : Runnable {
        override fun run() {
            if (!userSeeking) {
                val pos = binding.videoFront.currentPosition.coerceAtLeast(0)
                binding.currentTime.text = formatTime(pos)
                binding.seekBar.progress = pos.coerceAtMost(binding.seekBar.max.coerceAtLeast(1))
            }
            progressHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityV2VideoPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = V2VideoPlaybackAdapter(
            onItemClick = { playVideo(it) },
            onThumbnailNeeded = { requestThumbnail(it) },
        )
        binding.videoList.layoutManager = GridLayoutManager(this, 4).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = adapter.getSpanSize(position)
            }
        }
        binding.videoList.adapter = adapter
        binding.swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.button_accent))
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.page_background))
        binding.swipeRefresh.setOnRefreshListener { refreshVideos() }
        binding.tabNormalVideo.setOnClickListener { showListMode(stopPlayback = true) }
        binding.btnHome.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refreshVideos() }
        binding.btnSetting.setOnClickListener { startActivity(Intent(this, V2SettingsActivity::class.java)) }
        binding.btnPlayPause.setOnClickListener { togglePlayback() }
        binding.btnPlayerBack.setOnClickListener { showListMode(stopPlayback = true) }
        binding.btnSnapshot.setOnClickListener { Toast.makeText(this, "截图功能待接入", Toast.LENGTH_SHORT).show() }
        binding.btnPlayerMove.setOnClickListener { Toast.makeText(this, "导出功能待接入", Toast.LENGTH_SHORT).show() }
        binding.btnPlayerDelete.setOnClickListener { Toast.makeText(this, "删除功能待接入", Toast.LENGTH_SHORT).show() }
        binding.gridContainer.setOnClickListener { togglePlayerUi() }
        binding.videoFront.setOnClickListener { togglePlayerUi() }
        binding.placeholderFront.setOnClickListener { togglePlayerUi() }
        installPlayerInsetsListener()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingPlayer) {
                    showListMode(stopPlayback = true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.currentTime.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val target = seekBar?.progress ?: 0
                binding.videoFront.seekTo(target)
                binding.currentTime.text = formatTime(target)
                userSeeking = false
            }
        })
        binding.videoFront.setOnErrorListener { _, _, _ -> showPlaybackError("视频无法播放或文件未完成: ${pendingVideo?.name ?: "未知文件"}"); true }
        loadVideos(autoSelect = false)
    }

    override fun onDestroy() {
        exitPlayerImmersive()
        stopProgressUpdater()
        binding.videoFront.stopPlayback()
        loadGeneration += 1
        executor.shutdownNow()
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onPause() { exitPlayerImmersive(); pauseAll(); super.onPause() }

    private fun refreshVideos() {
        showListMode(stopPlayback = true)
        binding.swipeRefresh.isRefreshing = true
        selected = null
        pendingVideo = null
        loadVideos(autoSelect = false, preferCache = false)
    }

    private fun loadVideos(autoSelect: Boolean, preferCache: Boolean = true) {
        val generation = ++loadGeneration
        requestedThumbnailKeys.clear()
        adapter.clear()
        binding.emptyText.visibility = View.GONE
        var autoSelected = false
        executor.execute {
            if (preferCache) {
                val cachedGroups = V2VideoScanner.loadCachedGroups(this)
                runOnUiThread {
                    if (generation != loadGeneration) return@runOnUiThread
                    adapter.replaceAll(cachedGroups)
                    if (autoSelect && !autoSelected) {
                        cachedGroups.firstOrNull()?.let {
                            autoSelected = true
                            playVideo(it)
                        }
                    }
                    val empty = cachedGroups.isEmpty()
                    binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
                    if (empty) stopCurrentPlaybackUi()
                    binding.swipeRefresh.isRefreshing = false
                }
                V2PlaybackCacheMaintainer.scheduleRefresh(this)
                return@execute
            }

            V2PlaybackCacheMaintainer.refreshNow(this)
            val refreshedGroups = V2VideoScanner.loadCachedGroups(this)
            runOnUiThread {
                if (generation != loadGeneration) return@runOnUiThread
                adapter.replaceAll(refreshedGroups)
                val empty = refreshedGroups.isEmpty()
                binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
                if (empty) stopCurrentPlaybackUi()
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun requestThumbnail(group: V2VideoGroup) {
        if (group.thumbnail != null || group.composite == null) return
        if (!requestedThumbnailKeys.add(group.timestamp)) return
        loadThumbnailAsync(loadGeneration, group)
    }

    private fun loadThumbnailAsync(generation: Int, group: V2VideoGroup) {
        thumbnailExecutor.execute {
            val thumbnail = V2VideoScanner.cachedThumbnailPath(group.thumbnailPath)
                ?: group.composite?.let { V2VideoScanner.videoFrameThumbnail(this, it) }
                ?: return@execute
            runOnUiThread {
                if (generation != loadGeneration) return@runOnUiThread
                adapter.updateThumbnail(group.timestamp, thumbnail)
            }
        }
    }

    private fun stopCurrentPlaybackUi() {
        stopProgressUpdater()
        binding.videoFront.stopPlayback()
        binding.placeholderFront.visibility = View.VISIBLE
        setPlaybackButtonState(playing = false)
        binding.currentTime.text = "00:00"
        binding.totalTime.text = "00:00"
        binding.seekBar.progress = 0
        binding.seekBar.max = 100
    }

    private fun showListMode(stopPlayback: Boolean, animate: Boolean = true) {
        val wasShowingPlayer = showingPlayer
        showingPlayer = false
        binding.swipeRefresh.isEnabled = true
        binding.currentDatetime.visibility = View.GONE

        val listChrome = listOf(binding.toolbar, binding.btnRefresh, binding.btnSetting, binding.contentLayout)
        listChrome.forEach {
            it.animate().cancel()
            it.visibility = View.VISIBLE
        }
        binding.listContainer.visibility = View.VISIBLE

        if (animate && wasShowingPlayer && binding.previewContainer.visibility == View.VISIBLE) {
            showPlayerSystemBarsKeepingLayout()
            listChrome.forEach {
                it.alpha = 0f
                it.animate()
                    .alpha(1f)
                    .setStartDelay(90L)
                    .setDuration(300L)
                    .setInterpolator(playerUiInterpolator)
                    .start()
            }
            binding.previewContainer.animate().cancel()
            binding.previewContainer.animate()
                .alpha(0f)
                .scaleX(0.985f)
                .scaleY(0.985f)
                .setDuration(380L)
                .setInterpolator(playerUiInterpolator)
                .withEndAction {
                    binding.previewContainer.visibility = View.GONE
                    binding.previewContainer.alpha = 1f
                    binding.previewContainer.scaleX = 1f
                    binding.previewContainer.scaleY = 1f
                    setPlayerUiVisible(visible = false, animate = false)
                    exitPlayerImmersive()
                    if (stopPlayback) stopCurrentPlaybackUi()
                }
                .start()
            return
        }

        exitPlayerImmersive()
        if (stopPlayback) stopCurrentPlaybackUi()
        listChrome.forEach { it.alpha = 1f }
        binding.previewContainer.visibility = View.GONE
        binding.previewContainer.alpha = 1f
        binding.previewContainer.scaleX = 1f
        binding.previewContainer.scaleY = 1f
        setPlayerUiVisible(visible = false, animate = false)
    }

    private fun showPlayerMode() {
        showingPlayer = true
        enterPlayerFullscreenLayout(showBars = true)
        binding.swipeRefresh.isRefreshing = false
        binding.swipeRefresh.isEnabled = false
        val listChrome = listOf(binding.toolbar, binding.btnRefresh, binding.btnSetting, binding.contentLayout)
        listChrome.forEach { it.animate().cancel() }
        binding.previewContainer.visibility = View.VISIBLE
        binding.previewContainer.bringToFront()
        binding.previewContainer.alpha = 0f
        binding.previewContainer.scaleX = 0.985f
        binding.previewContainer.scaleY = 0.985f
        setPlayerUiVisible(visible = true, animate = false)
        binding.previewContainer.animate().cancel()
        binding.previewContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(360L)
            .setInterpolator(playerUiInterpolator)
            .withEndAction {
                if (showingPlayer) {
                    listChrome.forEach {
                        it.visibility = View.GONE
                        it.alpha = 1f
                    }
                }
            }
            .start()
    }

    private fun playVideo(group: V2VideoGroup) {
        selected = group
        showPlayerMode()
        updatePlayerTitle(group)
        val primary = group.composite
        binding.placeholderFront.visibility = if (primary == null) View.VISIBLE else View.GONE
        playFile(binding.videoFront, primary, true)
    }

    private fun playFile(view: android.widget.VideoView, file: File?, primary: Boolean) {
        if (file == null || !file.isFile || !file.canRead() || file.length() <= 0L) {
            pendingVideo = null
            view.stopPlayback()
            binding.placeholderFront.visibility = View.VISIBLE
            return
        }
        pendingVideo = file
        binding.placeholderFront.visibility = View.GONE
        setPlaybackButtonState(playing = false)
        view.setVideoURI(Uri.fromFile(file))
        view.setOnPreparedListener { player ->
            player.isLooping = false
            if (primary) {
                binding.totalTime.text = formatTime(player.duration)
                binding.seekBar.max = player.duration.coerceAtLeast(1)
                binding.seekBar.progress = 0
                binding.currentTime.text = "00:00"
                startProgressUpdater()
            }
            player.start()
            if (primary) setPlaybackButtonState(playing = true)
        }
        view.setOnCompletionListener {
            if (primary) {
                setPlaybackButtonState(playing = false)
                stopProgressUpdater()
                binding.seekBar.progress = binding.seekBar.max
                binding.currentTime.text = binding.totalTime.text
            }
        }
    }

    private fun showPlaybackError(message: String) {
        binding.videoFront.stopPlayback()
        stopProgressUpdater()
        binding.placeholderFront.visibility = View.VISIBLE
        setPlaybackButtonState(playing = false)
        binding.currentTime.text = "00:00"
        binding.totalTime.text = "00:00"
        binding.seekBar.progress = 0
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun togglePlayback() {
        if (binding.videoFront.isPlaying) {
            pauseAll()
        } else {
            startAll()
        }
    }

    private fun updatePlayerTitle(group: V2VideoGroup) {
        val parsed = V2VideoScanner.parseTimestamp(group.timestamp)
        if (parsed != null) {
            binding.playerTitleDate.text = playerTitleDateFormat.format(parsed)
            binding.playerTitleTime.text = playerTitleTimeFormat.format(parsed)
        } else {
            binding.playerTitleDate.text = group.displayDate
            binding.playerTitleTime.text = group.displayTime
        }
    }

    private fun togglePlayerUi() {
        if (!showingPlayer) return
        setPlayerUiVisible(!playerUiVisible, animate = true)
    }

    private fun setPlayerUiVisible(visible: Boolean, animate: Boolean) {
        playerUiVisible = visible
        if (showingPlayer) {
            if (visible) showPlayerSystemBarsKeepingLayout() else hidePlayerSystemBars()
        }

        val targets = listOf(binding.playerTitleBar, binding.btnSnapshot, binding.controlsLayout)
        targets.forEach { it.animate().cancel() }

        if (!animate) {
            val visibility = if (visible) View.VISIBLE else View.GONE
            targets.forEach {
                it.alpha = if (visible) 1f else 0f
                it.translationY = 0f
                it.visibility = visibility
            }
            return
        }

        if (visible) {
            targets.forEach {
                it.visibility = View.VISIBLE
                it.alpha = 0f
            }
            binding.playerTitleBar.translationY = -(binding.playerTitleBar.height + playerSystemTopInset).toFloat()
            binding.btnSnapshot.translationY = 0f
            binding.controlsLayout.translationY = (binding.controlsLayout.height + playerSystemBottomInset).toFloat()
            animatePlayerOverlay(binding.playerTitleBar, alpha = 1f, translationY = 0f)
            animatePlayerOverlay(binding.btnSnapshot, alpha = 1f, translationY = 0f)
            animatePlayerOverlay(binding.controlsLayout, alpha = 1f, translationY = 0f)
        } else {
            animatePlayerOverlay(binding.playerTitleBar, alpha = 0f, translationY = -(binding.playerTitleBar.height + playerSystemTopInset).toFloat(), hideAfter = true)
            animatePlayerOverlay(binding.btnSnapshot, alpha = 0f, translationY = 0f, hideAfter = true)
            animatePlayerOverlay(binding.controlsLayout, alpha = 0f, translationY = (binding.controlsLayout.height + playerSystemBottomInset).toFloat(), hideAfter = true)
        }
    }

    private fun installPlayerInsetsListener() {
        binding.previewContainer.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            if (top > 0 || bottom > 0 || playerUiVisible) {
                applyPlayerOverlayInsets(top, bottom)
            }
            insets
        }
    }

    private fun applyPlayerOverlayInsets(top: Int, bottom: Int) {
        playerSystemTopInset = top
        playerSystemBottomInset = bottom
        updateConstraintMargins(binding.playerTitleBar, top = top)
        updateConstraintMargins(binding.controlsLayout, bottom = bottom)
        updateConstraintMargins(binding.btnSnapshot, top = top, bottom = bottom)
    }

    private fun updateConstraintMargins(view: View, top: Int? = null, bottom: Int? = null) {
        val params = view.layoutParams as? ConstraintLayout.LayoutParams ?: return
        var changed = false
        if (top != null && params.topMargin != top) {
            params.topMargin = top
            changed = true
        }
        if (bottom != null && params.bottomMargin != bottom) {
            params.bottomMargin = bottom
            changed = true
        }
        if (changed) view.layoutParams = params
    }

    private fun animatePlayerOverlay(
        view: View,
        alpha: Float,
        translationY: Float,
        hideAfter: Boolean = false,
    ) {
        view.animate()
            .alpha(alpha)
            .translationY(translationY)
            .setDuration(480L)
            .setInterpolator(playerUiInterpolator)
            .withEndAction {
                if (hideAfter && !playerUiVisible) {
                    view.visibility = View.GONE
                    view.translationY = 0f
                }
            }
            .start()
    }

    private fun enterPlayerFullscreenLayout(showBars: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController ?: return
            if (showBars) {
                controller.show(WindowInsets.Type.systemBars())
            } else {
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (showBars) {
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
        binding.previewContainer.post { binding.previewContainer.requestApplyInsets() }
    }

    private fun hidePlayerSystemBars() {
        enterPlayerFullscreenLayout(showBars = false)
    }

    private fun showPlayerSystemBarsKeepingLayout() {
        enterPlayerFullscreenLayout(showBars = true)
    }

    private fun exitPlayerImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
            window.setDecorFitsSystemWindows(true)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun setPlaybackButtonState(playing: Boolean) {
        binding.btnPlayPause.setImageResource(if (playing) R.drawable.v2_player_ic_pause else R.drawable.v2_player_ic_play)
        binding.btnPlayPause.contentDescription = if (playing) "暂停" else "播放"
    }

    private fun pauseAll() {
        if (binding.videoFront.isPlaying) binding.videoFront.pause()
        stopProgressUpdater()
        setPlaybackButtonState(playing = false)
    }
    private fun startAll() {
        binding.videoFront.start()
        startProgressUpdater()
        setPlaybackButtonState(playing = true)
    }
    private fun startProgressUpdater() { progressHandler.removeCallbacks(progressUpdater); progressHandler.post(progressUpdater) }
    private fun stopProgressUpdater() { progressHandler.removeCallbacks(progressUpdater) }
    private fun formatTime(ms: Int): String = String.format(java.util.Locale.getDefault(), "%02d:%02d", (ms.coerceAtLeast(0) / 1000) / 60, (ms.coerceAtLeast(0) / 1000) % 60)
}
