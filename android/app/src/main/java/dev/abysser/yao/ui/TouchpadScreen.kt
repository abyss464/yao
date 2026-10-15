package dev.abysser.yao.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.abysser.yao.K
import dev.abysser.yao.Model
import dev.abysser.yao.net.VideoStream
import kotlin.math.roundToInt

/**
 * The touchpad follows the phone: upright it sits under the status row with the
 * click strip below; turned sideways it takes the whole screen, room enough for the
 * computer's three- and four-finger gestures.
 */
@Composable
fun TouchpadScreen(model: Model, onHost: () -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Workspaces(model)
            }
            Pad(model, Modifier.weight(1f).fillMaxWidth(), stripHeight = 64)
        }
        return
    }
    var typing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        StatusRow(model, onHost) {
            Workspaces(model)
            IconButton(Glyphs.type, "打字", tint = if (typing) C.accent else C.text3) { typing = !typing }
        }
        Pad(
            model, Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
            stripHeight = 76, video = typing,
        )
        if (typing) TextBar(model, onClose = { typing = false })
    }
}

/** The surface and its click strip; while typing, the surface shows the target window. */
@Composable
private fun Pad(model: Model, modifier: Modifier, stripHeight: Int, video: Boolean = false) {
    Column(modifier.clip(RoundedCornerShape(28.dp)).background(C.surface)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (video) VideoView(model, Modifier.fillMaxSize())
            Surface(model, Modifier.matchParentSize())
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.hair))
        Row(Modifier.fillMaxWidth().height(stripHeight.dp)) {
            ClickZone(model, K.BTN_LEFT, "左键", Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(C.hair))
            ClickZone(model, K.BTN_RIGHT, "右键", Modifier.weight(1f))
        }
    }
}

/** The computer's workspaces; the current one lit, a tap goes to another. */
@Composable
fun Workspaces(model: Model) {
    val list by model.workspaces.collectAsState()
    val active by model.activeWorkspace.collectAsState()
    val view = LocalView.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (id in list) {
            Box(
                Modifier.widthIn(min = 36.dp).height(44.dp).tapTarget("工作区 $id") {
                    view.tick()
                    model.workspace(id)
                },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(id.toString(), style = ts(13.sp, if (id == active) C.accent else C.dim, family = Mono))
            }
        }
    }
}

/**
 * The touch surface: every finger goes to the computer as-is, scaled by the speed
 * setting (a faster touchpad is a bigger one: pointer, scrolling and gestures all
 * travel further for the same finger movement). With three or more fingers down the
 * count shows, so a gesture the computer acts on is seen to have landed.
 */
@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun Surface(model: Model, modifier: Modifier) {
    val view = LocalView.current
    var size by remember { mutableStateOf(0 to 0) }
    var fingers by remember { mutableIntStateOf(0) }
    val speed = model.touchSpeed
    // The size settles over an animation (a keyboard closing, a rotation): tell the
    // computer once it has stopped changing, so its touchpad is made once.
    LaunchedEffect(size, speed) {
        if (size.first <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        val dm = view.resources.displayMetrics
        model.touchConfig(
            (size.first * speed).roundToInt(), (size.second * speed).roundToInt(),
            (dm.xdpi / 25.4f).roundToInt(), (dm.ydpi / 25.4f).roundToInt(),
        )
    }
    Box(
        modifier
            // Strokes that start near the screen edge stay on the touchpad instead of
            // becoming the system's Back gesture.
            .systemGestureExclusion()
            .onSizeChanged { size = it.width to it.height }
            .pointerInteropFilter { ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(ev)
                fingers = sendTouches(model, ev, size, speed)
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        if (fingers >= 3) BasicText(fingers.toString(), style = ts(56.sp, C.accent.copy(alpha = 0.35f), family = Mono))
    }
}

/**
 * The live picture of the window the text goes to, fitted into the space and drawn
 * under the touch surface (the touchpad keeps working over it).
 */
@Composable
private fun VideoView(model: Model, modifier: Modifier) {
    var aspect by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        var stream: VideoStream? = null
                        var surface: android.view.Surface? = null

                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val sf = android.view.Surface(st)
                            surface = sf
                            stream = model.openVideo(sf, maxW, maxH) { vw, vh -> post { aspect = vw.toFloat() / vh } }
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            stream?.close()
                            surface?.release()
                            return true
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = aspect?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
        )
    }
}

/** Sends the fingers in [ev]; returns how many stay down. */
private fun sendTouches(model: Model, ev: MotionEvent, size: Pair<Int, Int>, speed: Float): Int {
    val (w, h) = size
    if (w <= 0 || h <= 0) return 0
    fun sx(x: Float) = (x.coerceIn(0f, (w - 1).toFloat()) * speed).roundToInt()
    fun sy(y: Float) = (y.coerceIn(0f, (h - 1).toFloat()) * speed).roundToInt()
    val action = ev.actionMasked
    if (action == MotionEvent.ACTION_CANCEL) {
        model.touch(ev.eventTime, emptyList())
        return 0
    }
    // Samples batched into this event, oldest first.
    for (hIdx in 0 until ev.historySize) {
        val pts = (0 until ev.pointerCount).map { i ->
            Triple(ev.getPointerId(i), sx(ev.getHistoricalX(i, hIdx)), sy(ev.getHistoricalY(i, hIdx)))
        }
        model.touch(ev.getHistoricalEventTime(hIdx), pts)
    }
    val lifting = when (action) {
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> ev.actionIndex
        else -> -1
    }
    val pts = (0 until ev.pointerCount).filter { it != lifting }.map { i ->
        Triple(ev.getPointerId(i), sx(ev.getX(i)), sy(ev.getY(i)))
    }
    model.touch(ev.eventTime, pts)
    return pts.size
}

@Composable
private fun ClickZone(model: Model, button: Int, label: String, modifier: Modifier) {
    val view = LocalView.current
    val pressed = remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxHeight()
            .background(if (pressed.value) C.surfaceHi else C.surface)
            .semantics { contentDescription = label; role = Role.Button }
            .press(button, pressed, onDown = { view.tick(); model.button(button, true) }, onUp = { model.button(button, false) }),
    )
}

/**
 * Typing through the phone's own input method, bridged straight to the computer
 * (see [ImeBridgeView]). Above it: the window the text goes to (with focus following
 * the mouse, the one under the pointer). The bar shows what is still being composed.
 */
@Composable
private fun TextBar(model: Model, onClose: () -> Unit) {
    var composing by remember { mutableStateOf("") }
    var bridge by remember { mutableStateOf<ImeBridgeView?>(null) }
    val window by model.window.collectAsState()
    BackHandler {
        bridge?.hide()
        onClose()
    }
    LaunchedEffect(bridge) { bridge?.show() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        val target = window?.let { w -> if (w.title.isBlank()) w.cls else "${w.cls} · ${w.title}" } ?: "没有聚焦的窗口"
        BasicText(
            "→ $target",
            style = ts(12.sp, C.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(48.dp)
                    .background(C.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, C.hair, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.CenterStart,
            ) {
                AndroidView(
                    factory = { ctx ->
                        ImeBridgeView(ctx, model) { composing = it }.also { v ->
                            v.setOnClickListener { v.show() }
                            bridge = v
                        }
                    },
                    modifier = Modifier.matchParentSize(),
                )
                BasicText(
                    if (composing.isEmpty()) "用手机输入法打字，上屏即发送" else composing,
                    style = if (composing.isEmpty()) ts(15.sp, C.muted) else ts(15.sp, C.accent),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(Glyphs.backspace, "退格", size = 48) { model.tap(K.BACKSPACE) }
            IconButton(Glyphs.enter, "回车", size = 48) { model.tap(K.ENTER) }
        }
    }
}
