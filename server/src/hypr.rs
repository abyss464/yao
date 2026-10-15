//! Hyprland IPC: request socket and event socket.

use anyhow::{anyhow, Context, Result};
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::time::Duration;

/// The running instance's socket directory. Prefers `$HYPRLAND_INSTANCE_SIGNATURE`;
/// falls back to the newest instance directory (the daemon may outlive a Hyprland
/// restart, after which the inherited signature is stale).
pub fn socket_dir() -> Option<PathBuf> {
    let runtime = std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| format!("/run/user/{}", unsafe_uid()).into());
    let base = runtime.join("hypr");
    if let Some(sig) = std::env::var_os("HYPRLAND_INSTANCE_SIGNATURE") {
        let dir = base.join(sig);
        if dir.join(".socket2.sock").exists() && UnixStream::connect(dir.join(".socket.sock")).is_ok() {
            return Some(dir);
        }
    }
    let mut best: Option<(std::time::SystemTime, PathBuf)> = None;
    for entry in std::fs::read_dir(&base).ok()?.flatten() {
        let dir = entry.path();
        let sock = dir.join(".socket2.sock");
        let Ok(meta) = std::fs::metadata(&sock) else { continue };
        if UnixStream::connect(dir.join(".socket.sock")).is_err() {
            continue;
        }
        let mtime = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
        if best.as_ref().map_or(true, |(t, _)| mtime > *t) {
            best = Some((mtime, dir));
        }
    }
    best.map(|(_, d)| d)
}

fn unsafe_uid() -> u32 {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.starts_with("Uid:"))
                .and_then(|l| l.split_whitespace().nth(1).and_then(|v| v.parse().ok()))
        })
        .unwrap_or(1000)
}

/// Sends one request (e.g. `j/binds`) and returns the reply.
pub fn request(cmd: &str) -> Result<String> {
    let dir = socket_dir().ok_or_else(|| anyhow!("Hyprland is not running"))?;
    let mut s = UnixStream::connect(dir.join(".socket.sock")).context("Hyprland socket")?;
    s.set_read_timeout(Some(Duration::from_secs(3)))?;
    s.write_all(cmd.as_bytes())?;
    let mut out = String::new();
    s.read_to_string(&mut out)?;
    Ok(out)
}

/// Opens the event socket of the running instance.
pub fn events() -> Result<UnixStream> {
    let dir = socket_dir().ok_or_else(|| anyhow!("Hyprland is not running"))?;
    UnixStream::connect(dir.join(".socket2.sock")).context("Hyprland event socket")
}

#[derive(Clone, Debug, PartialEq, serde::Serialize)]
pub struct Window {
    pub class: String,
    pub title: String,
}

pub fn active_window() -> Option<Window> {
    let reply = request("j/activewindow").ok()?;
    let v: serde_json::Value = serde_json::from_str(&reply).ok()?;
    let class = v.get("class")?.as_str()?.to_string();
    if class.is_empty() {
        return None;
    }
    let title = v.get("title").and_then(|t| t.as_str()).unwrap_or("").to_string();
    Some(Window { class, title })
}

/// Caps Lock and Num Lock of the keyboard whose Hyprland name contains `name`.
pub fn lock_state(name: &str) -> Option<(bool, bool)> {
    let reply = request("j/devices").ok()?;
    let v: serde_json::Value = serde_json::from_str(&reply).ok()?;
    let kb = v.get("keyboards")?.as_array()?.iter().find(|k| {
        k.get("name").and_then(|n| n.as_str()).map_or(false, |n| n.contains(name))
    })?;
    Some((
        kb.get("capsLock").and_then(|b| b.as_bool()).unwrap_or(false),
        kb.get("numLock").and_then(|b| b.as_bool()).unwrap_or(false),
    ))
}

#[derive(Clone, Debug, PartialEq, serde::Serialize)]
pub struct Workspace {
    pub id: i64,
    pub name: String,
}

/// Normal workspaces (special ones left out), ordered by id, and the active one.
pub fn workspaces() -> Option<(Vec<Workspace>, i64)> {
    let list: serde_json::Value = serde_json::from_str(&request("j/workspaces").ok()?).ok()?;
    let active: serde_json::Value = serde_json::from_str(&request("j/activeworkspace").ok()?).ok()?;
    let mut out: Vec<Workspace> = list
        .as_array()?
        .iter()
        .filter_map(|w| {
            let id = w.get("id")?.as_i64()?;
            (id > 0).then(|| Workspace { id, name: w.get("name").and_then(|n| n.as_str()).unwrap_or("").to_string() })
        })
        .collect();
    out.sort_by_key(|w| w.id);
    Some((out, active.get("id").and_then(|i| i.as_i64()).unwrap_or(0)))
}

/// Switches to a workspace: the Lua dispatcher first, the classic one if this
/// Hyprland still uses hyprlang.
pub fn focus_workspace(id: i64) -> bool {
    let lua = request(&format!("dispatch hl.dsp.focus({{ workspace = {id} }})"));
    if lua.as_deref().map_or(false, |r| r.trim() == "ok") {
        return true;
    }
    request(&format!("dispatch workspace {id}")).map_or(false, |r| r.trim() == "ok")
}
