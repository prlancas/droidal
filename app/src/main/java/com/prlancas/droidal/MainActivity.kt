package com.prlancas.droidal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.prlancas.droidal.CommandListener.CommandListener
import com.prlancas.droidal.brain.Agent
import com.prlancas.droidal.camera.CameraManager
import com.prlancas.droidal.speech.Speak
import com.prlancas.droidal.ui.FaceController
import com.prlancas.droidal.listen.Listen
import com.prlancas.droidal.ui.FaceCanvas

import kotlinx.coroutines.*


class MainActivity : ComponentActivity() {
    private lateinit var canvas:FaceCanvas

    private lateinit var ttobj:TextToSpeech
    private lateinit var cameraManager: CameraManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)

        System.setProperty(IO_PARALLELISM_PROPERTY_NAME, 256.toString())

        canvas = FaceCanvas(this)
        setContentView( canvas)
        FaceController(this, canvas)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        //TODO re enable looking at me
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

        // TODO tmp code
//        lookAbout()

        requestRecordPermission()

        ttobj = TextToSpeech(
            applicationContext
        ) { status ->
            run {
                Speak(ttobj)
                CommandListener
                Agent
//                LLMHandler() // Initialize LLM handler
            }

//        requestCameraPermission()

        }
    }

    private fun createCameraManager() {
//        CameraManager(
//            this,
//            this,
//        )

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
    }
}