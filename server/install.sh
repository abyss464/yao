#!/bin/sh
# Builds yaod and yao, installs them to ~/.local/bin and (re)starts the user service.
set -eu
cd "$(dirname "$0")"
cargo build --release
mkdir -p "$HOME/.local/bin" "$HOME/.config/systemd/user"
install -m 755 target/release/yaod target/release/yao "$HOME/.local/bin/"
install -m 644 yaod.service "$HOME/.config/systemd/user/yaod.service"
systemctl --user daemon-reload
systemctl --user enable yaod.service
systemctl --user restart yaod.service
systemctl --user --no-pager status yaod.service | head -5
