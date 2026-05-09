package com.arvideo.viewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arvideo.viewer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Permissions required
    private val neededPermissions: Array<String>
        get() {
            val list = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += Manifest.permission.READ_MEDIA_VIDEO
            } else {
                list += Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return list.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            pickVideo()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { launchAR(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickVideo.setOnClickListener {
            checkPermissionsAndPick()
        }

        binding.btnDemo.setOnClickListener {
            // Launch AR without a video to show orientation demo
            startActivity(Intent(this, ARVideoActivity::class.java))
        }
    }

    private fun checkPermissionsAndPick() {
        val denied = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            pickVideo()
        } else {
            permissionLauncher.launch(denied.toTypedArray())
        }
    }

    private fun pickVideo() {
        videoPickerLauncher.launch("video/*")
    }

    private fun launchAR(uri: Uri) {
        val intent = Intent(this, ARVideoActivity::class.java)
        intent.putExtra(ARVideoActivity.EXTRA_VIDEO_URI, uri.toString())
        startActivity(intent)
    }
}
