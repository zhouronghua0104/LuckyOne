package com.example.videoplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
    setContentView(binding.root)

    enableImmersiveFullscreen()
  }

  override fun onStart() {
    super.onStart()
    initializePlayerIfNeeded()
  }

  override fun onStop() {
    super.onStop()
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

    val uri = intent.getParcelableExtra(EXTRA_VIDEO_URI, Uri::class.java)
      ?: defaultRawSampleUri()

    exoPlayer.setMediaItem(
      MediaItem.Builder()
        .setUri(uri)
        .build()
    )
    exoPlayer.prepare()
  }

  private fun releasePlayer() {
    binding.playerView.player = null
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

