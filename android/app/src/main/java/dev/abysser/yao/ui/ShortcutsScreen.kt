package dev.abysser.yao.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.abysser.yao.K
import dev.abysser.yao.Model
import dev.abysser.yao.Shortcut
import dev.abysser.yao.Step

/** What the shortcut page is showing on top of the list. */
private sealed interface Overlay {
    data object Composer : Overlay
    data class Menu(val s: Shortcut) : Overlay
    data class Editor(val s: Shortcut?, val steps: List<Step>, val app: String) : Overlay
}

@Composable
fun ShortcutsScreen(model: Model, onHost: () -> Unit) {
    val library by model.library.collectAsState()
    val window by model.window.collectAsState()
    val view = LocalView.current
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val cls = window?.cls.orEmpty()

    val favorites = library.filter { it.favorite }
    val forApp = library.filter { it.user && cls.isNotEmpty() && it.app.equals(cls, ignoreCase = true) }
    val global = library.filter { it.user && it.app.isEmpty() }
    val hypr = library.filter { !it.user }.groupBy { it.group }

    Column(Modifier.fillMaxSize()) {
        StatusRow(model, onHost)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp).tapTarget("任意组合键") { overlay = Overlay.Composer },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText("任意组合键", style = body, modifier = Modifier.weight(1f))
                    GlyphIcon(Glyphs.chevron, tint = C.muted, size = 18.dp)
                }
            }
            if (favorites.isNotEmpty()) {
                item { Header("收藏") }
                items(favorites) { s -> ShortcutRow(s, { view.tick(); model.run(s.id) }) { overlay = Overlay.Menu(s) } }
            }
            if (cls.isNotEmpty()) {
                item {
                    Header(cls, accent = true, mono = true) {
                        IconButton(Glyphs.plus, "为 $cls 录制快捷键", tint = C.muted) {
                            overlay = Overlay.Editor(null, emptyList(), cls)
                        }
                    }
                }
                if (forApp.isEmpty()) item { Hint("这里还没有 $cls 的快捷键，点 + 录一个") }
                items(forApp) { s -> ShortcutRow(s, { view.tick(); model.run(s.id) }) { overlay = Overlay.Menu(s) } }
            }
            if (global.isNotEmpty()) {
                item { Header("通用") }
                items(global) { s -> ShortcutRow(s, { view.tick(); model.run(s.id) }) { overlay = Overlay.Menu(s) } }
            }
            for ((group, list) in hypr) {
                item { Header(if (group.isEmpty()) "Hyprland" else "Hyprland · $group", mono = false) }
                items(list) { s -> ShortcutRow(s, { view.tick(); model.run(s.id) }) { overlay = Overlay.Menu(s) } }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    when (val o = overlay) {
        null -> {}
        Overlay.Composer -> Composer(
            onDismiss = { overlay = null },
            primary = "发送",
            onPrimary = { codes -> view.tick(); model.chord(codes) },
            secondary = "存为快捷键",
            onSecondary = { codes -> overlay = Overlay.Editor(null, listOf(Step.Chord(codes)), cls) },
        )
        is Overlay.Menu -> Sheet({ overlay = null }) {
            BasicText(o.s.name, style = ts(13.sp, C.muted))
            SheetAction(if (o.s.favorite) "取消收藏" else "收藏") {
                model.favorite(o.s.id, !o.s.favorite)
                overlay = null
            }
            if (o.s.user) {
                SheetAction("编辑") { overlay = Overlay.Editor(o.s, o.s.steps, o.s.app) }
                SheetAction("删除", C.accent) {
                    model.deleteShortcut(o.s.id)
                    overlay = null
                }
            }
        }
        is Overlay.Editor -> Editor(
            model = model,
            existing = o.s,
            initialSteps = o.steps,
            appClass = o.app,
            onDismiss = { overlay = null },
        )
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.items(list: List<T>, content: @Composable (T) -> Unit) {
    items(list.size) { content(list[it]) }
}

@Composable
private fun Header(text: String, accent: Boolean = false, mono: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, style = ts(12.sp, if (accent) C.accent else C.muted, family = if (mono) Mono else null), modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun Hint(text: String) {
    BasicText(text, style = ts(13.sp, C.dim), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutRow(s: Shortcut, onRun: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onMenu,
                onClick = onRun,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText(s.name, style = body, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(s.keys, style = ts(13.sp, C.muted, family = Mono), maxLines = 1)
    }
}

private val modifierKeys = listOf(K.LEFTCTRL to "Ctrl", K.LEFTALT to "Alt", K.LEFTSHIFT to "Shift", K.LEFTMETA to "Super")

private val pickerGroups: List<List<Int>> = listOf(
    "QWERTYUIOPASDFGHJKLZXCVBNM".map { K.letter(it) },
    (1..9).map { K.digit(it) } + K.digit(0),
    (1..12).map { K.f(it) },
    listOf(K.ESC, K.TAB, K.ENTER, K.SPACE, K.BACKSPACE, K.DELETE, K.INSERT, K.HOME, K.END, K.PAGEUP, K.PAGEDOWN,
        K.LEFT, K.UP, K.DOWN, K.RIGHT, K.SYSRQ),
    listOf(K.MINUS, K.EQUAL, K.LEFTBRACE, K.RIGHTBRACE, K.SEMICOLON, K.APOSTROPHE, K.GRAVE, K.BACKSLASH, K.COMMA, K.DOT, K.SLASH),
    listOf(K.PLAYPAUSE, K.PREVIOUSSONG, K.NEXTSONG, K.VOLUMEDOWN, K.VOLUMEUP, K.MUTE),
)

/** Any modifiers plus any one key. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    onDismiss: () -> Unit,
    primary: String,
    onPrimary: (List<Int>) -> Unit,
    secondary: String?,
    onSecondary: (List<Int>) -> Unit,
) {
    val mods = remember { mutableStateListOf<Int>() }
    var key by remember { mutableStateOf<Int?>(null) }
    val codes = modifierKeys.map { it.first }.filter { it in mods } + listOfNotNull(key)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Sheet(onDismiss) {
        BasicText(
            if (codes.isEmpty()) "选修饰键和一个键" else K.chordName(codes) + if (key == null) " + …" else "",
            style = ts(18.sp, if (key != null) C.accent else C.text3, family = Mono),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for ((code, label) in modifierKeys) {
                val on = code in mods
                Box(
                    Modifier.weight(1f).height(44.dp)
                        .background(if (on) C.accent else C.surface, RoundedCornerShape(10.dp))
                        .tapTarget(label) { if (on) mods.remove(code) else mods.add(code) },
                    contentAlignment = Alignment.Center,
                ) { BasicText(label, style = ts(14.sp, if (on) C.onAccent else C.text2, family = Mono)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
            for (group in pickerGroups) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (code in group) {
                        val on = key == code
                        Box(
                            Modifier.widthIn(min = 44.dp).height(44.dp)
                                .background(if (on) C.accent else C.key, RoundedCornerShape(8.dp))
                                .tapTarget(K.name(code)) { key = if (on) null else code }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { BasicText(K.name(code), style = ts(13.sp, if (on) C.onAccent else C.text, family = Mono)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (secondary != null) {
                Box(Modifier.weight(1f).height(48.dp).tapTarget(secondary) { if (key != null) onSecondary(codes) }, contentAlignment = Alignment.Center) {
                    BasicText(secondary, style = ts(15.sp, if (key != null) C.text2 else C.dim))
                }
            }
            AccentButton(primary, Modifier.weight(1f), enabled = key != null) { onPrimary(codes) }
        }
    }
}

/** Create or change a user shortcut: a name, where it shows, and its steps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Editor(model: Model, existing: Shortcut?, initialSteps: List<Step>, appClass: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var forApp by remember { mutableStateOf(existing?.app?.isNotEmpty() ?: appClass.isNotEmpty()) }
    val steps = remember { mutableStateListOf<Step>().apply { addAll(initialSteps) } }
    var picking by remember { mutableStateOf(false) }
    var addingText by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val app = existing?.app?.takeIf { it.isNotEmpty() } ?: appClass
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (existing == null) nameFocus.requestFocus() }

    if (picking) {
        Composer(
            onDismiss = { picking = false },
            primary = "加入",
            onPrimary = { codes -> steps.add(Step.Chord(codes)); picking = false },
            secondary = null,
            onSecondary = {},
        )
        return
    }
    Sheet(onDismiss) {
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = ts(18.sp),
            cursorBrush = SolidColor(C.accent),
            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).padding(vertical = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (name.isEmpty()) BasicText("名字", style = ts(18.sp, C.dim))
                    inner()
                }
            },
        )
        if (app.isNotEmpty()) {
            Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Choice("只在 $app", forApp) { forApp = true }
                Choice("所有窗口", !forApp) { forApp = false }
            }
        }
        Spacer(Modifier.height(4.dp))
        steps.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = when (s) {
                    is Step.Chord -> K.chordName(s.codes)
                    is Step.Text -> "“${s.text}”"
                    is Step.Wait -> "等待 ${s.ms} ms"
                }
                BasicText(
                    label, style = ts(14.sp, C.text2, family = if (s is Step.Text) null else Mono),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(
                        if (s is Step.Wait) Modifier.tapTarget("改等待时长") {
                            val next = listOf(100, 200, 500, 1000, 2000).firstOrNull { it > s.ms } ?: 100
                            steps[i] = Step.Wait(next)
                        } else Modifier,
                    ),
                )
                IconButton(Glyphs.close, "去掉这一步", tint = C.dim) { steps.removeAt(i) }
            }
        }
        if (addingText) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = ts(15.sp),
                    cursorBrush = SolidColor(C.accent),
                    modifier = Modifier.weight(1f).border(1.dp, C.hair, RoundedCornerShape(10.dp)).padding(12.dp),
                )
                Spacer(Modifier.width(8.dp))
                BasicText("加入", style = ts(15.sp, C.accent), modifier = Modifier.padding(12.dp).tapTarget("加入文字") {
                    if (text.isNotEmpty()) steps.add(Step.Text(text))
                    text = ""
                    addingText = false
                })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            BasicText("+ 组合键", style = ts(14.sp, C.text3), modifier = Modifier.height(44.dp).padding(vertical = 12.dp).tapTarget("加组合键") { picking = true })
            BasicText("+ 文字", style = ts(14.sp, C.text3), modifier = Modifier.height(44.dp).padding(vertical = 12.dp).tapTarget("加文字") { addingText = true })
            BasicText("+ 等待", style = ts(14.sp, C.text3), modifier = Modifier.height(44.dp).padding(vertical = 12.dp).tapTarget("加等待") { steps.add(Step.Wait(200)) })
        }
        Spacer(Modifier.height(8.dp))
        AccentButton("保存", Modifier.fillMaxWidth(), enabled = name.isNotBlank() && steps.isNotEmpty()) {
            model.putShortcut(existing?.id, if (forApp) app else "", name.trim(), steps.toList())
            onDismiss()
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.height(44.dp).tapTarget(label, onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (on) C.accent else C.underlineEmpty, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(8.dp))
        BasicText(label, style = ts(14.sp, if (on) C.text else C.muted))
    }
}
