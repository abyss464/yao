package dev.abysser.yao.ui

import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.abysser.yao.K
import dev.abysser.yao.Leds
import dev.abysser.yao.Model

private enum class Kind { Key, Mod, Gap, Toggle }

private class Item(val code: Int, val label: String, val shifted: String? = null, val units: Float = 1f, val kind: Kind = Kind.Key)

private fun gap(units: Float) = Item(0, "", units = units, kind = Kind.Gap)
private fun mod(code: Int, label: String, units: Float) = Item(code, label, units = units, kind = Kind.Mod)
private fun keys(labels: String, codes: List<Int>) = labels.split(' ').zip(codes) { l, c -> Item(c, l) }
private fun sym(base: String, shifted: String, code: Int) = Item(code, base, shifted)

private val mainRows: List<List<Item>> = listOf(
    listOf(Item(K.ESC, "Esc"), gap(1f)) + (1..4).map { Item(K.f(it), "F$it") } + gap(0.5f) +
        (5..8).map { Item(K.f(it), "F$it") } + gap(0.5f) + (9..12).map { Item(K.f(it), "F$it") },
    listOf(sym("`", "~", K.GRAVE)) +
        "1!2@3#4$5%6^7&8*9(0)".chunked(2).mapIndexed { i, p -> sym(p[0].toString(), p[1].toString(), K.digit((i + 1) % 10)) } +
        listOf(sym("-", "_", K.MINUS), sym("=", "+", K.EQUAL), Item(K.BACKSPACE, "Backspace", units = 2f)),
    listOf(Item(K.TAB, "Tab", units = 1.5f)) + "QWERTYUIOP".map { Item(K.letter(it), it.toString()) } +
        listOf(sym("[", "{", K.LEFTBRACE), sym("]", "}", K.RIGHTBRACE), Item(K.BACKSLASH, "\\", "|", 1.5f)),
    listOf(Item(K.CAPSLOCK, "Caps", units = 1.75f)) + "ASDFGHJKL".map { Item(K.letter(it), it.toString()) } +
        listOf(sym(";", ":", K.SEMICOLON), sym("'", "\"", K.APOSTROPHE), Item(K.ENTER, "Enter", units = 2.25f)),
    listOf(mod(K.LEFTSHIFT, "Shift", 2.25f)) + "ZXCVBNM".map { Item(K.letter(it), it.toString()) } +
        listOf(sym(",", "<", K.COMMA), sym(".", ">", K.DOT), sym("/", "?", K.SLASH), mod(K.RIGHTSHIFT, "Shift", 2.75f)),
    listOf(
        mod(K.LEFTCTRL, "Ctrl", 1.25f), mod(K.LEFTMETA, "Super", 1.25f), mod(K.LEFTALT, "Alt", 1.25f),
        Item(K.SPACE, "", units = 6.25f),
        mod(K.RIGHTALT, "Alt", 1.25f), mod(K.RIGHTMETA, "Super", 1.25f), Item(K.COMPOSE, "Menu", units = 1.25f),
        mod(K.RIGHTCTRL, "Ctrl", 1.25f),
    ),
)

private val navRows: List<List<Item>> = listOf(
    listOf(Item(K.SYSRQ, "PrtSc"), Item(K.SCROLLLOCK, "ScrLk"), Item(K.PAUSE, "Pause"), Item(0, "小键盘", kind = Kind.Toggle)),
    listOf(gap(0.5f), Item(K.INSERT, "Ins"), Item(K.HOME, "Home"), Item(K.PAGEUP, "PgUp"), gap(0.5f)),
    listOf(gap(0.5f), Item(K.DELETE, "Del"), Item(K.END, "End"), Item(K.PAGEDOWN, "PgDn"), gap(0.5f)),
    listOf(gap(4f)),
    listOf(gap(1.5f), Item(K.UP, "↑"), gap(1.5f)),
    listOf(gap(0.5f), Item(K.LEFT, "←"), Item(K.DOWN, "↓"), Item(K.RIGHT, "→"), gap(0.5f)),
)

private val numRows: List<List<Item>> = listOf(
    listOf(Item(K.NUMLOCK, "NumLk"), Item(K.KPSLASH, "/"), Item(K.KPASTERISK, "*"), Item(0, "导航", kind = Kind.Toggle)),
    listOf(Item(K.kp(7), "7"), Item(K.kp(8), "8"), Item(K.kp(9), "9"), Item(K.KPMINUS, "-")),
    listOf(Item(K.kp(4), "4"), Item(K.kp(5), "5"), Item(K.kp(6), "6"), Item(K.KPPLUS, "+")),
    listOf(Item(K.kp(1), "1"), Item(K.kp(2), "2"), Item(K.kp(3), "3"), Item(K.KPENTER, "Enter")),
    listOf(Item(K.kp(0), "0"), Item(K.KPDOT, "."), Item(K.UP, "↑"), gap(1f)),
    listOf(gap(1f), Item(K.LEFT, "←"), Item(K.DOWN, "↓"), Item(K.RIGHT, "→")),
)

private enum class ModState { Pressed, OneShot, PressedAgain, Locked, Unlocking }

private class ModInfo(val state: ModState, val used: Boolean = false, val since: Long = 0L)

/**
 * Sticky modifiers. A tap applies the modifier to the next key, a double tap locks it
 * until it is tapped again, and holding it while pressing other keys works like a
 * physical modifier. A modifier stays down on the computer for as long as it applies.
 */
private class Board(val model: Model, val view: View) {
    val mods = mutableStateMapOf<Int, ModInfo>()
    var numpad by mutableStateOf(false)
    val shift get() = mods.keys.any { K.isShift(it) }

    fun down(item: Item) {
        view.tick()
        when (item.kind) {
            Kind.Mod -> modDown(item.code)
            Kind.Key -> {
                for ((c, m) in mods.toMap()) {
                    if (m.state == ModState.Pressed || m.state == ModState.PressedAgain) mods[c] = ModInfo(m.state, true, m.since)
                }
                model.key(item.code, true)
            }
            else -> {}
        }
    }

    fun up(item: Item) {
        when (item.kind) {
            Kind.Mod -> modUp(item.code)
            Kind.Key -> {
                model.key(item.code, false)
                for ((c, m) in mods.toMap()) if (m.state == ModState.OneShot) release(c)
            }
            Kind.Toggle -> numpad = !numpad
            Kind.Gap -> {}
        }
    }

    private fun modDown(code: Int) {
        val m = mods[code]
        when (m?.state) {
            null -> {
                model.key(code, true)
                mods[code] = ModInfo(ModState.Pressed)
            }
            ModState.OneShot -> mods[code] = ModInfo(ModState.PressedAgain, false, m.since)
            ModState.Locked -> mods[code] = ModInfo(ModState.Unlocking)
            else -> {}
        }
    }

    private fun modUp(code: Int) {
        val m = mods[code] ?: return
        val now = SystemClock.uptimeMillis()
        when (m.state) {
            ModState.Pressed -> if (m.used) release(code) else mods[code] = ModInfo(ModState.OneShot, since = now)
            ModState.PressedAgain ->
                if (!m.used && now - m.since < 450) mods[code] = ModInfo(ModState.Locked) else release(code)
            ModState.Unlocking -> release(code)
            else -> {}
        }
    }

    private fun release(code: Int) {
        model.key(code, false)
        mods.remove(code)
    }

    fun releaseAll() {
        for (c in mods.keys.toList()) release(c)
    }
}

@Composable
fun KeyboardScreen(model: Model) {
    val view = LocalView.current
    val board = remember { Board(model, view) }
    val leds by model.leds.collectAsState()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { board.releaseAll() } }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(C.ground)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 15.dp, vertical = 12.dp),
    ) {
        val gapW = 5.dp
        val unit = (maxWidth + gapW) / 19.5f
        val rowGap = 6.dp
        val avail = maxHeight - rowGap * 5
        val fRow = avail * (46f / 336f)
        val row = avail * (58f / 336f)
        val right = if (board.numpad) numRows else navRows
        Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
            for (r in 0 until 6) {
                val h = if (r == 0) fRow else row
                Row(Modifier.height(h), horizontalArrangement = Arrangement.spacedBy(gapW)) {
                    for (item in mainRows[r]) Cap(item, board, leds, unit, gapW, h)
                    Spacer(Modifier.width(unit * 0.5f - gapW))
                    for (item in right[r]) Cap(item, board, leds, unit, gapW, h)
                }
            }
        }
    }
}

@Composable
private fun Cap(item: Item, board: Board, leds: Leds, unit: Dp, gapW: Dp, h: Dp) {
    val w = unit * item.units - gapW
    if (item.kind == Kind.Gap) {
        Spacer(Modifier.width(w).height(h))
        return
    }
    val pressed = remember(item) { mutableStateOf(false) }
    val mod = board.mods[item.code]?.state
    val bg = when {
        mod == ModState.Locked -> C.accent
        pressed.value -> C.pressed
        item.kind == Kind.Mod || item.kind == Kind.Toggle -> C.keyMod
        else -> C.key
    }
    val fg = when {
        mod == ModState.Locked -> C.onAccent
        mod != null -> C.accent
        item.kind == Kind.Toggle -> C.text3
        item.kind == Kind.Mod -> C.text2
        else -> C.text
    }
    val label = if (board.shift && item.shifted != null) item.shifted else item.label
    val outline = mod != null && mod != ModState.Locked
    Box(
        Modifier.width(w).height(h)
            .background(bg, RoundedCornerShape(8.dp))
            .then(if (outline) Modifier.border(1.5.dp, C.accent, RoundedCornerShape(8.dp)) else Modifier)
            .semantics { contentDescription = if (item.code == K.SPACE) "空格" else label; role = Role.Button }
            .press(item, pressed, onDown = { board.down(item) }, onUp = { board.up(item) }),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = ts(if (label.length > 1) 11.sp else 15.sp, fg, family = if (label.any { it.code > 0x2E80 }) null else Mono)
                .copy(textAlign = TextAlign.Center),
        )
        val lit = (item.code == K.CAPSLOCK && leds.caps) || (item.code == K.NUMLOCK && leds.num)
        if (lit) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(5.dp).background(C.accent, CircleShape))
    }
}
