//! Files the daemon keeps: settings, paired devices and the shortcut library.

use anyhow::{Context, Result};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use std::io::Write;
use std::net::Ipv4Addr;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

pub const DEFAULT_PORT: u16 = 20780;

#[derive(Clone, Debug)]
pub struct Paths {
    pub config_dir: PathBuf,
    pub state_dir: PathBuf,
}

impl Paths {
    pub fn standard() -> Self {
        let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(|| "/".into());
        let config = std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".config"));
        let state = std::env::var_os("XDG_STATE_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".local/state"));
        Paths { config_dir: config.join("yao"), state_dir: state.join("yao") }
    }

    /// Everything under one directory (development runs).
    pub fn rooted(dir: &Path) -> Self {
        Paths { config_dir: dir.join("config"), state_dir: dir.join("state") }
    }

    pub fn config_file(&self) -> PathBuf {
        self.config_dir.join("config.toml")
    }
    pub fn shortcuts_file(&self) -> PathBuf {
        self.config_dir.join("shortcuts.toml")
    }
    pub fn devices_file(&self) -> PathBuf {
        self.config_dir.join("devices.toml")
    }
    pub fn cert_file(&self) -> PathBuf {
        self.state_dir.join("cert.der")
    }
    pub fn key_file(&self) -> PathBuf {
        self.state_dir.join("key.der")
    }
}

#[derive(Debug, Default, Deserialize, Serialize)]
#[serde(default)]
pub struct Config {
    pub port: Option<u16>,
    pub advertise_ip: Option<Ipv4Addr>,
}

impl Config {
    pub fn load(paths: &Paths) -> Result<Config> {
        read_toml(&paths.config_file())
    }
    pub fn port(&self) -> u16 {
        self.port.unwrap_or(DEFAULT_PORT)
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub token_sha256: String,
    pub paired_at: String,
}

#[derive(Debug, Default, Deserialize, Serialize)]
pub struct Devices {
    #[serde(default, rename = "device")]
    pub devices: Vec<Device>,
}

impl Devices {
    pub fn load(paths: &Paths) -> Result<Devices> {
        read_toml(&paths.devices_file())
    }
    pub fn save(&self, paths: &Paths) -> Result<()> {
        write_toml(&paths.devices_file(), self)
    }
}

/// One step of a user shortcut.
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(untagged)]
pub enum Step {
    Chord { chord: Vec<u16> },
    Text { text: String },
    Wait { wait_ms: u64 },
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct UserShortcut {
    pub id: String,
    #[serde(default)]
    pub app: String,
    pub name: String,
    #[serde(default)]
    pub steps: Vec<Step>,
}

/// `shortcuts.toml`: the user's shortcuts and the ids marked as favourites
/// (user shortcuts and Hyprland binds alike).
#[derive(Debug, Default, Deserialize, Serialize)]
pub struct Shortcuts {
    #[serde(default)]
    pub favorites: Vec<String>,
    #[serde(default, rename = "shortcut")]
    pub shortcuts: Vec<UserShortcut>,
}

impl Shortcuts {
    pub fn load(paths: &Paths) -> Result<Shortcuts> {
        read_toml(&paths.shortcuts_file())
    }
    pub fn save(&self, paths: &Paths) -> Result<()> {
        write_toml(&paths.shortcuts_file(), self)
    }
}

fn read_toml<T: DeserializeOwned + Default>(path: &Path) -> Result<T> {
    match std::fs::read_to_string(path) {
        Ok(s) => toml::from_str(&s).with_context(|| format!("reading {}", path.display())),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(T::default()),
        Err(e) => Err(e).with_context(|| format!("reading {}", path.display())),
    }
}

/// Writes atomically, readable by the owner only (devices.toml holds token hashes).
fn write_toml<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    let text = toml::to_string_pretty(value)?;
    write_private(path, text.as_bytes())
}

pub fn write_private(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let tmp = path.with_extension("tmp");
    {
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(&tmp)
            .with_context(|| format!("writing {}", tmp.display()))?;
        f.write_all(bytes)?;
        f.sync_all()?;
    }
    std::fs::rename(&tmp, path)?;
    Ok(())
}

/// Current UTC time as RFC 3339, without pulling in a date crate.
pub fn now_rfc3339() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // Civil-from-days (Howard Hinnant).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60
    )
}

pub fn hostname() -> String {
    std::fs::read_to_string("/proc/sys/kernel/hostname")
        .map(|s| s.trim().to_string())
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "computer".into())
}
