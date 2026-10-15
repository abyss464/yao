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
    Box(
        modifier
            .onSizeChanged {
                size = it.width to it.height
                val dm = view.resources.displayMetrics
                model.touchConfig(it.width, it.height, (dm.xdpi / 25.4f).roundToInt(), (dm.ydpi / 25.4f).roundToInt())
            }
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
 * Typing through the phone's own input method. The field mirrors onto the computer:
 * whatever the input method commits (not the text it is still composing) is typed
 * there, and deletions become backspaces. Enter sends Enter and starts afresh.
 */
@Composable
private fun TextBar(model: Model, onClose: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var sent by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun committed(v: TextFieldValue): String {
        val c = v.composition ?: return v.text
        return v.text.substring(0, c.min) + v.text.substring(c.max)
    }

    fun mirror(now: String) {
        val p = sent.commonPrefixWith(now).length
        val removed = sent.substring(p)
        repeat(removed.codePointCount(0, removed.length)) { model.tap(K.BACKSPACE) }
        val added = now.substring(p)
        if (added.isNotEmpty()) model.text(added)
        sent = now
    }

    fun enter() {
        model.tap(K.ENTER)
        value = TextFieldValue("")
        sent = ""
    }

    fun backspace() {
        if (value.composition == null && value.text.isNotEmpty()) {
            val t = value.text.substring(0, value.text.offsetByCodePoints(value.text.length, -1))
            value = TextFieldValue(t, TextRange(t.length))
            sent = t
        }
        model.tap(K.BACKSPACE)
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { v ->
                value = v
                mirror(committed(v))
            },
            singleLine = true,
            textStyle = ts(15.sp),
            cursorBrush = SolidColor(C.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSend = { enter() }),
            modifier = Modifier.weight(1f).height(48.dp).focusRequester(focus)
                // A backspace with nothing left in the field still deletes on the computer.
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && value.text.isEmpty()) {
                        model.tap(K.BACKSPACE)
                        true
                    } else false
                }
                .background(C.surface, RoundedCornerShape(14.dp))
                .border(1.dp, C.hair, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) BasicText("输入文字，上屏即发送", style = ts(15.sp, C.muted))
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        IconButton(Glyphs.backspace, "退格", size = 48) { backspace() }
        IconButton(Glyphs.enter, "回车", size = 48) { enter() }
    }
}
