//! The picture of the window the phone is typing into, as H.264.
//!
//! A phone opens a second connection for it (so big video frames never queue up
//! in front of touches and keys) and starts it with VIDEO_OPEN. The focused
//! window's region is captured with wf-recorder (wlr-screencopy), scaled and
//! encoded on the GPU (VAAPI; libx264 when that is unavailable), and sent one
//! access unit per message. When focus moves to another window, or the window
//! moves or changes size, capture restarts on the new region.
//!
//! Messages on the video connection: u32 length (of type + payload), u8 type, payload.
//!   0x01 FORMAT  JSON {"width", "height", "window": {"class", "title"}}
//!   0x02 FRAME   one H.264 access unit, Annex-B

use crate::Shared;
use serde_json::json;
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::process::{Child, Command};

const FORMAT: u8 = 0x01;
const FRAME: u8 = 0x02;

#[derive(Clone, Debug)]
struct Region {
    address: String,
    x: i64,
    y: i64,
    w: i64,
    h: i64,
    class: String,
    title: String,
}

/// The focused window's place in the global layout (logical pixels).
fn focused_region() -> Option<Region> {
    let reply = yao::hypr::request("j/activewindow").ok()?;
    let v: serde_json::Value = serde_json::from_str(&reply).ok()?;
    let at = v.get("at")?.as_array()?;
    let size = v.get("size")?.as_array()?;
    let r = Region {
        address: v.get("address").and_then(|a| a.as_str()).unwrap_or("").to_string(),
        x: at.first()?.as_i64()?,
        y: at.get(1)?.as_i64()?,
        w: size.first()?.as_i64()?,
        h: size.get(1)?.as_i64()?,
        class: v.get("class").and_then(|c| c.as_str()).unwrap_or("").to_string(),
        title: v.get("title").and_then(|c| c.as_str()).unwrap_or("").to_string(),
    };
    (r.w > 0 && r.h > 0).then_some(r)
}

impl Region {
    /// Same window in the same place (a new title does not restart capture).
    fn same(&self, o: &Region) -> bool {
        (&self.address, self.x, self.y, self.w, self.h) == (&o.address, o.x, o.y, o.w, o.h)
    }
}

/// Largest even size with the region's shape that fits in `max_w`×`max_h`.
fn fit(r: &Region, max_w: i64, max_h: i64) -> (i64, i64) {
    let scale = (max_w as f64 / r.w as f64).min(max_h as f64 / r.h as f64).min(1.0);
    let even = |v: f64| ((v as i64) / 2 * 2).max(16);
    (even(r.w as f64 * scale), even(r.h as f64 * scale))
}

fn render_node() -> Option<String> {
    let mut nodes: Vec<String> = std::fs::read_dir("/dev/dri")
        .ok()?
        .flatten()
        .map(|e| e.path().to_string_lossy().into_owned())
        .filter(|p| p.contains("renderD"))
        .collect();
    nodes.sort();
    nodes.into_iter().next()
}

fn spawn_capture(r: &Region, w: i64, h: i64, hardware: bool) -> std::io::Result<Child> {
    let mut c = Command::new("wf-recorder");
    c.args(["-y", "-g", &format!("{},{} {}x{}", r.x, r.y, r.w, r.h), "-m", "h264", "-f", "/dev/stdout"]);
    c.args(["-r", "30", "-b", "0", "-p", "aud=1", "-p", "g=30"]);
    match (hardware, render_node()) {
        (true, Some(node)) => {
            c.args(["-c", "h264_vaapi", "-d", &node, "-x", "vaapi"]);
            c.args(["-F", &format!("scale_vaapi=w={w}:h={h}:format=nv12")]);
        }
        _ => {
            c.args(["-c", "libx264", "-x", "yuv420p", "-p", "preset=ultrafast", "-p", "tune=zerolatency"]);
            c.args(["-F", &format!("scale={w}:{h}")]);
        }
    }
    c.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).kill_on_drop(true).spawn()
}

async fn send<W: AsyncWrite + Unpin>(w: &mut W, kind: u8, payload: &[u8]) -> std::io::Result<()> {
    let mut head = Vec::with_capacity(5);
    head.extend_from_slice(&((payload.len() + 1) as u32).to_be_bytes());
    head.push(kind);
    w.write_all(&head).await?;
    w.write_all(payload).await?;
    w.flush().await
}

/// Splits an Annex-B byte stream into access units at access unit delimiters.
struct Splitter {
    buf: Vec<u8>,
}

impl Splitter {
    /// Offsets of the access unit delimiters (start code + NAL type 9) in `buf`.
    fn delimiters(&self, from: usize) -> Vec<usize> {
        let b = &self.buf;
        let mut out = Vec::new();
        let mut i = from;
        while i + 4 <= b.len() {
            if b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 && b[i + 3] & 0x1f == 9 {
                // Include a leading zero of a four-byte start code.
                out.push(if i > 0 && b[i - 1] == 0 { i - 1 } else { i });
                i += 4;
            } else {
                i += 1;
            }
        }
        out
    }

    /// Complete access units in the buffer; the unfinished tail stays.
    fn take(&mut self) -> Vec<Vec<u8>> {
        let d = self.delimiters(0);
        let mut units = Vec::new();
        if d.len() < 2 {
            return units;
        }
        for pair in d.windows(2) {
            units.push(self.buf[pair[0]..pair[1]].to_vec());
        }
        let last = *d.last().unwrap();
        self.buf.drain(..last);
        units
    }
}

/// Streams the focused window until the phone hangs up.
pub async fn run<R, W>(sh: Arc<Shared>, mut rd: R, mut wr: W, max_w: i64, max_h: i64)
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin,
{
    let _ = sh;
    // The phone sends nothing more; its closing the connection ends the stream.
    let mut hangup = tokio::spawn(async move {
        let mut b = [0u8; 64];
        while matches!(rd.read(&mut b).await, Ok(n) if n > 0) {}
    });
    let mut hardware = true;
    'outer: loop {
        let Some(region) = tokio::task::spawn_blocking(focused_region).await.ok().flatten() else {
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_millis(500)) => continue,
                _ = &mut hangup => break,
            }
        };
        let (w, h) = fit(&region, max_w, max_h);
        let Ok(mut child) = spawn_capture(&region, w, h, hardware) else { break };
        let Some(mut out) = child.stdout.take() else { break };
        let format = json!({ "width": w, "height": h, "window": { "class": region.class, "title": region.title } });
        if send(&mut wr, FORMAT, format.to_string().as_bytes()).await.is_err() {
            break;
        }
        let mut split = Splitter { buf: Vec::new() };
        let mut chunk = vec![0u8; 1 << 16];
        let mut check = tokio::time::interval(Duration::from_millis(300));
        let mut produced = false;
        loop {
            tokio::select! {
                n = out.read(&mut chunk) => {
                    match n {
                        Ok(0) | Err(_) => {
                            // An encoder that dies before its first frame: try the other one.
                            if !produced && hardware {
                                eprintln!("yaod: video: VAAPI capture failed, using libx264");
                                hardware = false;
                            } else {
                                tokio::time::sleep(Duration::from_millis(500)).await;
                            }
                            continue 'outer;
                        }
                        Ok(n) => {
                            split.buf.extend_from_slice(&chunk[..n]);
                            for unit in split.take() {
                                produced = true;
                                if send(&mut wr, FRAME, &unit).await.is_err() {
                                    break 'outer;
                                }
                            }
                        }
                    }
                }
                _ = check.tick() => {
                    let now = tokio::task::spawn_blocking(focused_region).await.ok().flatten();
                    if now.as_ref().map_or(false, |n| !n.same(&region)) {
                        let _ = child.kill().await;
                        continue 'outer;
                    }
                }
                _ = &mut hangup => break 'outer,
            }
        }
    }
    hangup.abort();
}
