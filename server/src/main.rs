//! yaod: turns a paired phone into this computer's keyboard, touchpad and remote.
//! No UI; the only thing it ever shows is a pairing code in a desktop notification.

mod devices;
mod ime;
mod net;
mod session;
mod video;

use anyhow::{bail, Context, Result};
use serde::Serialize;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::sync::atomic::AtomicU64;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::watch;
use yao::config::{self, Config, Paths, Shortcuts};
use yao::hypr::{self, Window};
use yao::library::{self, HyprBind};

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct Leds {
    pub caps: bool,
    pub num: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Media {
    pub player: String,
    pub title: String,
    pub artist: String,
    pub playing: bool,
    pub position_ms: u64,
    pub length_ms: u64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct State {
    pub window: Option<Window>,
    pub media: Option<Media>,
    pub leds: Leds,
    pub workspaces: Vec<hypr::Workspace>,
    pub workspace: i64,
}

pub struct Shared {
    pub paths: Paths,
    pub sandbox: bool,
    pub host: String,
    pub devices: Mutex<devices::Devices>,
    pub state: watch::Sender<State>,
    pub library: watch::Sender<serde_json::Value>,
    pub binds: Mutex<Vec<HyprBind>>,
    /// Serialises read-modify-write of shortcuts.toml and devices.toml.
    pub store_lock: Mutex<()>,
    pub text_lock: tokio::sync::Mutex<()>,
    pub ime: ime::Fcitx,
    pub next_session: AtomicU64,
}

impl Shared {
    pub fn rebuild_library(&self) {
        let user = Shortcuts::load(&self.paths).unwrap_or_else(|e| {
            eprintln!("yaod: {e:#}");
            Shortcuts::default()
        });
        let binds = self.binds.lock().unwrap().clone();
        let json = library::to_json(&library::build(&user, &binds));
        self.library.send_if_modified(|cur| {
            if *cur != json {
                *cur = json;
                true
            } else {
                false
            }
        });
    }

    fn refresh_binds(&self) {
        if let Ok(reply) = hypr::request("j/binds") {
            *self.binds.lock().unwrap() = library::hypr_binds(&reply);
        }
        self.rebuild_library();
    }

    pub fn set_window(&self, window: Option<Window>) {
        self.state.send_if_modified(|s| {
            let changed = s.window != window;
            s.window = window;
            changed
        });
    }

    fn refresh_workspaces(&self) {
        if let Some((list, active)) = hypr::workspaces() {
            self.state.send_if_modified(|s| {
                let changed = s.workspaces != list || s.workspace != active;
                s.workspaces = list;
                s.workspace = active;
                changed
            });
        }
    }

    pub fn refresh_leds(&self) {
        if let Some((caps, num)) = hypr::lock_state(devices::KEYBOARD_HYPR_NAME) {
            self.state.send_if_modified(|s| {
                let leds = Leds { caps, num };
                let changed = s.leds != leds;
                s.leds = leds;
                changed
            });
        }
    }

    fn set_media(&self, media: Option<Media>) {
        self.state.send_if_modified(|s| {
            let changed = s.media != media;
            s.media = media;
            changed
        });
    }
}

struct Args {
    sandbox: bool,
    advertise: bool,
    port: Option<u16>,
    config_dir: Option<PathBuf>,
}

fn parse_args() -> Result<Args> {
    let mut args = Args { sandbox: false, advertise: true, port: None, config_dir: None };
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--sandbox" => args.sandbox = true,
            "--no-advertise" => args.advertise = false,
            "--port" => args.port = Some(it.next().context("--port needs a value")?.parse()?),
            "--config-dir" => args.config_dir = Some(it.next().context("--config-dir needs a value")?.into()),
            "-h" | "--help" => {
                println!(
                    "yaod [--port N] [--config-dir DIR] [--no-advertise] [--sandbox]\n\n  \
                     --no-advertise  no mDNS announcement\n  \
                     --sandbox       grab the virtual devices and log instead of typing (development)"
                );
                std::process::exit(0);
            }
            other => bail!("unknown argument {other}"),
        }
    }
    Ok(args)
}

/// Follows Hyprland's event socket for the focused window and config reloads,
/// reconnecting when Hyprland restarts.
fn hypr_thread(sh: Arc<Shared>) {
    loop {
        if let Ok(stream) = hypr::events() {
            sh.refresh_binds();
            sh.set_window(hypr::active_window());
            sh.refresh_leds();
            sh.refresh_workspaces();
            for line in BufReader::new(stream).lines() {
                let Ok(line) = line else { break };
                let event = line.split(">>").next().unwrap_or("");
                if matches!(
                    event,
                    "workspace" | "workspacev2" | "createworkspace" | "createworkspacev2" | "destroyworkspace"
                        | "destroyworkspacev2" | "focusedmon" | "focusedmonv2" | "moveworkspace"
                        | "moveworkspacev2" | "renameworkspace"
                ) {
                    sh.refresh_workspaces();
                }
                if let Some(rest) = line.strip_prefix("activewindow>>") {
                    let (class, title) = rest.split_once(',').unwrap_or((rest, ""));
                    sh.set_window((!class.is_empty()).then(|| Window {
                        class: class.to_string(),
                        title: title.to_string(),
                    }));
                } else if line.starts_with("configreloaded>>") {
                    sh.refresh_binds();
                }
            }
        }
        std::thread::sleep(Duration::from_secs(1));
    }
}

/// Follows the current media player through playerctl.
fn media_thread(sh: Arc<Shared>) {
    const FORMAT: &str =
        "{{playerName}}\t{{status}}\t{{position}}\t{{mpris:length}}\t{{artist}}\t{{title}}";
    loop {
        let child = std::process::Command::new("playerctl")
            .args(["--follow", "metadata", "--format", FORMAT])
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .spawn();
        if let Ok(mut child) = child {
            if let Some(out) = child.stdout.take() {
                for line in BufReader::new(out).lines() {
                    let Ok(line) = line else { break };
                    let f: Vec<&str> = line.splitn(6, '\t').collect();
                    let media = (f.len() == 6 && !f[0].is_empty()).then(|| Media {
                        player: f[0].to_string(),
                        playing: f[1] == "Playing",
                        position_ms: f[2].trim().parse::<u64>().unwrap_or(0) / 1000,
                        length_ms: f[3].trim().parse::<u64>().unwrap_or(0) / 1000,
                        artist: f[4].to_string(),
                        title: f[5].to_string(),
                    });
                    sh.set_media(media);
                }
            }
            let _ = child.kill();
            let _ = child.wait();
        }
        sh.set_media(None);
        std::thread::sleep(Duration::from_secs(2));
    }
}

/// Picks up edits made to shortcuts.toml by hand.
async fn watch_shortcuts(sh: Arc<Shared>) {
    let path = sh.paths.shortcuts_file();
    let mtime = |p: &std::path::Path| std::fs::metadata(p).and_then(|m| m.modified()).ok();
    let mut last = mtime(&path);
    loop {
        tokio::time::sleep(Duration::from_secs(2)).await;
        let now = mtime(&path);
        if now != last {
            last = now;
            sh.rebuild_library();
        }
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = parse_args()?;
    let paths = match &args.config_dir {
        Some(d) => Paths::rooted(d),
        None => Paths::standard(),
    };
    let cfg = Config::load(&paths)?;
    let port = args.port.unwrap_or(cfg.port());
    let host = config::hostname();
    let acceptor = net::acceptor(&paths)?;
    let devices = devices::Devices::new(args.sandbox)?;

    let (state, _) = watch::channel(State::default());
    let (library, _) = watch::channel(serde_json::json!({ "shortcuts": [] }));
    let sh = Arc::new(Shared {
        paths,
        sandbox: args.sandbox,
        host: host.clone(),
        devices: Mutex::new(devices),
        state,
        library,
        binds: Mutex::new(Vec::new()),
        store_lock: Mutex::new(()),
        text_lock: tokio::sync::Mutex::new(()),
        ime: ime::Fcitx::default(),
        next_session: AtomicU64::new(1),
    });
    sh.rebuild_library();

    {
        let sh = sh.clone();
        std::thread::spawn(move || hypr_thread(sh));
    }
    {
        let sh = sh.clone();
        std::thread::spawn(move || media_thread(sh));
    }
    tokio::spawn(watch_shortcuts(sh.clone()));
    if args.advertise && !args.sandbox {
        tokio::spawn(net::advertise(host.clone(), port, cfg.advertise_ip));
    }

    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port))
        .await
        .with_context(|| format!("listening on port {port}"))?;
    eprintln!(
        "yaod: {host} listening on {port}{}",
        if args.sandbox { " (sandbox: devices grabbed, nothing reaches the desktop)" } else { "" }
    );
    loop {
        let (tcp, peer) = listener.accept().await?;
        let sh = sh.clone();
        let acceptor = acceptor.clone();
        tokio::spawn(async move {
            session::run(sh, tcp, peer, acceptor).await;
        });
    }
}
