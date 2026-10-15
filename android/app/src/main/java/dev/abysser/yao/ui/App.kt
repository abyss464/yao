package dev.abysser.yao.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.abysser.yao.Model
import dev.abysser.yao.Tab

@Composable
fun App(model: Model) {
    val pairing by model.pairing.collectAsState()
    Box(Modifier.fillMaxSize().background(C.ground)) {
        if (pairing == null) PairScreen(model) else Main(model)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Main(model: Model) {
    var settings by remember { mutableStateOf(false) }
    val openSettings = { settings = true }
    if (model.tab == Tab.Keyboard) {
        KeyboardScreen(model)
        return
    }
    // Back never leaves the app from its main pages: on a touchpad an edge swipe is
    // far more often a stroke than a request to quit.
    BackHandler {}
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Box(Modifier.weight(1f)) {
            when (model.tab) {
                Tab.Touchpad -> TouchpadScreen(model, openSettings)
                Tab.Remote -> RemoteScreen(model, openSettings)
                Tab.Shortcuts -> ShortcutsScreen(model, openSettings)
                Tab.Keyboard -> {}
            }
        }
        // While the phone's input method is up, it is the bottom of the screen.
        if (!WindowInsets.isImeVisible) {
            NavBar(model.tab) { t ->
                if (t != Tab.Keyboard) model.lastPortraitTab = t
                model.tab = t
            }
        }
    }
    if (settings) Settings(model) { settings = false }
}

@Composable
private fun Settings(model: Model, onDismiss: () -> Unit) {
    val pairing by model.pairing.collectAsState()
    val connected by model.connected.collectAsState()
    val rtt by model.rtt.collectAsState()
    val p = pairing ?: return
    Sheet(onDismiss) {
        BasicText(p.name, style = ts(18.sp))
        BasicText("${p.address}:${p.port}", style = ts(13.sp, C.muted, family = Mono))
        BasicText(if (connected) "已连接 · $rtt ms" else "连接中…", style = ts(13.sp, C.muted))
        BasicText("证书 " + p.pin.take(16).chunked(4).joinToString(" "), style = ts(12.sp, C.dim, family = Mono))
        Spacer(Modifier.height(8.dp))
        SheetAction("忘记这台电脑", C.accent) {
            onDismiss()
            model.forget()
        }
    }
}
