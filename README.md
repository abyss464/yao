# 遥 Yao

A phone as this computer's touchpad, remote, full-size keyboard and shortcut pad.

- `server/` — `yaod`, a daemon with no UI. It creates a virtual touchpad, keyboard
  and pointer through uinput, so libinput and Hyprland treat the phone like real
  hardware: tapping, scrolling, gestures and key binds follow this computer's own
  settings. Text from the phone's input method is typed through the Wayland
  virtual-keyboard protocol (`wtype`). The only thing it ever shows is the pairing
  code, in a desktop notification.
- `android/` — the phone app (Kotlin, Jetpack Compose).
- `PROTOCOL.md` — the wire protocol between them.
- `design/` — the design boards.

## Computer

```sh
server/install.sh          # builds, installs yaod + yao to ~/.local/bin, enables yaod.service
yao devices                # paired phones
yao revoke <id|name>       # unpair one
yao library                # the shortcut library phones receive
```

`yaod` listens on TCP 20780 (TLS 1.3, self-signed certificate pinned by the phone at
pairing) and advertises `_yao._tcp` over mDNS. Files:

- `~/.config/yao/config.toml` — `port`, `advertise_ip` (both optional)
- `~/.config/yao/shortcuts.toml` — user shortcuts and favourites, edited from the phone
- `~/.config/yao/devices.toml` — paired phones (token hashes only)
- `~/.local/state/yao/` — the TLS identity

`yaod --sandbox --port N --config-dir DIR` grabs its own devices and logs instead of
typing, for development.

## Phone

`android/dist/yao.apk` (release, signed with the owner's release key). Build:

```sh
cd android && ./gradlew :app:assembleRelease   # then sign app-release-unsigned.apk with apksigner
```

Open the app on the same network; it finds the computer and asks for the code from
the notification. Tabs: touchpad (the `T` at the top right types through the phone's
input method), remote (hold the bottom line and wave the phone to move the pointer;
the volume keys turn the computer's volume), keyboard (landscape; turn the phone
upright to leave), shortcuts (current window's, favourites, Hyprland binds, any chord).
