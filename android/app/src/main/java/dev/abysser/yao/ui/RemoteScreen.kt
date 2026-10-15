package dev.abysser.yao.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.abysser.yao.K
import dev.abysser.yao.MediaInfo
import dev.abysser.yao.Model
import kotlinx.coroutines.delay
import kotlin.math.sqrt

@Composable
fun RemoteScreen(model: Model, onHost: () -> Unit) {
    val media by model.media.collectAsState()
    Column(Modifier.fillMaxSize()) {
        StatusRow(model, onHost)
        NowPlaying(media)
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            DPad(model)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                TextKey(model, K.ESC, "Esc")
                TextKey(model, K.SPACE, "Space")
                TextKey(model, K.F, "F")
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GlyphKey(model, K.PREVIOUSSONG, Glyphs.prev, "上一首")
                GlyphKey(model, K.PLAYPAUSE, Glyphs.play, "播放或暂停", tint = C.accent)
                GlyphKey(model, K.NEXTSONG, Glyphs.next, "下一首")
                Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(20.dp).background(C.underlineEmpty))
                GlyphKey(model, K.VOLUMEDOWN, Glyphs.volDown, "音量减")
                GlyphKey(model, K.MUTE, Glyphs.mute, "静音")
                GlyphKey(model, K.VOLUMEUP, Glyphs.volUp, "音量加")
            }
        }
        AirPointer(model)
    }
}

@Composable
private fun NowPlaying(media: MediaInfo?) {
    val m = media ?: run {
        Spacer(Modifier.height(20.dp))
        return
    }
    val progress by produceState(0f, m) {
        while (true) {
            val pos = m.positionMs + if (m.playing) SystemClock.elapsedRealtime() - m.at else 0
            value = if (m.lengthMs > 0) (pos.toFloat() / m.lengthMs).coerceIn(0f, 1f) else 0f
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp)) {
        BasicText(m.player, style = ts(12.sp, C.muted))
        Spacer(Modifier.height(6.dp))
        val title = if (m.artist.isNotBlank()) "${m.title} · ${m.artist}" else m.title
        BasicText(title, style = ts(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.surfaceHi)) {
            if (m.lengthMs > 0) Box(Modifier.fillMaxWidth(progress).height(2.dp).background(C.accent))
        }
    }
}

@Composable
private fun DPad(model: Model) {
    Box(Modifier.size(248.dp).background(C.surface, CircleShape)) {
        Arrow(model, K.UP, Glyphs.up, "上", Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
        Arrow(model, K.DOWN, Glyphs.down, "下", Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        Arrow(model, K.LEFT, Glyphs.left, "左", Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
        Arrow(model, K.RIGHT, Glyphs.right, "右", Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
        val pressed = remember { mutableStateOf(false) }
        Box(
            Modifier.align(Alignment.Center).size(84.dp)
                .background(if (pressed.value) C.pressed else C.surfaceHi, CircleShape)
                .keyPress(model, K.ENTER, "确定", pressed),
        )
    }
}

@Composable
private fun Arrow(model: Model, code: Int, glyph: Glyph, label: String, modifier: Modifier) {
    val pressed = remember { mutableStateOf(false) }
    Box(
        modifier.size(68.dp).background(if (pressed.value) C.surfaceHi else Color.Transparent, CircleShape)
            .keyPress(model, code, label, pressed),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, tint = C.text2, size = 26.dp, stroke = 2f) }
}

@Composable
private fun TextKey(model: Model, code: Int, label: String) {
    val pressed = remember { mutableStateOf(false) }
    Box(
        Modifier.widthIn(min = 56.dp).height(44.dp).keyPress(model, code, label, pressed),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = ts(14.sp, if (pressed.value) C.accent else C.text3, family = Mono)) }
}

@Composable
private fun GlyphKey(model: Model, code: Int, glyph: Glyph, label: String, tint: Color = C.text2) {
    val pressed = remember { mutableStateOf(false) }
    Box(
        Modifier.size(48.dp).background(if (pressed.value) C.surfaceHi else Color.Transparent, CircleShape)
            .keyPress(model, code, label, pressed),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, tint = tint, size = 22.dp) }
}

/** Key down while held, key up on release: holding repeats on the computer. */
@Composable
private fun Modifier.keyPress(model: Model, code: Int, label: String, pressed: MutableState<Boolean>): Modifier {
    val view = LocalView.current
    return this
        .semantics { contentDescription = label; role = Role.Button }
        .press(code, pressed, onDown = { view.tick(); model.key(code, true) }, onUp = { model.key(code, false) })
}

/**
 * While held, the phone's turning moves the computer's pointer: turning left and
 * right (about the world's vertical, found from gravity) moves it sideways; tilting
 * the top up and down moves it vertically.
 */
@Composable
private fun AirPointer(model: Model) {
    val context = LocalContext.current
    val view = LocalView.current
    val active = remember { mutableStateOf(false) }
    if (active.value) {
        DisposableEffect(Unit) {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            val g = floatArrayOf(0f, 0f, 9.81f)
            var lastT = 0L
            var restX = 0f
            var restY = 0f
            val gain = 900f
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (e.sensor.type == Sensor.TYPE_GRAVITY) {
                        e.values.copyInto(g, 0, 0, 3)
                        return
                    }
                    if (lastT == 0L) { lastT = e.timestamp; return }
                    val dt = (e.timestamp - lastT) / 1e9f
                    lastT = e.timestamp
                    if (dt <= 0f || dt > 0.1f) return
                    val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(0.1f)
                    val yaw = (e.values[0] * g[0] + e.values[1] * g[1] + e.values[2] * g[2]) / n
                    val pitch = e.values[0]
                    restX += -yaw * dt * gain
                    restY += -pitch * dt * gain
                    val dx = restX.toInt()
                    val dy = restY.toInt()
                    if (dx != 0 || dy != 0) {
                        restX -= dx
                        restY -= dy
                        model.motion(dx, dy)
                    }
                }

                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            }
            grav?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
            gyro?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) }
            onDispose { sm.unregisterListener(listener) }
        }
    }
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .semantics { contentDescription = "按住，挥动手机移动指针"; role = Role.Button }
            .press(Unit, active, onDown = { view.tick() }, onUp = {}),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (active.value) C.accent else C.muted
        GlyphIcon(Glyphs.gyro, Glyphs.gyroArc, tint = tint, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        BasicText("按住，挥动手机移动指针", style = ts(13.sp, tint))
    }
}
