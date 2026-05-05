package com.kooo.evcam.v2.ui.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.kooo.evcam.R
import com.kooo.evcam.databinding.ActivityV2VideoPlaybackBinding
import com.kooo.evcam.v2.storage.V2PlaybackCacheEvents
import com.kooo.evcam.v2.ui.settings.V2SettingsActivity
import java.io.File

class V2VideoPlaybackActivity : AppCompatActivity() {
    private lateinit var binding: ActivityV2VideoPlaybackBinding
    private lateinit var adapter: V2VideoPlaybackAdapter
    private lateinit var contentLoader: V2PlaybackContentLoader
    private lateinit var deletionCoordinator: V2PlaybackDeletionCoordinator
    private lateinit var playerController: V2PlaybackPlayerController
    private var playbackMode = V2PlaybackMode.NORMAL
    private var playbackCacheReceiverRegistered = false

    private val playbackCacheReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != V2PlaybackCacheEvents.ACTION_CHANGED) return
            reloadVideosFromCache()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityV2VideoPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playerController = V2PlaybackPlayerController(
            activity = this,
            binding = binding,
            onSnapshotRequested = ::saveSnapshot,
            onMoveRequested = { Toast.makeText(this, "导出功能待接入", Toast.LENGTH_SHORT).show() },
            onDeleteRequested = ::confirmDeleteCurrentVideo,
        )
        playerController.attach()

        adapter = V2VideoPlaybackAdapter(
            onItemClick = { playerController.play(it) },
            onThumbnailNeeded = { requestThumbnail(it) },
        )
        contentLoader = V2PlaybackContentLoader(
            context = this,
            adapter = adapter,
            playbackMode = { playbackMode },
            onLoadingStarted = {
                adapter.clear()
                binding.emptyText.visibility = View.GONE
            },
            onGroupsLoaded = { groups -> handleGroupsLoaded(groups) },
            onAutoSelect = { group -> playerController.play(group) },
        )
        deletionCoordinator = V2PlaybackDeletionCoordinator(this)

        binding.videoList.layoutManager = GridLayoutManager(this, 4).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = adapter.getSpanSize(position)
            }
        }
        binding.videoList.adapter = adapter
        binding.swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.button_accent))
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.page_background))
        binding.swipeRefresh.setOnRefreshListener { refreshVideos() }
        binding.tabNormalVideo.setOnClickListener { switchMode(V2PlaybackMode.NORMAL) }
        binding.tabEventVideo.setOnClickListener { switchMode(V2PlaybackMode.EVENT) }
        binding.tabPhoto.setOnClickListener { switchMode(V2PlaybackMode.PHOTO) }
        binding.btnHome.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refreshVideos() }
        binding.btnSetting.setOnClickListener { startActivity(Intent(this, V2SettingsActivity::class.java)) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerController.showingPlayer) {
                    playerController.showListMode(stopPlayback = true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        updateTabs()
        loadVideos(autoSelect = false)
    }

    override fun onDestroy() {
        playerController.destroy()
        contentLoader.shutdown()
        deletionCoordinator.shutdown()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        registerPlaybackCacheReceiver()
    }

    override fun onStop() {
        unregisterPlaybackCacheReceiver()
        super.onStop()
    }

    override fun onPause() {
        playerController.pauseForLifecycle()
        super.onPause()
    }

    override fun finish() {
        playerController.exitPlayerImmersive()
        super.finish()
    }

    private fun refreshVideos() {
        playerController.showListMode(stopPlayback = true)
        binding.swipeRefresh.isRefreshing = true
        playerController.clearSelection()
        contentLoader.refreshIncremental {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun registerPlaybackCacheReceiver() {
        if (playbackCacheReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            playbackCacheReceiver,
            IntentFilter(V2PlaybackCacheEvents.ACTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        playbackCacheReceiverRegistered = true
    }

    private fun unregisterPlaybackCacheReceiver() {
        if (!playbackCacheReceiverRegistered) return
        runCatching { unregisterReceiver(playbackCacheReceiver) }
        playbackCacheReceiverRegistered = false
    }

    private fun reloadVideosFromCache() {
        contentLoader.reloadVideosFromCache()
    }

    private fun loadVideos(autoSelect: Boolean, preferCache: Boolean = true) {
        contentLoader.loadVideos(autoSelect, preferCache)
    }

    private fun requestThumbnail(group: V2VideoGroup) {
        contentLoader.requestThumbnail(group)
    }

    private fun handleGroupsLoaded(groups: List<V2VideoGroup>) {
        val empty = groups.isEmpty()
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty && !playerController.showingPlayer) playerController.stopCurrentPlaybackUi()
    }

    private fun confirmDeleteCurrentVideo() {
        val current = playerController.currentVideoForDeletion()
        if (current == null) {
            Toast.makeText(this, "无可删除的视频", Toast.LENGTH_SHORT).show()
            return
        }
        playerController.pausePlayback()
        AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage("确定删除当前视频？删除后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteCurrentVideo(current.group, current.file) }
            .show()
    }

    private fun deleteCurrentVideo(group: V2VideoGroup, file: File) {
        binding.btnPlayerDelete.isEnabled = false
        deletionCoordinator.delete(group, file) { success ->
            binding.btnPlayerDelete.isEnabled = true
            Toast.makeText(this, if (success) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
            if (success) {
                playerController.clearSelection()
                playerController.showListMode(stopPlayback = true)
                loadVideos(autoSelect = false, preferCache = false)
            }
        }
    }

    private fun switchMode(mode: V2PlaybackMode) {
        if (playbackMode == mode) return
        playbackMode = mode
        updateTabs()
        playerController.showListMode(stopPlayback = true)
        playerController.clearSelection()
        contentLoader.clearThumbnailRequests()
        loadVideos(autoSelect = false, preferCache = mode != V2PlaybackMode.PHOTO)
    }

    private fun updateTabs() {
        val selectedBg = R.drawable.v2_playback_tab_checked_bg
        val accent = ContextCompat.getColor(this, R.color.playback_accent)
        val normal = ContextCompat.getColor(this, R.color.text_secondary)

        binding.tabNormalVideo.setBackgroundResource(if (playbackMode == V2PlaybackMode.NORMAL) selectedBg else 0)
        binding.tabEventVideo.setBackgroundResource(if (playbackMode == V2PlaybackMode.EVENT) selectedBg else 0)
        binding.tabPhoto.setBackgroundResource(if (playbackMode == V2PlaybackMode.PHOTO) selectedBg else 0)
        binding.tabNormalVideo.getChildAt(0)?.visibility = if (playbackMode == V2PlaybackMode.NORMAL) View.VISIBLE else View.INVISIBLE
        binding.toolbarTitle.setTextColor(if (playbackMode == V2PlaybackMode.NORMAL) accent else normal)
        binding.tabEventVideoText.setTextColor(if (playbackMode == V2PlaybackMode.EVENT) accent else normal)
        binding.tabPhotoText.setTextColor(if (playbackMode == V2PlaybackMode.PHOTO) accent else normal)
        binding.emptyText.text = when (playbackMode) {
            V2PlaybackMode.NORMAL -> "暂无循环录像"
            V2PlaybackMode.EVENT -> "暂无紧急录像"
            V2PlaybackMode.PHOTO -> "暂无图片"
        }
    }

    private fun saveSnapshot() {
        Toast.makeText(this, "截图已从 Kotlin 媒体路径移除", Toast.LENGTH_SHORT).show()
    }
}
