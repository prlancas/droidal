package com.prlancas.droidal

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.prlancas.droidal.CommandListener.CommandListener
import com.prlancas.droidal.brain.Agent
import com.prlancas.droidal.brain.llm.LiteRtLmEngineCache
import com.prlancas.droidal.brain.llm.LlmProviderFactory
import com.prlancas.droidal.camera.CameraManager
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.debug.DebugHandle
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.OpenSettings
import com.prlancas.droidal.listen.Listen
import com.prlancas.droidal.settings.SettingsActivity
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.speech.Speak
import com.prlancas.droidal.ui.FaceCanvas
import com.prlancas.droidal.ui.FaceController
import kotlinx.coroutines.IO_PARALLELISM_PROPERTY_NAME
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    private lateinit var canvas: FaceCanvas

    private lateinit var ttobj: TextToSpeech
    private lateinit var cameraManager: CameraManager

    // Debug overlays — recreated on first onCreate, visibility toggled on
    // every onResume from SettingsRepository so changes in the settings
    // page apply the next time the user taps Done.
    private lateinit var activityOverlay: TextView
    private lateinit var partialSpeechOverlay: TextView
    private lateinit var debugMenuButton: Button

    private val mainScope = MainScope()

    // Set when we navigate to [SettingsActivity] so the matching
    // [onResume] knows it really does need to drop the cached
    // LiteRT-LM engine — the user may have changed the model,
    // max-num-tokens, etc. and the cache key doesn't reflect those.
    // Left at `false` on initial launch and on returns from non-
    // settings activities (e.g. a screen-off/on cycle) so the engine
    // [MyApplication.onCreate] prewarmed survives.
    private var settingsDirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        hideSystemBars()

        // Reasonable I/O parallelism - not 256!
        System.setProperty(IO_PARALLELISM_PROPERTY_NAME, Runtime.getRuntime().availableProcessors().toString())

        canvas = FaceCanvas(this)
        activityOverlay = buildActivityOverlay()
        partialSpeechOverlay = buildPartialSpeechOverlay()
        debugMenuButton = buildDebugMenuButton()

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
            addView(activityOverlay, buildActivityOverlayLayoutParams())
            addView(partialSpeechOverlay, buildPartialSpeechLayoutParams())
            addView(debugMenuButton, buildDebugMenuButtonLayoutParams())
        }
        setContentView(root)
        FaceController(this, canvas)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyScreenBrightnessPreference()

        subscribeToOpenSettings()
        subscribeToDebugBus()

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

    /**
     * Translucent rounded chip that hovers over [FaceCanvas] showing the
     * current [com.prlancas.droidal.debug.DebugActivityState]. Visibility
     * is controlled by [SettingsRepository.debugActivityOverlayEnabled];
     * the text is bound reactively from [DebugBus.activity].
     */
    private fun buildActivityOverlay(): TextView {
        val padH = (12 * resources.displayMetrics.density).toInt()
        val padV = (6 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(padH, padV, padH, padV)
            background = overlayChipBackground()
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            visibility = View.GONE
        }
    }

    private fun buildActivityOverlayLayoutParams(): FrameLayout.LayoutParams {
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = marginPx
        }
    }

    /**
     * Bottom-of-screen TextView showing the live partial transcript from
     * [com.prlancas.droidal.speech.SpeechToText]. Bound to
     * [DebugBus.partialSpeech].
     */
    private fun buildPartialSpeechOverlay(): TextView {
        val padH = (16 * resources.displayMetrics.density).toInt()
        val padV = (10 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(padH, padV, padH, padV)
            background = overlayChipBackground()
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            maxLines = 3
            visibility = View.GONE
        }
    }

    private fun buildPartialSpeechLayoutParams(): FrameLayout.LayoutParams {
        val marginH = (24 * resources.displayMetrics.density).toInt()
        val marginV = (24 * resources.displayMetrics.density).toInt()
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = marginV
            leftMargin = marginH
            rightMargin = marginH
        }
    }

    /**
     * Top-right "Debug" button — shown only when the user has flipped
     * the toggle in Settings → Debug overlays. Tapping it pops up a
     * menu of expressions / commands which dispatch through
     * [DebugHandle.debugCommand] so the on-screen path mirrors the
     * "debug ..." voice command path exactly.
     */
    private fun buildDebugMenuButton(): Button {
        return Button(this).apply {
            text = "Debug"
            setTextColor(Color.WHITE)
            background = overlayChipBackground()
            setOnClickListener { showDebugPopupMenu(it) }
            visibility = View.GONE
        }
    }

    private fun buildDebugMenuButtonLayoutParams(): FrameLayout.LayoutParams {
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = marginPx
            marginEnd = marginPx
        }
    }

    private fun showDebugPopupMenu(anchor: View) {
        val items = debugMenuItems()
        val menu = PopupMenu(this, anchor)
        items.forEachIndexed { index, item ->
            menu.menu.add(0, index, index, item.label)
        }
        menu.setOnMenuItemClickListener { menuItem ->
            items[menuItem.itemId].action()
            true
        }
        menu.show()
    }

    /**
     * Modal text-entry for the on-canvas "Debug → Set user…" item, the
     * touch equivalent of the spoken `debug set user <name>` command.
     * Dispatches via [DebugHandle.debugCommand] so persistence and the
     * spoken confirmation match the voice path exactly.
     */
    private fun showSetUserDialog() {
        val padPx = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "Name (e.g. Paul)"
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Set current user")
            .setMessage("Overrides face recognition until cleared.")
            .setView(container)
            .setPositiveButton("Set") { dialog, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    DebugHandle.debugCommand("debug set user $name")
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Builds the on-canvas Debug popup. Each entry pairs a human label
     * with the action to dispatch — most are a direct call into
     * [DebugHandle.debugCommand] so the touch UI and the spoken
     * "debug …" path share one handler. "Set user…" needs a free-text
     * name so it routes through [showSetUserDialog] first.
     */
    private fun debugMenuItems(): List<DebugMenuItem> = listOf(
        DebugMenuItem("Look normal") { DebugHandle.debugCommand("debug look normal") },
        DebugMenuItem("Look cute") { DebugHandle.debugCommand("debug look cute") },
        DebugMenuItem("Look sleepy") { DebugHandle.debugCommand("debug look sleepy") },
        DebugMenuItem("Look bloodshot") { DebugHandle.debugCommand("debug look bloodshot") },
        DebugMenuItem("Think") { DebugHandle.debugCommand("debug think") },
        DebugMenuItem("Blink") { DebugHandle.debugCommand("debug blink") },
        DebugMenuItem("Sleep") { DebugHandle.debugCommand("debug sleep") },
        DebugMenuItem("What can you see") { DebugHandle.debugCommand("debug what can you see") },
        DebugMenuItem("Toggle echo back") { DebugHandle.debugCommand("debug echo") },
        DebugMenuItem("Who is current user") { DebugHandle.debugCommand("debug who") },
        DebugMenuItem("Set user…") { showSetUserDialog() },
        DebugMenuItem("Clear current user") { DebugHandle.debugCommand("debug clear user") },
        DebugMenuItem("Open settings") { DebugHandle.debugCommand("debug settings") },
    )

    private data class DebugMenuItem(val label: String, val action: () -> Unit)

    private fun overlayChipBackground(): GradientDrawable {
        val radiusPx = 18f * resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(0xAA000000.toInt())
        }
    }

    private fun applyDebugOverlayVisibility() {
        val settings = runCatching { SettingsRepository.get(applicationContext) }.getOrNull() ?: return
        activityOverlay.visibility = if (settings.debugActivityOverlayEnabled()) View.VISIBLE else View.GONE
        partialSpeechOverlay.visibility = if (settings.debugSpeechOverlayEnabled() &&
            partialSpeechOverlay.text.isNotBlank()) View.VISIBLE else View.GONE
        debugMenuButton.visibility = if (settings.debugMenuButtonEnabled()) View.VISIBLE else View.GONE
    }

    private fun subscribeToDebugBus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    DebugBus.activity.collectLatest { state ->
                        activityOverlay.text = state.label
                    }
                }
                launch {
                    DebugBus.partialSpeech.collectLatest { partial ->
                        partialSpeechOverlay.text = partial
                        // Only show when the user has speech overlay turned
                        // on AND there's something to display — otherwise
                        // the chip lingers as an empty box after STT ends.
                        val show = partial.isNotBlank() &&
                            SettingsRepository.get(applicationContext).debugSpeechOverlayEnabled()
                        partialSpeechOverlay.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }
            }
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
        settingsDirty = true
        startActivity(
            Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        // Only drop the cached LiteRT-LM engine when the user has
        // genuinely returned from [SettingsActivity] — the model
        // selection, max-num-tokens window, or vision toggle may have
        // changed and the cache key doesn't reflect those. On every
        // other onResume (initial launch, screen-off/on, returning
        // from any other activity) we keep whatever
        // [MyApplication.onCreate] / a prior prewarm has already
        // loaded so the first reply doesn't pay the ~12s warm-up.
        if (settingsDirty) {
            LiteRtLmEngineCache.invalidate()
            settingsDirty = false
            // Re-kick the prewarm so the fresh settings start loading
            // in the background while the wake-word loop comes back
            // up — same idea as the load in MyApplication, just for
            // the (re-)configured model.
            runCatching { LlmProviderFactory.prewarmIfLocal(this) }
        }

        Speak.applyVoicePreference()
        applyDebugOverlayVisibility()
        // Nav / status bars can reappear after we pause (e.g. after the
        // settings activity). Re-hide them once we're back in front.
        hideSystemBars()
        // The user may have toggled the brightness override while in
        // settings — re-apply so the next frame uses the new value.
        applyScreenBrightnessPreference()
    }

    /**
     * Honours [SettingsRepository.keepScreenFullBrightness]. When enabled,
     * we override the window's `screenBrightness` to
     * [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL] so the face
     * can't dim — even if Android's auto-brightness curve would normally
     * pull it down in a dark room. When disabled we hand control back to
     * the system with `BRIGHTNESS_OVERRIDE_NONE`.
     *
     * This is paired with the `FLAG_KEEP_SCREEN_ON` set in `onCreate`,
     * which by itself only stops the screen-off timer; it doesn't affect
     * brightness.
     */
    private fun applyScreenBrightnessPreference() {
        val keepFull = runCatching {
            SettingsRepository.get(applicationContext).keepScreenFullBrightness()
        }.getOrDefault(true)
        val attrs = window.attributes
        attrs.screenBrightness = if (keepFull) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = attrs
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
