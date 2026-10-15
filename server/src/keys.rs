//! Linux evdev key codes: names for display, and xkb key names (as written in
//! Hyprland binds) mapped to codes for the US layout.

pub const KEY_LEFTCTRL: u16 = 29;
pub const KEY_LEFTSHIFT: u16 = 42;
pub const KEY_LEFTALT: u16 = 56;
pub const KEY_CAPSLOCK: u16 = 58;
pub const KEY_NUMLOCK: u16 = 69;
pub const KEY_RIGHTCTRL: u16 = 97;
pub const KEY_RIGHTALT: u16 = 100;
pub const KEY_LEFTMETA: u16 = 125;
pub const KEY_RIGHTMETA: u16 = 126;
pub const KEY_RIGHTSHIFT: u16 = 54;

/// Hyprland modmask bits.
pub const MOD_SHIFT: u32 = 1;
pub const MOD_CTRL: u32 = 4;
pub const MOD_ALT: u32 = 8;
pub const MOD_SUPER: u32 = 64;

/// Display order of modifiers: Super Ctrl Alt Shift.
fn modifier_rank(code: u16) -> Option<u8> {
    match code {
        KEY_LEFTMETA | KEY_RIGHTMETA => Some(0),
        KEY_LEFTCTRL | KEY_RIGHTCTRL => Some(1),
        KEY_LEFTALT | KEY_RIGHTALT => Some(2),
        KEY_LEFTSHIFT | KEY_RIGHTSHIFT => Some(3),
        _ => None,
    }
}

pub fn is_modifier(code: u16) -> bool {
    modifier_rank(code).is_some()
}

/// Modifier key codes for a Hyprland modmask, in press order.
pub fn modmask_codes(mask: u32) -> Vec<u16> {
    let mut out = Vec::new();
    if mask & MOD_SUPER != 0 {
        out.push(KEY_LEFTMETA);
    }
    if mask & MOD_CTRL != 0 {
        out.push(KEY_LEFTCTRL);
    }
    if mask & MOD_ALT != 0 {
        out.push(KEY_LEFTALT);
    }
    if mask & MOD_SHIFT != 0 {
        out.push(KEY_LEFTSHIFT);
    }
    out
}

/// "Super Ctrl Shift T": modifiers first in fixed order, then the other keys as given.
pub fn chord_display(codes: &[u16]) -> String {
    let mut mods: Vec<(u8, u16)> = codes
        .iter()
        .filter_map(|&c| modifier_rank(c).map(|r| (r, c)))
        .collect();
    mods.sort();
    mods.dedup_by_key(|(r, _)| *r);
    let mut parts: Vec<String> = mods.iter().map(|&(_, c)| display_name(c)).collect();
    parts.extend(codes.iter().filter(|&&c| !is_modifier(c)).map(|&c| display_name(c)));
    parts.join(" ")
}

const LETTERS: &str = "QWERTYUIOPASDFGHJKLZXCVBNM";
const LETTER_CODES: [u16; 26] = [
    16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 30, 31, 32, 33, 34, 35, 36, 37, 38, 44, 45, 46, 47, 48,
    49, 50,
];

fn letter_code(c: char) -> Option<u16> {
    LETTERS.find(c.to_ascii_uppercase()).map(|i| LETTER_CODES[i])
}

fn f_key_code(n: u16) -> Option<u16> {
    match n {
        1..=10 => Some(58 + n),
        11 => Some(87),
        12 => Some(88),
        13..=24 => Some(183 + n - 13),
        _ => None,
    }
}

pub fn display_name(code: u16) -> String {
    if let Some(i) = LETTER_CODES.iter().position(|&c| c == code) {
        return LETTERS[i..i + 1].to_string();
    }
    if (2..=11).contains(&code) {
        return ((code - 1) % 10).to_string();
    }
    for n in 1..=24 {
        if f_key_code(n) == Some(code) {
            return format!("F{n}");
        }
    }
    let s = match code {
        1 => "Esc",
        12 => "-",
        13 => "=",
        14 => "Backspace",
        15 => "Tab",
        26 => "[",
        27 => "]",
        28 => "Enter",
        39 => ";",
        40 => "'",
        41 => "`",
        43 => "\\",
        51 => ",",
        52 => ".",
        53 => "/",
        55 => "Num *",
        57 => "Space",
        58 => "Caps",
        69 => "NumLk",
        70 => "ScrLk",
        71 => "Num 7",
        72 => "Num 8",
        73 => "Num 9",
        74 => "Num -",
        75 => "Num 4",
        76 => "Num 5",
        77 => "Num 6",
        78 => "Num +",
        79 => "Num 1",
        80 => "Num 2",
        81 => "Num 3",
        82 => "Num 0",
        83 => "Num .",
        96 => "Num Enter",
        98 => "Num /",
        99 => "Print",
        102 => "Home",
        103 => "↑",
        104 => "PgUp",
        105 => "←",
        106 => "→",
        107 => "End",
        108 => "↓",
        109 => "PgDn",
        110 => "Insert",
        111 => "Delete",
        113 => "Mute",
        114 => "Vol−",
        115 => "Vol+",
        119 => "Pause",
        127 => "Menu",
        163 => "Next",
        164 => "Play/Pause",
        165 => "Prev",
        166 => "Stop",
        200 => "Play",
        201 => "Pause",
        224 => "Bright−",
        225 => "Bright+",
        248 => "Mic Mute",
        KEY_LEFTCTRL | KEY_RIGHTCTRL => "Ctrl",
        KEY_LEFTSHIFT | KEY_RIGHTSHIFT => "Shift",
        KEY_LEFTALT | KEY_RIGHTALT => "Alt",
        KEY_LEFTMETA | KEY_RIGHTMETA => "Super",
        _ => return format!("Key{code}"),
    };
    s.to_string()
}

/// An xkb key name (as Hyprland binds spell it) to an evdev code, US layout.
pub fn xkb_to_code(name: &str) -> Option<u16> {
    let mut chars = name.chars();
    if let (Some(c), None) = (chars.next(), chars.next()) {
        if c.is_ascii_alphabetic() {
            return letter_code(c);
        }
        if let Some(d) = c.to_digit(10) {
            return Some(if d == 0 { 11 } else { 1 + d as u16 });
        }
        let punct = match c {
            '-' => 12,
            '=' => 13,
            '[' => 26,
            ']' => 27,
            ';' => 39,
            '\'' => 40,
            '`' => 41,
            '\\' => 43,
            ',' => 51,
            '.' => 52,
            '/' => 53,
            ' ' => 57,
            _ => return None,
        };
        return Some(punct);
    }
    let lower = name.to_ascii_lowercase();
    if let Some(n) = lower.strip_prefix('f').and_then(|n| n.parse::<u16>().ok()) {
        return f_key_code(n);
    }
    if let Some(n) = lower.strip_prefix("kp_").and_then(|n| n.parse::<u16>().ok()) {
        return match n {
            0 => Some(82),
            1..=3 => Some(78 + n),
            4..=6 => Some(71 + n),
            7..=9 => Some(64 + n),
            _ => None,
        };
    }
    let code = match lower.as_str() {
        "escape" => 1,
        "minus" => 12,
        "equal" => 13,
        "backspace" => 14,
        "tab" => 15,
        "bracketleft" => 26,
        "bracketright" => 27,
        "return" | "enter" => 28,
        "semicolon" => 39,
        "apostrophe" => 40,
        "grave" => 41,
        "backslash" => 43,
        "comma" => 51,
        "period" => 52,
        "slash" => 53,
        "kp_multiply" => 55,
        "space" => 57,
        "caps_lock" => 58,
        "num_lock" => 69,
        "scroll_lock" => 70,
        "kp_subtract" => 74,
        "kp_add" => 78,
        "kp_decimal" => 83,
        "kp_enter" => 96,
        "kp_divide" => 98,
        "print" => 99,
        "home" => 102,
        "up" => 103,
        "prior" | "page_up" => 104,
        "left" => 105,
        "right" => 106,
        "end" => 107,
        "down" => 108,
        "next" | "page_down" => 109,
        "insert" => 110,
        "delete" => 111,
        "pause" => 119,
        "menu" => 127,
        "xf86audiomute" => 113,
        "xf86audiolowervolume" => 114,
        "xf86audioraisevolume" => 115,
        "xf86audionext" => 163,
        "xf86audioplay" => 164,
        "xf86audioprev" => 165,
        "xf86audiostop" => 166,
        "xf86audiopause" => 201,
        "xf86monbrightnessdown" => 224,
        "xf86monbrightnessup" => 225,
        "xf86audiomicmute" => 248,
        _ => return None,
    };
    Some(code)
}
