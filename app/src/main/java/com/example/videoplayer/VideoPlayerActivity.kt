package com.example.videoplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.videoplayer.databinding.ActivityVideoPlayerBinding

/**
 * Fullscreen local video player with looping.
 *
 * - Supports mp4 and other formats supported by ExoPlayer/Media3.
 * - By default plays a file in res/raw named "sample" (e.g. sample.mp4).
 * - You can also pass a local Uri via [EXTRA_VIDEO_URI].
 */
class VideoPlayerActivity : ComponentActivity() {

  private lateinit var binding: ActivityVideoPlayerBinding
  private var player: ExoPlayer? = null

  private val mainHandler = Handler(Looper.getMainLooper())
  private var userSeeking = false
  private val progressUpdater = object : Runnable {
    override fun run() {
      updateProgressUi()
      mainHandler.postDelayed(this, 500L)
    }
  }

  private val playerListener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      updatePlayPauseUi(isPlaying)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      updateProgressUi()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
    setContentView(binding.root)

    enableImmersiveFullscreen()

    binding.playPauseButton.setOnClickListener {
      val p = player ?: return@setOnClickListener
      if (p.isPlaying) p.pause() else p.play()
      updatePlayPauseUi(p.isPlaying)
    }

    binding.progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (!fromUser) return
        // Only seek on stopTracking to avoid spamming seeks.
      }

      override fun onStartTrackingTouch(seekBar: SeekBar) {
        userSeeking = true
      }

      override fun onStopTrackingTouch(seekBar: SeekBar) {
        val p = player ?: return
        userSeeking = false
        p.seekTo(seekBar.progress.toLong())
        updateProgressUi()
      }
    })
  }

  override fun onStart() {
    super.onStart()
    initializePlayerIfNeeded()
    mainHandler.removeCallbacks(progressUpdater)
    mainHandler.post(progressUpdater)
  }

  override fun onStop() {
    super.onStop()
    mainHandler.removeCallbacks(progressUpdater)
    releasePlayer()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) enableImmersiveFullscreen()
  }

  private fun initializePlayerIfNeeded() {
    if (player != null) return

    val exoPlayer = ExoPlayer.Builder(this).build().also { p ->
      p.repeatMode = Player.REPEAT_MODE_ONE // loop current item
      p.playWhenReady = true
    }

    binding.playerView.player = exoPlayer
    player = exoPlayer
    exoPlayer.addListener(playerListener)

    val uri = intent.getParcelableExtra(EXTRA_VIDEO_URI, Uri::class.java)
      ?: defaultRawSampleUri()

    exoPlayer.setMediaItem(
      MediaItem.Builder()
        .setUri(uri)
        .build()
    )
    exoPlayer.prepare()

    updatePlayPauseUi(exoPlayer.isPlaying)
    updateProgressUi()
  }

  private fun releasePlayer() {
    binding.playerView.player = null
    player?.removeListener(playerListener)
    player?.release()
    player = null
  }

  private fun defaultRawSampleUri(): Uri {
    // Put a local video file at: app/src/main/res/raw/sample.mp4 (or sample.mkv, etc)
    // Resource name must be "sample" (no extension in the resource id).
    return Uri.parse("android.resource://$packageName/${R.raw.sample}")
  }

  private fun enableImmersiveFullscreen() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, binding.root).let { controller ->
      controller.hide(WindowInsetsCompat.Type.systemBars())
      controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }

  private fun updatePlayPauseUi(isPlaying: Boolean) {
    val iconRes = if (isPlaying) {
      android.R.drawable.ic_media_pause
    } else {
      android.R.drawable.ic_media_play
    }
    binding.playPauseButton.setImageResource(iconRes)
  }

  private fun updateProgressUi() {
    val p = player ?: return
    val durationMs = p.duration
    val positionMs = p.currentPosition

    if (durationMs == C.TIME_UNSET || durationMs <= 0) {
      binding.progressSeekBar.isEnabled = false
      binding.progressSeekBar.max = 1000
      if (!userSeeking) binding.progressSeekBar.progress = 0
      return
    }

    binding.progressSeekBar.isEnabled = true
    // duration is Long, SeekBar max is Int
    val safeMax = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val safeProgress = positionMs.coerceIn(0, durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    if (binding.progressSeekBar.max != safeMax) binding.progressSeekBar.max = safeMax
    if (!userSeeking) binding.progressSeekBar.progress = safeProgress
  }

  companion object {
    const val EXTRA_VIDEO_URI = "video_uri"

    /**
     * Launch with a local video Uri (e.g. file://, content://).
     *
     * If you use content Uri, ensure you have read permission (FLAG_GRANT_READ_URI_PERMISSION).
     */
    fun intent(context: Context, videoUri: Uri): Intent =
      Intent(context, VideoPlayerActivity::class.java).apply {
        putExtra(EXTRA_VIDEO_URI, videoUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
  }
}

