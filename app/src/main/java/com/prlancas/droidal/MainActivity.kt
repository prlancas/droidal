package com.prlancas.droidal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.prlancas.droidal.CommandListener.CommandListener
import com.prlancas.droidal.brain.Agent
import com.prlancas.droidal.brain.llm.LiteRtLmEngineCache
import com.prlancas.droidal.camera.CameraManager
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.OpenSettings
import com.prlancas.droidal.settings.SettingsActivity
import com.prlancas.droidal.speech.Speak
import com.prlancas.droidal.ui.FaceController
import com.prlancas.droidal.listen.Listen
import com.prlancas.droidal.ui.FaceCanvas

import kotlinx.coroutines.*


class MainActivity : ComponentActivity() {
    private lateinit var canvas:FaceCanvas

    private lateinit var ttobj:TextToSpeech
    private lateinit var cameraManager: CameraManager

    private val mainScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        hideSystemBars()

        // Reasonable I/O parallelism - not 256!
        System.setProperty(IO_PARALLELISM_PROPERTY_NAME, Runtime.getRuntime().availableProcessors().toString())

        canvas = FaceCanvas(this)

        // Wrap the face in a FrameLayout so we can overlay a small cog button
        // in the top-left without disturbing FaceCanvas's drawing. (Top-left
        // avoids clashing with the gesture/back-button area on the right.)
        val root = FrameLayout(this).apply {
            addView(
                canvas,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(buildSettingsCog(), buildCogLayoutParams())
        }
        setContentView(root)
        FaceController(this, canvas)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        subscribeToOpenSettings()

        createCameraManager()
        if (allPermissionsGranted()) {
            cameraManager.startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        requestRecordPermission()

        ttobj = TextToSpeech(
            applicationContext
        ) { status ->
            run {
                Speak(ttobj)
                CommandListener
                Agent
            }
        }
    }

    private fun buildSettingsCog(): ImageButton {
        return ImageButton(this).apply {
            setImageResource(R.drawable.ic_cog)
            background = null
            alpha = COG_IDLE_ALPHA
            contentDescription = getString(R.string.settings_button_description)
            setOnClickListener { openSettings() }
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> alpha = 1.0f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alpha = COG_IDLE_ALPHA
                }
                false
            }
        }
    }

    private fun buildCogLayoutParams(): FrameLayout.LayoutParams {
        val sizePx = (48 * resources.displayMetrics.density).toInt()
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        return FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = marginPx
            marginStart = marginPx
        }
    }

    private fun subscribeToOpenSettings() {
        mainScope.launch {
            EventBus.subscribe<OpenSettings> {
                runOnUiThread { openSettings() }
            }
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        // Settings may have been changed while we were paused (the user just
        // came back from SettingsActivity). Drop any cached LiteRT-LM engine
        // so the next conversation rebuilds it against the new selection,
        // and re-apply the TTS voice preference.
        LiteRtLmEngineCache.invalidate()
        Speak.applyVoicePreference()
        // Nav / status bars can reappear after we pause (e.g. after the
        // settings activity). Re-hide them once we're back in front.
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Swiping the bars into view is transient — as soon as we're the
        // focused window again, clamp the face screen back to fullscreen.
        if (hasFocus) hideSystemBars()
    }

    /**
     * Immersive-sticky: hides both the status bar and the 3-button /
     * gesture navigation bar so nothing covers [FaceCanvas]. The bars
     * briefly reappear on a swipe from an edge but snap back after a
     * timeout.
     */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }

    private fun createCameraManager() {
        cameraManager = CameraManager(
            this,
            this,
        )
    }




    private fun requestRecordPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf<String>(Manifest.permission.RECORD_AUDIO),
            0
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                cameraManager.startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT)
                    .show()
                finish()
            }
        }
        else {

            if (grantResults.size == 0 ||
                grantResults[0] == PackageManager.PERMISSION_DENIED
            ) {
                // handle permission denied
            } else {
                Listen.init(this, applicationContext)
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA//,
//            android.Manifest.permission.READ_EXTERNAL_STORAGE,
//            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        private const val COG_IDLE_ALPHA = 0.35f
    }
}
