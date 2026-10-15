package dev.abysser.yao.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.abysser.yao.Model
import dev.abysser.yao.Tab

fun View.tick() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

/**
 * Press-and-release for keys and buttons: [onDown] when a finger lands, [onUp] when
 * that finger lifts, wherever it has moved. Each element tracks its own finger, so
 * several can be held at once.
 */
fun Modifier.press(key: Any?, pressed: MutableState<Boolean>? = null, onDown: () -> Unit, onUp: () -> Unit) =
    pointerInput(key) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            first.consume()
            pressed?.value = true
            onDown()
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == first.id } ?: break
                c.consume()
                if (!c.pressed) break
            }
            pressed?.value = false
            onUp()
        }
    }

/** A tap target without ripple. */
fun Modifier.tapTarget(label: String? = null, onClick: () -> Unit) = this
    .semantics {
        role = Role.Button
        if (label != null) contentDescription = label
    }
    .clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)

@Composable
fun StatusRow(model: Model, onHost: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    val pairing by model.pairing.collectAsState()
    val connected by model.connected.collectAsState()
    val rtt by model.rtt.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.height(44.dp).tapTarget("连接与设置", onHost),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(if (connected) C.accent else C.dim, CircleShape))
            Spacer(Modifier.width(8.dp))
            val extra = when {
                !connected -> " · 连接中"
                rtt > 30 -> " · $rtt ms"
                else -> ""
            }
            BasicText((pairing?.name ?: "") + extra, style = ts(13.sp, C.muted))
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun NavBar(current: Tab, onSelect: (Tab) -> Unit) {
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
            .height(64.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val items = listOf(
            Tab.Touchpad to (Glyphs.touchpad to "触控板"),
            Tab.Remote to (Glyphs.remote to "遥控器"),
            Tab.Keyboard to (Glyphs.keyboard to "键盘"),
            Tab.Shortcuts to (Glyphs.bolt to "快捷键"),
        )
        for ((tab, g) in items) {
            Box(
                Modifier.weight(1f).height(56.dp).tapTarget(g.second) {
                    view.tick()
                    onSelect(tab)
                },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(g.first, tint = if (tab == current) C.accent else C.dim)
            }
        }
    }
}

@Composable
fun IconButton(glyph: Glyph, label: String, tint: Color = C.text3, size: Int = 44, onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier.size(size.dp).tapTarget(label) {
            view.tick()
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, tint = tint, size = 22.dp) }
}

/** A panel over the whole screen, anchored at the bottom; tap outside or Back closes it. */
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setDimAmount(0f)
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        SheetBody(onDismiss, content)
    }
}

@Composable
private fun SheetBody(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).tapTarget(null, onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(C.sheet, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/** A row in a sheet: text that does something. */
@Composable
fun SheetAction(label: String, color: Color = C.text, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp).tapTarget(label, onClick), contentAlignment = Alignment.CenterStart) {
        BasicText(label, style = ts(15.sp, color))
    }
}

@Composable
fun AccentButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp)
            .background(if (enabled) C.accent else C.surfaceHi, RoundedCornerShape(14.dp))
            .tapTarget(label) { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = ts(15.sp, if (enabled) C.onAccent else C.dim)) }
}

@Composable
fun BoxScope.Hairline(modifier: Modifier = Modifier) {
    Box(modifier.background(C.hair))
}
