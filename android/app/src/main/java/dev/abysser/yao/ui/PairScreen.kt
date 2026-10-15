package dev.abysser.yao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.abysser.yao.Model
import dev.abysser.yao.PairPhase
import dev.abysser.yao.net.Host
import dev.abysser.yao.net.Proto

@Composable
fun PairScreen(model: Model) {
    val hosts by model.hosts.collectAsState()
    val host by model.pairHost.collectAsState()
    val phase by model.pairPhase.collectAsState()
    val message by model.pairMessage.collectAsState()
    var manual by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(C.ground)
            .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).imePadding(),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText("遥", style = ts(56.sp, C.accent, family = Mark))
            Spacer(Modifier.height(40.dp))
            when {
                host != null -> {
                    BasicText(host!!.name, style = ts(18.sp))
                    Spacer(Modifier.height(28.dp))
                    when (phase) {
                        PairPhase.EnterCode -> {
                            CodeField(onComplete = model::submitCode, resetKey = message)
                            Spacer(Modifier.height(28.dp))
                            BasicText(message ?: "输入电脑通知里的配对码", style = ts(13.sp, if (message != null) C.accent else C.muted))
                        }
                        PairPhase.Failed -> {
                            BasicText(message ?: "连接失败", style = ts(13.sp, C.muted))
                            Spacer(Modifier.height(16.dp))
                            BasicText("重试", style = ts(15.sp, C.accent), modifier = Modifier.padding(12.dp).tapTarget("重试") { model.retryPairing() })
                        }
                        else -> BasicText("正在连接…", style = ts(13.sp, C.muted))
                    }
                }
                hosts.size > 1 -> {
                    BasicText("选择这台电脑", style = ts(13.sp, C.muted))
                    Spacer(Modifier.height(12.dp))
                    hosts.forEach { h ->
                        BasicText(
                            h.name, style = ts(18.sp),
                            modifier = Modifier.height(48.dp).padding(horizontal = 24.dp, vertical = 12.dp).tapTarget(h.name) { model.choose(h) },
                        )
                    }
                }
                else -> BasicText("正在寻找电脑…", style = ts(15.sp, C.muted))
            }
        }
        BasicText(
            "手动输入地址",
            style = ts(13.sp, C.dim),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                .height(44.dp).padding(horizontal = 16.dp, vertical = 12.dp).tapTarget("手动输入地址") { manual = true },
        )
    }
    if (manual) ManualAddress(onDismiss = { manual = false }) { h ->
        manual = false
        model.choose(h)
    }
}

/** Six underlined cells over one hidden numeric field; the sixth digit submits. */
@Composable
private fun CodeField(onComplete: (String) -> Unit, resetKey: Any?) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(resetKey) { if (resetKey != null) code = "" }
    BasicTextField(
        value = code,
        onValueChange = { v ->
            val digits = v.filter { it.isDigit() }.take(6)
            code = digits
            if (digits.length == 6) onComplete(digits)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        cursorBrush = SolidColor(C.ground),
        modifier = Modifier.focusRequester(focus),
        decorationBox = { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (i in 0 until 6) {
                    val ch = code.getOrNull(i)?.toString() ?: ""
                    val line = when {
                        i < code.length -> C.underline
                        i == code.length -> C.accent
                        else -> C.underlineEmpty
                    }
                    Column(Modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(46.dp), contentAlignment = Alignment.Center) {
                            BasicText(ch, style = ts(28.sp, C.text, family = Mono).copy(textAlign = TextAlign.Center))
                        }
                        Box(Modifier.fillMaxWidth().height(2.dp).background(line))
                    }
                }
            }
        },
    )
}

@Composable
private fun ManualAddress(onDismiss: () -> Unit, onSubmit: (Host) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() {
        val t = text.trim()
        if (t.isEmpty()) return
        val (addr, port) = if (t.count { it == ':' } == 1) {
            t.substringBefore(':') to (t.substringAfter(':').toIntOrNull() ?: Proto.PORT)
        } else t to Proto.PORT
        onSubmit(Host(addr, addr, port))
    }
    Sheet(onDismiss) {
        BasicText("电脑的地址", style = ts(13.sp, C.muted))
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = ts(18.sp, family = Mono),
            cursorBrush = SolidColor(C.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                .border(1.dp, C.hair, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) BasicText("192.168.1.10", style = ts(18.sp, C.dim, family = Mono))
                    inner()
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        AccentButton("连接", Modifier.fillMaxWidth(), enabled = text.isNotBlank()) { submit() }
        Spacer(Modifier.size(4.dp))
    }
}
