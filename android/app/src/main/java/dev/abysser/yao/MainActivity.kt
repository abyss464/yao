package dev.abysser.yao

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.abysser.yao.ui.App

class MainActivity : ComponentActivity() {
    private val model: Model by viewModels()
    private var wifiLock: WifiManager.WifiLock? = null
    private var orientation: OrientationEventListener? = null
    private var seenLandscape = false
    private var lastDeg = -1
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val leaveIfUpright = Runnable {
        if (model.tab == Tab.Keyboard && isUpright(lastDeg)) model.tab = model.lastPortraitTab
    }

    private fun isUpright(deg: Int) = deg != -1 && (deg < 25 || deg > 335)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LaunchedEffect(Unit) {
                snapshotFlow { model.tab }.collect { applyTab(it) }
            }
            App(model)
        }
        // Turning the phone back upright leaves the landscape keyboard.
        orientation = object : OrientationEventListener(this) {
            // Called only when the angle changes; whether it stays upright is checked later.
            override fun onOrientationChanged(deg: Int) {
                if (model.tab != Tab.Keyboard || deg == ORIENTATION_UNKNOWN) return
                lastDeg = deg
                if (deg in 60..120 || deg in 240..300) seenLandscape = true
                handler.removeCallbacks(leaveIfUpright)
                if (seenLandscape && isUpright(deg)) handler.postDelayed(leaveIfUpright, 350)
            }
        }
    }

    /** The keyboard is landscape; the touchpad follows the phone; the rest stay upright. */
    private fun applyTab(tab: Tab) {
        when (tab) {
            Tab.Keyboard -> {
                seenLandscape = false
                lastDeg = -1
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                orientation?.enable()
            }
            Tab.Touchpad -> {
                orientation?.disable()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
            else -> {
                orientation?.disable()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        applyBars()
    }

    /** Full screen while the phone is sideways (keyboard, landscape touchpad). */
    private fun applyBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (model.tab == Tab.Keyboard || (model.tab == Tab.Touchpad && landscape)) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBars()
    }

    override fun onStart() {
        super.onStart()
        model.start()
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "yao").apply {
            setReferenceCounted(false)
            acquire()
        }
        if (model.tab == Tab.Keyboard) orientation?.enable()
    }

    override fun onStop() {
        model.stop()
        wifiLock?.release()
        wifiLock = null
        orientation?.disable()
        super.onStop()
    }

    /** On the remote, the phone's volume keys turn the computer's volume. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> K.VOLUMEUP
            KeyEvent.KEYCODE_VOLUME_DOWN -> K.VOLUMEDOWN
            else -> 0
        }
        if (code != 0 && model.tab == Tab.Remote && model.connected.value) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) model.key(code, true)
                KeyEvent.ACTION_UP -> model.key(code, false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
