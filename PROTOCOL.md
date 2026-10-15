# Yao wire protocol (v1)

The phone app is the client, `yaod` on the computer is the server. One TLS 1.3
connection over TCP carries everything.

## Transport

- Port **20780** (TCP). The server listens on `0.0.0.0`.
- Discovery: the server advertises mDNS service type `_yao._tcp`, instance name =
  the computer's hostname, TXT `v=1`, on the IPv4 address of the interface that
  holds the default route (overridable in config).
- TLS: the server presents a self-signed certificate generated on first start and
  kept for good. The client pins the SHA-256 of the certificate's DER encoding:
  it records it while pairing and afterwards refuses any other certificate.
  No hostname verification.
- `TCP_NODELAY` on both sides.

## Framing

Every frame is

```
u16  length   big-endian, number of bytes that follow (type + payload), 1..65535
u8   type
...  payload  (length - 1 bytes)
```

All integers are big-endian. JSON payloads are UTF-8 JSON objects.

## Session

1. The client opens TLS.
2. Already paired: the client sends `HELLO`. The server answers `WELCOME`, then a
   `STATE` and a `LIBRARY`. Unknown device or wrong token: `ERROR`
   (`code: "unpaired"`) and the server closes.
3. Pairing: the client sends `PAIR_REQUEST`. The server makes a random 6-digit
   code, shows it in a desktop notification for 60 s, and answers `PAIR_PENDING`.
   The client sends `PAIR_CODE`. Right code: the server stores the device and
   answers `PAIR_OK` with a fresh token; the session is now authenticated (no
   `HELLO` needed) and `STATE` + `LIBRARY` follow. Wrong code: `ERROR`
   (`bad_code`); after 3 wrong codes or after 60 s the request is void
   (`ERROR` `expired`) and the client must send a new `PAIR_REQUEST`.
4. Before authentication any other frame gets `ERROR` (`unauthenticated`) and the
   server closes.
5. Liveness: the client sends `PING` every 250 ms; the server answers `PONG`.
   If the server hears nothing from a session for 1 s it releases everything that
   session holds (keys, buttons, touch contacts). After 10 s of silence it closes
   the connection. On close it also releases everything. The client reconnects
   when it has had no `PONG` for 2 s.

## Client → server

| type | name | payload |
|---|---|---|
| `0x01` | HELLO | JSON `{"id": "<device uuid>", "token": "<base64>", "name": "<device model>"}` |
| `0x02` | PAIR_REQUEST | JSON `{"id": "<device uuid>", "name": "<device model>"}` |
| `0x03` | PAIR_CODE | JSON `{"code": "482913"}` |
| `0x04` | VIDEO_OPEN | JSON `{"id", "token", "width", "height"}` — first frame of a separate connection that carries the picture of the focused window (see "Video") |
| `0x10` | TOUCH_CONFIG | `u16 width_px, u16 height_px, u16 x_px_per_mm, u16 y_px_per_mm` — size of the touch surface. Sent when the touchpad surface is laid out or changes size. |
| `0x11` | TOUCH | `u32 time_ms`, `u8 n`, then `n` × `{u8 id, u16 x, u16 y}` — the complete set of fingers on the surface at `time_ms` (the client's monotonic clock in ms, e.g. Android `MotionEvent.eventTime`; `id` = Android pointer id, `x`,`y` in surface px). A finger missing from the set has lifted; `n = 0` means none. Sent on every touch event. The server replays touch frames with the client's own spacing behind a short fixed delay, so that frames bunched up by Wi-Fi do not reach libinput as jumps. |
| `0x12` | BUTTON | `u8 button` (1 left, 2 right, 3 middle), `u8 state` (1 down, 0 up) |
| `0x13` | KEY | `u16 code` (Linux evdev `KEY_*`), `u8 state` (1 down, 0 up). Auto-repeat is the computer's job: the client sends one down and one up. |
| `0x14` | MOTION | `i16 dx, i16 dy` — relative pointer motion, already scaled by the client |
| `0x15` | CHORD | `u8 n`, then `n` × `u16 code` — the server presses the keys in order and releases them in reverse |
| `0x20` | TEXT | raw UTF-8 text the phone's input method committed; put into the computer's focused field as-is |
| `0x25` | PREEDIT | raw UTF-8 text the phone's input method is still composing, shown underlined in the computer's focused field; empty clears it. A following TEXT replaces it. |
| `0x21` | RUN | JSON `{"id": "<shortcut id>"}` |
| `0x22` | SHORTCUT_PUT | JSON `{"id"?: "<id>", "app": "<window class or empty>", "name": "...", "steps": [step...]}` — no `id` creates a new user shortcut |
| `0x23` | SHORTCUT_DELETE | JSON `{"id": "<id>"}` (user shortcuts only) |
| `0x24` | FAVORITE | JSON `{"id": "<id>", "favorite": true}` (any shortcut) |
| `0x26` | WORKSPACE | JSON `{"id": 3}` — switch the computer to that workspace |
| `0x30` | PING | `u64` client clock in ms |

## Server → client

| type | name | payload |
|---|---|---|
| `0x81` | WELCOME | JSON `{"host": "<hostname>"}` |
| `0x82` | PAIR_PENDING | JSON `{"expires_in": 60}` |
| `0x83` | PAIR_OK | JSON `{"token": "<base64 of 32 random bytes>", "host": "<hostname>"}` |
| `0x84` | ERROR | JSON `{"code": "unpaired" \| "bad_code" \| "expired" \| "unauthenticated" \| "bad_request", "message": "..."}` |
| `0x90` | PONG | the `u64` from the `PING`, echoed |
| `0x91` | STATE | JSON, full snapshot, sent on connect and whenever any part changes (below) |
| `0x92` | LIBRARY | JSON `{"shortcuts": [shortcut...]}`, full snapshot, sent on connect and whenever it changes |

### STATE

```json
{
  "window": {"class": "code", "title": "PROTOCOL.md - yao - Visual Studio Code"},
  "media": {"player": "mpv", "title": "...", "artist": "...", "playing": true,
            "position_ms": 81234, "length_ms": 1420000},
  "leds": {"caps": false, "num": true},
  "workspaces": [{"id": 5, "name": "5"}, {"id": 6, "name": "6"}],
  "workspace": 6
}
```

`window` and `media` may be `null`. `position_ms` is the position at the moment the
STATE was sent; while `playing` the client advances it with its own clock.

### Shortcut

```json
{
  "id": "u:3f0c...",
  "source": "user",
  "app": "code",
  "group": "",
  "name": "Command palette",
  "keys": "Ctrl Shift P",
  "steps": [{"chord": [29, 42, 25]}],
  "favorite": false
}
```

- `id`: `u:<uuid>` for user shortcuts, `h:<modmask>:<key>` for Hyprland binds.
- `source`: `"user"` or `"hyprland"`.
- `app`: window class it belongs to (compared case-insensitively with
  `STATE.window.class`); empty string = global. Hyprland binds are global.
- `group`: for Hyprland binds the part of the bind's description before the first
  `": "` (`"Shell"`, `"Utilities"`, ...); `name` is the rest. Empty for user ones.
- `keys`: what the shortcut presses, for display: modifiers in the order
  `Super Ctrl Alt Shift`, then the key (`"Super Shift T"`, `"Ctrl \`"`). A user
  shortcut with more than one step shows `"序列 · N 步"`.
- `steps` (user shortcuts only): run in order —
  `{"chord": [codes]}` presses the keys in order and releases them in reverse,
  `{"text": "..."}` types text, `{"wait_ms": 200}` waits.
- Hyprland binds listed: those with a description, in the default submap, bound to
  a keyboard key (not `mouse:`). Running one presses its modifiers and key on the
  virtual keyboard.

## Video

While typing, the phone shows the window the text goes to. It opens a second TLS
connection whose first frame is `VIDEO_OPEN` (authenticated like `HELLO`, with the
largest picture it wants). From then on the connection carries only server messages:

```
u32  length   big-endian, bytes that follow (type + payload)
u8   type     0x01 FORMAT: JSON {"width", "height", "window": {"class", "title"}}
              0x02 FRAME: one H.264 access unit, Annex-B
```

The focused window's region is captured (wlr-screencopy via wf-recorder), scaled to
fit, and encoded at 30 fps on the GPU (VAAPI; libx264 otherwise), a keyframe every
second. When focus moves, or the window moves or resizes, a new FORMAT follows and the
stream restarts with a keyframe. Closing the connection stops capture.

## Computer-side devices

- **Yao Touchpad** — uinput multitouch touchpad (`INPUT_PROP_POINTER`, MT slots,
  tracking ids, `BTN_TOUCH`, `BTN_TOOL_FINGER`…`QUINTTAP`, `BTN_LEFT/RIGHT/MIDDLE`),
  axis ranges `0..width-1` / `0..height-1` with the resolution from `TOUCH_CONFIG`.
  libinput treats it as a touchpad, so tapping, scrolling, gestures and acceleration
  follow the computer's touchpad settings. `BUTTON` frames go to it.
- **Yao Keyboard** — uinput keyboard with every `KEY_*` code, including media keys.
- **Yao Pointer** — uinput relative pointer (`REL_X`, `REL_Y`, `BTN_LEFT`) for `MOTION`.
- **Text** — committed through fcitx5 (its `wechatvoice` addon: `Begin`, `SetPreedit`,
  `Commit`, `Cancel` on `org.fcitx.Fcitx5 /wechatvoice`), the channel apps already take
  composed text from. Where fcitx5 has no focused input context, typed with `wtype`
  with fcitx5 switched off around it.
