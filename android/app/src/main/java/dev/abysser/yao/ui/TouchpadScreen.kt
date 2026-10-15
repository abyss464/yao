package dev.abysser.yao.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.abysser.yao.K
import dev.abysser.yao.Model
import kotlin.math.roundToInt

@Composable
fun TouchpadScreen(model: Model, onHost: () -> Unit) {
    var typing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        StatusRow(model, onHost) {
            IconButton(Glyphs.type, "打字", tint = if (typing) C.accent else C.text3) { typing = !typing }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp)
                .clip(RoundedCornerShape(28.dp)).background(C.surface),
        ) {
            Surface(model, Modifier.weight(1f).fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.hair))
            Row(Modifier.fillMaxWidth().height(76.dp)) {
                ClickZone(model, K.BTN_LEFT, "左键", Modifier.weight(1f))
                Box(Modifier.width(1.dp).fillMaxHeight().background(C.hair))
                ClickZone(model, K.BTN_RIGHT, "右键", Modifier.weight(1f))
            }
        }
        if (typing) TextBar(model, onClose = { typing = false }) else Spacer(Modifier.height(0.dp))
    }
}

/** The touch surface: every finger goes to the computer as-is. */
@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun Surface(model: Model, modifier: Modifier) {
    val view = LocalView.current
    val density = LocalDensity.current
    var size by remember { mutableStateOf(0 to 0) }
    // The size settles over an animation (the code keyboard closing): tell the
    // computer once it has stopped changing, so its touchpad is made once.
    LaunchedEffect(size) {
        if (size.first <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        val dm = view.resources.displayMetrics
        model.touchConfig(size.first, size.second, (dm.xdpi / 25.4f).roundToInt(), (dm.ydpi / 25.4f).roundToInt())
    }
    Box(
        modifier
            // Strokes that start near the screen edge stay on the touchpad instead of
            // becoming the system's Back gesture.
            .systemGestureExclusion()
            .onSizeChanged { size = it.width to it.height }
            .pointerInteropFilter { ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(ev)
                sendTouches(model, ev, size)
                true
            },
    )
}

private fun sendTouches(model: Model, ev: MotionEvent, size: Pair<Int, Int>) {
    val (w, h) = size
    if (w <= 0 || h <= 0) return
    fun clampX(x: Float) = x.roundToInt().coerceIn(0, w - 1)
    fun clampY(y: Float) = y.roundToInt().coerceIn(0, h - 1)
    val action = ev.actionMasked
    if (action == MotionEvent.ACTION_CANCEL) {
        model.touch(ev.eventTime, emptyList())
        return
    }
    // Samples batched into this event, oldest first.
    for (hIdx in 0 until ev.historySize) {
        val pts = (0 until ev.pointerCount).map { i ->
            Triple(ev.getPointerId(i), clampX(ev.getHistoricalX(i, hIdx)), clampY(ev.getHistoricalY(i, hIdx)))
        }
        model.touch(ev.getHistoricalEventTime(hIdx), pts)
    }
    val lifting = when (action) {
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> ev.actionIndex
        else -> -1
    }
    val pts = (0 until ev.pointerCount).filter { it != lifting }.map { i ->
        Triple(ev.getPointerId(i), clampX(ev.getX(i)), clampY(ev.getY(i)))
    }
    model.touch(ev.eventTime, pts)
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
 * (see [ImeBridgeView]). The bar shows what is still being composed.
 */
@Composable
private fun TextBar(model: Model, onClose: () -> Unit) {
    var composing by remember { mutableStateOf("") }
    var bridge by remember { mutableStateOf<ImeBridgeView?>(null) }
    BackHandler {
        bridge?.hide()
        onClose()
    }
    LaunchedEffect(bridge) { bridge?.show() }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).height(48.dp)
                .background(C.surface, RoundedCornerShape(14.dp))
                .border(1.dp, C.hair, RoundedCornerShape(14.dp)),
            contentAlignment = androidx.compose.ui.Alignment.CenterStart,
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
