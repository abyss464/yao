package dev.abysser.yao

/** Linux evdev key codes (input-event-codes.h). */
object K {
    const val ESC = 1
    const val MINUS = 12
    const val EQUAL = 13
    const val BACKSPACE = 14
    const val TAB = 15
    const val LEFTBRACE = 26
    const val RIGHTBRACE = 27
    const val ENTER = 28
    const val LEFTCTRL = 29
    const val SEMICOLON = 39
    const val APOSTROPHE = 40
    const val GRAVE = 41
    const val LEFTSHIFT = 42
    const val BACKSLASH = 43
    const val COMMA = 51
    const val DOT = 52
    const val SLASH = 53
    const val RIGHTSHIFT = 54
    const val KPASTERISK = 55
    const val LEFTALT = 56
    const val SPACE = 57
    const val CAPSLOCK = 58
    const val NUMLOCK = 69
    const val SCROLLLOCK = 70
    const val KPMINUS = 74
    const val KPPLUS = 78
    const val KPDOT = 83
    const val KPENTER = 96
    const val RIGHTCTRL = 97
    const val KPSLASH = 98
    const val SYSRQ = 99
    const val RIGHTALT = 100
    const val HOME = 102
    const val UP = 103
    const val PAGEUP = 104
    const val LEFT = 105
    const val RIGHT = 106
    const val END = 107
    const val DOWN = 108
    const val PAGEDOWN = 109
    const val INSERT = 110
    const val DELETE = 111
    const val MUTE = 113
    const val VOLUMEDOWN = 114
    const val VOLUMEUP = 115
    const val PAUSE = 119
    const val LEFTMETA = 125
    const val RIGHTMETA = 126
    const val COMPOSE = 127
    const val NEXTSONG = 163
    const val PLAYPAUSE = 164
    const val PREVIOUSSONG = 165
    const val F = 33

    const val BTN_LEFT = 1
    const val BTN_RIGHT = 2

    private const val LETTERS = "QWERTYUIOPASDFGHJKLZXCVBNM"
    private val LETTER_CODES = intArrayOf(16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 30, 31, 32, 33, 34, 35, 36, 37, 38, 44, 45, 46, 47, 48, 49, 50)

    fun letter(c: Char): Int = LETTER_CODES[LETTERS.indexOf(c.uppercaseChar())]
    fun digit(d: Int): Int = if (d == 0) 11 else 1 + d
    fun f(n: Int): Int = when (n) {
        in 1..10 -> 58 + n
        11 -> 87
        12 -> 88
        else -> 183 + n - 13
    }
    fun kp(d: Int): Int = when (d) {
        0 -> 82
        in 1..3 -> 78 + d
        in 4..6 -> 71 + d
        else -> 64 + d
    }

    val modifiers = setOf(LEFTCTRL, RIGHTCTRL, LEFTSHIFT, RIGHTSHIFT, LEFTALT, RIGHTALT, LEFTMETA, RIGHTMETA)
    fun isShift(code: Int) = code == LEFTSHIFT || code == RIGHTSHIFT

    /** Display name of a key, matching the server's names. */
    fun name(code: Int): String {
        val i = LETTER_CODES.indexOf(code)
        if (i >= 0) return LETTERS[i].toString()
        if (code in 2..11) return ((code - 1) % 10).toString()
        for (n in 1..24) if (f(n) == code) return "F$n"
        return when (code) {
            ESC -> "Esc"; MINUS -> "-"; EQUAL -> "="; BACKSPACE -> "Backspace"; TAB -> "Tab"
            LEFTBRACE -> "["; RIGHTBRACE -> "]"; ENTER -> "Enter"; SEMICOLON -> ";"; APOSTROPHE -> "'"
            GRAVE -> "`"; BACKSLASH -> "\\"; COMMA -> ","; DOT -> "."; SLASH -> "/"; SPACE -> "Space"
            CAPSLOCK -> "Caps"; NUMLOCK -> "NumLk"; SCROLLLOCK -> "ScrLk"; SYSRQ -> "Print"
            HOME -> "Home"; UP -> "↑"; PAGEUP -> "PgUp"; LEFT -> "←"; RIGHT -> "→"; END -> "End"
            DOWN -> "↓"; PAGEDOWN -> "PgDn"; INSERT -> "Insert"; DELETE -> "Delete"; PAUSE -> "Pause"
            COMPOSE -> "Menu"; MUTE -> "Mute"; VOLUMEDOWN -> "Vol−"; VOLUMEUP -> "Vol+"
            NEXTSONG -> "Next"; PLAYPAUSE -> "Play/Pause"; PREVIOUSSONG -> "Prev"
            LEFTCTRL, RIGHTCTRL -> "Ctrl"; LEFTSHIFT, RIGHTSHIFT -> "Shift"
            LEFTALT, RIGHTALT -> "Alt"; LEFTMETA, RIGHTMETA -> "Super"
            else -> "Key$code"
        }
    }

    private fun modRank(code: Int) = when (code) {
        LEFTMETA, RIGHTMETA -> 0
        LEFTCTRL, RIGHTCTRL -> 1
        LEFTALT, RIGHTALT -> 2
        LEFTSHIFT, RIGHTSHIFT -> 3
        else -> -1
    }

    /** "Super Ctrl Shift T" */
    fun chordName(codes: List<Int>): String {
        val mods = codes.filter { modRank(it) >= 0 }.sortedBy { modRank(it) }.distinctBy { modRank(it) }
        return (mods + codes.filter { modRank(it) < 0 }).joinToString(" ") { name(it) }
    }
}
