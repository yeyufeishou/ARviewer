package com.arvideo.viewer

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.arvideo.viewer.camera.CameraHelper
import com.arvideo.viewer.databinding.ActivityArVideoBinding
import com.arvideo.viewer.gl.ARVideoRenderer
import com.arvideo.viewer.sensor.SensorHelper

class ARVideoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
    }

    private lateinit var binding: ActivityArVideoBinding
    private lateinit var renderer: ARVideoRenderer
    private lateinit var sensorHelper: SensorHelper
    private lateinit var cameraHelper: CameraHelper
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        binding = ActivityArVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorHelper = SensorHelper(this)
        cameraHelper = CameraHelper(this)

        setupGlSurface()
        setupCamera()
        setupButtons()

        val uriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (uriStr != null) {
            // Video URI will be connected after GL surface is ready (see renderer callback)
            pendingVideoUri = Uri.parse(uriStr)
        } else {
            binding.tvHint.text = getString(R.string.hint_no_video)
            binding.tvHint.visibility = View.VISIBLE
        }
    }

    private var pendingVideoUri: Uri? = null

    private fun setupGlSurface() {
        val glView = binding.glSurfaceView
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)  // RGBA8 with alpha

        renderer = ARVideoRenderer(this, sensorHelper)
        renderer.onVideoSurfaceReady = { surface ->
            // Called on main thread; attach ExoPlayer here
            pendingVideoUri?.let { uri -> attachPlayer(surface, uri) }
        }
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Make GLSurfaceView transparent so camera TextureView shows through
        glView.setZOrderOnTop(true)
        glView.holder.setFormat(PixelFormat.TRANSLUCENT)
    }

    private fun setupCamera() {
        val tv = binding.cameraTextureView
        if (tv.isAvailable) {
            cameraHelper.openCamera(tv)
        } else {
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    cameraHelper.openCamera(tv)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnReset.setOnClickListener {
            sensorHelper.calibrate()
            showHint(getString(R.string.hint_reset))
        }
    }

    private fun attachPlayer(surface: Surface, uri: Uri) {
        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        player.setVideoSurface(surface)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                renderer.updateAspectRatio(size.width, size.height)
                // Rebuild GL geometry with real aspect ratio
                binding.glSurfaceView.queueEvent {
                    renderer.onSurfaceChanged(null, binding.glSurfaceView.width, binding.glSurfaceView.height)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    binding.tvHint.visibility = View.GONE
                }
            }
        })
        player.prepare()
        player.play()
    }

    private fun showHint(msg: String) {
        binding.tvHint.text = msg
        binding.tvHint.visibility = View.VISIBLE
        binding.tvHint.postDelayed({ binding.tvHint.visibility = View.GONE }, 1500)
    }

    override fun onResume() {
        super.onResume()
        sensorHelper.start()
        binding.glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        sensorHelper.stop()
        binding.glSurfaceView.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        cameraHelper.release()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }
}
