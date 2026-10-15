//! One phone connection: authentication, input frames, state feeds.

use crate::devices::{BTN_LEFT, BTN_MIDDLE, BTN_RIGHT};
use crate::Shared;
use base64::Engine;
use rand::{Rng, RngCore};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio_rustls::TlsAcceptor;
use yao::config::{self, Device, Devices, Shortcuts, Step, UserShortcut};
use yao::keys;
use yao::proto::{self, Cursor};

const RELEASE_AFTER: Duration = Duration::from_secs(1);
const CLOSE_AFTER: Duration = Duration::from_secs(10);
const PAIR_TTL: Duration = Duration::from_secs(60);
/// Bounds of the delay (beyond the fastest delivery) at which a stroke starts.
const TOUCH_MIN_DELAY_US: i64 = 4_000;
const TOUCH_MAX_DELAY_US: i64 = 80_000;

/// A snapshot of the fingers on the phone; `time` is the phone's clock in ms,
/// `None` for a release made up here (emitted as soon as it is dequeued).
struct TouchFrame {
    time: Option<u32>,
    arrived: std::time::Instant,
    contacts: Vec<(u8, u16, u16)>,
}

/// Work that must keep its order: keys, chords, text, shortcut steps.
enum Action {
    Key(u16, bool),
    Chord(Vec<u16>),
    Text(String),
    Preedit(String),
    Steps(Vec<Step>),
}

struct Pending {
    id: String,
    name: String,
    code: String,
    expires: Instant,
    attempts: u8,
}

struct Session {
    sh: Arc<Shared>,
    sid: u64,
    out: mpsc::UnboundedSender<Vec<u8>>,
    actions: mpsc::UnboundedSender<Action>,
    touches: std::sync::mpsc::Sender<TouchFrame>,
    held: Arc<Mutex<HashSet<u16>>>,
    buttons: HashSet<u16>,
    device: Option<String>,
    pending: Option<Pending>,
    feeds: Option<JoinHandle<()>>,
}

pub async fn run(sh: Arc<Shared>, tcp: TcpStream, peer: SocketAddr, acceptor: TlsAcceptor) {
    let _ = tcp.set_nodelay(true);
    let tls = match tokio::time::timeout(Duration::from_secs(10), acceptor.accept(tcp)).await {
        Ok(Ok(s)) => s,
        _ => return,
    };
    let (mut rd, mut wr) = tokio::io::split(tls);

    // The first frame says what this connection is: a video stream, or a session.
    let first = match tokio::time::timeout(Duration::from_secs(10), proto::read_frame(&mut rd)).await {
        Ok(Ok(Some(f))) => f,
        _ => return,
    };
    if first.0 == proto::VIDEO_OPEN {
        let v: Value = serde_json::from_slice(&first.1).unwrap_or(Value::Null);
        let id = v["id"].as_str().unwrap_or("");
        let token = v["token"].as_str().unwrap_or("");
        let known = Devices::load(&sh.paths)
            .map(|d| d.devices.iter().any(|d| d.id == id && d.token_sha256 == token_hash(token)))
            .unwrap_or(false);
        if !id.is_empty() && known {
            let w = v["width"].as_i64().unwrap_or(1280).clamp(64, 3840);
            let h = v["height"].as_i64().unwrap_or(720).clamp(64, 3840);
            eprintln!("yaod: video to {peer} ({w}x{h} max)");
            crate::video::run(sh, rd, wr, w, h).await;
            eprintln!("yaod: video to {peer} ended");
        }
        return;
    }

    let (out_tx, mut out_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let writer = tokio::spawn(async move {
        while let Some(frame) = out_rx.recv().await {
            if wr.write_all(&frame).await.is_err() || wr.flush().await.is_err() {
                break;
            }
        }
        let _ = wr.shutdown().await;
    });

    // Frames are read in their own task: a read cut off by a timeout would lose bytes.
    let (in_tx, mut in_rx) = mpsc::unbounded_channel::<(u8, Vec<u8>)>();
    let reader = tokio::spawn(async move {
        while let Ok(Some(frame)) = proto::read_frame(&mut rd).await {
            if in_tx.send(frame).is_err() {
                break;
            }
        }
    });

    let sid = sh.next_session.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let held = Arc::new(Mutex::new(HashSet::new()));
    let (act_tx, act_rx) = mpsc::unbounded_channel();
    let executor = tokio::spawn(execute(sh.clone(), held.clone(), act_rx));
    let (touch_tx, touch_rx) = std::sync::mpsc::channel();
    let pacer = {
        let sh = sh.clone();
        std::thread::spawn(move || pace_touches(sh, sid, touch_rx))
    };
    let mut s = Session {
        sh,
        sid,
        out: out_tx,
        actions: act_tx,
        touches: touch_tx,
        held,
        buttons: HashSet::new(),
        device: None,
        pending: None,
        feeds: None,
    };
    eprintln!("yaod: session {sid} from {peer}");

    let mut last_heard = Instant::now();
    let mut released = false;
    if !s.handle(first.0, &first.1) {
        s.release_all();
        return;
    }
    let mut last_check = Instant::now();
    let mut tick = tokio::time::interval(Duration::from_millis(250));
    let reason;
    loop {
        tokio::select! {
            frame = in_rx.recv() => {
                let Some((kind, payload)) = frame else { reason = "the phone closed the connection"; break };
                last_heard = Instant::now();
                released = false;
                if !s.handle(kind, &payload) {
                    reason = "refused";
                    break;
                }
            }
            _ = tick.tick() => {
                let silent = last_heard.elapsed();
                if silent >= RELEASE_AFTER && !released {
                    s.release_all();
                    released = true;
                }
                if silent >= CLOSE_AFTER {
                    reason = "silent for 10 s";
                    break;
                }
                if last_check.elapsed() >= Duration::from_secs(5) {
                    last_check = Instant::now();
                    if !s.still_paired() {
                        s.send(proto::error("unpaired", "this phone was removed on the computer"));
                        reason = "unpaired on the computer";
                        break;
                    }
                }
            }
        }
    }

    if let Some(f) = s.feeds.take() {
        f.abort();
    }
    reader.abort();
    s.release_all();
    let sh = s.sh.clone();
    drop(s);
    // The executor ends once its queue drains, clearing any preedit it showed.
    let _ = tokio::time::timeout(Duration::from_secs(2), executor).await;
    // Let queued touch frames play out, then make sure every finger is lifted.
    let _ = tokio::time::timeout(Duration::from_millis(500), tokio::task::spawn_blocking(move || pacer.join())).await;
    sh.devices.lock().unwrap().release_touches(sid);
    let _ = tokio::time::timeout(Duration::from_secs(1), writer).await;
    eprintln!("yaod: session {sid} closed: {reason}");
}

impl Session {
    fn send(&self, frame: Vec<u8>) {
        let _ = self.out.send(frame);
    }

    fn act(&self, a: Action) {
        let _ = self.actions.send(a);
    }

    /// Handles one frame; false closes the session.
    fn handle(&mut self, kind: u8, p: &[u8]) -> bool {
        match kind {
            proto::HELLO => return self.hello(p),
            proto::PAIR_REQUEST => return self.pair_request(p),
            proto::PAIR_CODE => return self.pair_code(p),
            proto::PING => {
                self.send(proto::encode(proto::PONG, p));
                return true;
            }
            _ => {}
        }
        if self.device.is_none() {
            self.send(proto::error("unauthenticated", "pair or say hello first"));
            return false;
        }
        let mut c = Cursor::new(p);
        match kind {
            proto::TOUCH_CONFIG => {
                if let (Some(w), Some(h), Some(rx), Some(ry)) = (c.u16(), c.u16(), c.u16(), c.u16()) {
                    if let Err(e) = self.sh.devices.lock().unwrap().touch_config(w, h, rx, ry) {
                        eprintln!("yaod: {e:#}");
                    }
                }
            }
            proto::TOUCH => {
                let (Some(hi), Some(lo)) = (c.u16(), c.u16()) else { return true };
                let time = (hi as u32) << 16 | lo as u32;
                let n = c.u8().unwrap_or(0) as usize;
                let mut contacts = Vec::with_capacity(n);
                for _ in 0..n {
                    match (c.u8(), c.u16(), c.u16()) {
                        (Some(id), Some(x), Some(y)) => contacts.push((id, x, y)),
                        _ => break,
                    }
                }
                let _ = self.touches.send(TouchFrame { time: Some(time), arrived: Instant::now(), contacts });
            }
            proto::BUTTON => {
                let button = match c.u8() {
                    Some(1) => BTN_LEFT,
                    Some(2) => BTN_RIGHT,
                    Some(3) => BTN_MIDDLE,
                    _ => return true,
                };
                let down = c.u8() == Some(1);
                if down {
                    self.buttons.insert(button);
                } else {
                    self.buttons.remove(&button);
                }
                self.sh.devices.lock().unwrap().button(button, down);
            }
            proto::KEY => {
                if let (Some(code), Some(state)) = (c.u16(), c.u8()) {
                    self.act(Action::Key(code, state == 1));
                }
            }
            proto::MOTION => {
                if let (Some(dx), Some(dy)) = (c.i16(), c.i16()) {
                    self.sh.devices.lock().unwrap().motion(dx as i32, dy as i32);
                }
            }
            proto::CHORD => {
                let n = c.u8().unwrap_or(0);
                let codes: Vec<u16> = (0..n).map_while(|_| c.u16()).collect();
                if !codes.is_empty() {
                    self.act(Action::Chord(codes));
                }
            }
            proto::TEXT => {
                let text = String::from_utf8_lossy(p).into_owned();
                if !text.is_empty() {
                    self.act(Action::Text(text));
                }
            }
            proto::PREEDIT => self.act(Action::Preedit(String::from_utf8_lossy(p).into_owned())),
            proto::RUN => self.run_shortcut(p),
            proto::WORKSPACE => {
                if let Some(id) = self.json(p).and_then(|v| v["id"].as_i64()) {
                    if self.sh.sandbox {
                        eprintln!("[sandbox] workspace {id}");
                    } else {
                        tokio::task::spawn_blocking(move || yao::hypr::focus_workspace(id));
                    }
                }
            }
            proto::SHORTCUT_PUT => self.shortcut_put(p),
            proto::SHORTCUT_DELETE => self.shortcut_delete(p),
            proto::FAVORITE => self.favorite(p),
            _ => self.send(proto::error("bad_request", &format!("unknown frame type {kind:#x}"))),
        }
        true
    }

    fn json(&self, p: &[u8]) -> Option<Value> {
        let v = serde_json::from_slice::<Value>(p).ok().filter(|v| v.is_object());
        if v.is_none() {
            self.send(proto::error("bad_request", "expected a JSON object"));
        }
        v
    }

    fn hello(&mut self, p: &[u8]) -> bool {
        let Some(v) = self.json(p) else { return false };
        let id = v["id"].as_str().unwrap_or("");
        let token = v["token"].as_str().unwrap_or("");
        let known = Devices::load(&self.sh.paths)
            .map(|d| d.devices.iter().any(|d| d.id == id && d.token_sha256 == token_hash(token)))
            .unwrap_or(false);
        if id.is_empty() || !known {
            self.send(proto::error("unpaired", "this phone is not paired with this computer"));
            return false;
        }
        self.device = Some(id.to_string());
        eprintln!("yaod: session {} is {}", self.sid, v["name"].as_str().unwrap_or(id));
        self.send(proto::encode_json(proto::WELCOME, &json!({ "host": self.sh.host })));
        self.start_feeds();
        true
    }

    fn pair_request(&mut self, p: &[u8]) -> bool {
        let Some(v) = self.json(p) else { return false };
        let id = v["id"].as_str().unwrap_or("").to_string();
        if id.is_empty() {
            self.send(proto::error("bad_request", "missing id"));
            return true;
        }
        let name = v["name"].as_str().filter(|n| !n.is_empty()).unwrap_or("手机").to_string();
        let code = format!("{:06}", rand::thread_rng().gen_range(0..1_000_000));
        if self.sh.sandbox {
            eprintln!("[sandbox] pairing code for {name}: {code}");
        } else {
            let _ = tokio::process::Command::new("notify-send")
                .args(["-a", "遥", "-u", "critical", "-t", "60000", "遥 · 配对"])
                .arg(format!("{name} 请求连接\n配对码  {} {}", &code[..3], &code[3..]))
                .spawn();
        }
        self.pending = Some(Pending { id, name, code, expires: Instant::now() + PAIR_TTL, attempts: 0 });
        self.send(proto::encode_json(proto::PAIR_PENDING, &json!({ "expires_in": PAIR_TTL.as_secs() })));
        true
    }

    fn pair_code(&mut self, p: &[u8]) -> bool {
        let Some(v) = self.json(p) else { return false };
        let code = v["code"].as_str().unwrap_or("").trim().to_string();
        let Some(pending) = self.pending.as_mut() else {
            self.send(proto::error("expired", "ask for a new code"));
            return true;
        };
        if Instant::now() > pending.expires {
            self.pending = None;
            self.send(proto::error("expired", "the code expired"));
            return true;
        }
        if code != pending.code {
            pending.attempts += 1;
            if pending.attempts >= 3 {
                self.pending = None;
                self.send(proto::error("expired", "too many wrong codes"));
            } else {
                self.send(proto::error("bad_code", "wrong code"));
            }
            return true;
        }
        let pending = self.pending.take().unwrap();
        let mut raw = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut raw);
        let token = base64::engine::general_purpose::STANDARD.encode(raw);
        let stored = {
            let _g = self.sh.store_lock.lock().unwrap();
            Devices::load(&self.sh.paths).and_then(|mut d| {
                d.devices.retain(|x| x.id != pending.id);
                d.devices.push(Device {
                    id: pending.id.clone(),
                    name: pending.name.clone(),
                    token_sha256: token_hash(&token),
                    paired_at: config::now_rfc3339(),
                });
                d.save(&self.sh.paths)
            })
        };
        if let Err(e) = stored {
            eprintln!("yaod: saving the paired device: {e:#}");
            self.send(proto::error("bad_request", "could not save the pairing"));
            return true;
        }
        eprintln!("yaod: paired {}", pending.name);
        self.device = Some(pending.id);
        self.send(proto::encode_json(proto::PAIR_OK, &json!({ "token": token, "host": self.sh.host })));
        self.start_feeds();
        true
    }

    fn still_paired(&self) -> bool {
        let Some(id) = &self.device else { return true };
        Devices::load(&self.sh.paths).map(|d| d.devices.iter().any(|d| &d.id == id)).unwrap_or(true)
    }

    /// Sends STATE and LIBRARY now and again whenever they change.
    fn start_feeds(&mut self) {
        if self.feeds.is_some() {
            return;
        }
        let mut state = self.sh.state.subscribe();
        let mut library = self.sh.library.subscribe();
        let out = self.out.clone();
        let sh = self.sh.clone();
        tokio::task::spawn_blocking(move || sh.refresh_leds());
        self.feeds = Some(tokio::spawn(async move {
            let send_state = |s: &crate::State| {
                proto::encode_json(proto::STATE, &serde_json::to_value(s).unwrap_or(Value::Null))
            };
            let _ = out.send(send_state(&state.borrow_and_update()));
            let _ = out.send(proto::encode_json(proto::LIBRARY, &library.borrow_and_update()));
            loop {
                tokio::select! {
                    r = state.changed() => {
                        if r.is_err() { break }
                        let frame = send_state(&state.borrow_and_update());
                        if out.send(frame).is_err() { break }
                    }
                    r = library.changed() => {
                        if r.is_err() { break }
                        let frame = proto::encode_json(proto::LIBRARY, &library.borrow_and_update());
                        if out.send(frame).is_err() { break }
                    }
                }
            }
        }));
    }

    fn release_all(&mut self) {
        let keys: Vec<u16> = self.held.lock().unwrap().drain().collect();
        let mut d = self.sh.devices.lock().unwrap();
        for k in keys {
            d.key(k, false);
        }
        for b in self.buttons.drain() {
            d.button(b, false);
        }
        drop(d);
        let _ = self.touches.send(TouchFrame { time: None, arrived: Instant::now(), contacts: Vec::new() });
    }

    fn run_shortcut(&self, p: &[u8]) {
        let Some(v) = self.json(p) else { return };
        let id = v["id"].as_str().unwrap_or("");
        if id.starts_with("h:") {
            let codes = self.sh.binds.lock().unwrap().iter().find(|b| b.id == id).map(|b| b.codes.clone());
            match codes {
                Some(codes) => self.act(Action::Chord(codes)),
                None => self.send(proto::error("bad_request", "no such Hyprland bind")),
            }
            return;
        }
        let steps = Shortcuts::load(&self.sh.paths)
            .ok()
            .and_then(|s| s.shortcuts.into_iter().find(|s| s.id == id))
            .map(|s| s.steps);
        match steps {
            Some(steps) => self.act(Action::Steps(steps)),
            None => self.send(proto::error("bad_request", "no such shortcut")),
        }
    }

    /// Read-modify-write of shortcuts.toml, then a fresh LIBRARY for every phone.
    fn edit_shortcuts(&self, f: impl FnOnce(&mut Shortcuts) -> Result<(), &'static str>) {
        let result = {
            let _g = self.sh.store_lock.lock().unwrap();
            match Shortcuts::load(&self.sh.paths) {
                Ok(mut s) => match f(&mut s) {
                    Ok(()) => s.save(&self.sh.paths).map_err(|e| format!("{e:#}")),
                    Err(e) => Err(e.to_string()),
                },
                Err(e) => Err(format!("{e:#}")),
            }
        };
        match result {
            Ok(()) => self.sh.rebuild_library(),
            Err(e) => self.send(proto::error("bad_request", &e)),
        }
    }

    fn shortcut_put(&self, p: &[u8]) {
        let Some(v) = self.json(p) else { return };
        let Ok(steps) = serde_json::from_value::<Vec<Step>>(v["steps"].clone()) else {
            self.send(proto::error("bad_request", "bad steps"));
            return;
        };
        let name = v["name"].as_str().unwrap_or("").trim().to_string();
        let app = v["app"].as_str().unwrap_or("").trim().to_string();
        let id = v["id"].as_str().map(str::to_string);
        self.edit_shortcuts(|s| {
            if name.is_empty() || steps.is_empty() {
                return Err("a shortcut needs a name and at least one step");
            }
            match id.and_then(|id| s.shortcuts.iter_mut().find(|x| x.id == id)) {
                Some(existing) => {
                    existing.name = name;
                    existing.app = app;
                    existing.steps = steps;
                }
                None => {
                    let mut raw = [0u8; 8];
                    rand::thread_rng().fill_bytes(&mut raw);
                    s.shortcuts.push(UserShortcut { id: format!("u:{}", hex::encode(raw)), app, name, steps });
                }
            }
            Ok(())
        });
    }

    fn shortcut_delete(&self, p: &[u8]) {
        let Some(v) = self.json(p) else { return };
        let id = v["id"].as_str().unwrap_or("").to_string();
        self.edit_shortcuts(|s| {
            s.shortcuts.retain(|x| x.id != id);
            s.favorites.retain(|x| *x != id);
            Ok(())
        });
    }

    fn favorite(&self, p: &[u8]) {
        let Some(v) = self.json(p) else { return };
        let id = v["id"].as_str().unwrap_or("").to_string();
        let on = v["favorite"].as_bool().unwrap_or(false);
        self.edit_shortcuts(|s| {
            s.favorites.retain(|x| *x != id);
            if on && !id.is_empty() {
                s.favorites.push(id);
            }
            Ok(())
        });
    }
}

/// Replays touch frames on the phone's own timeline: frame `t` is emitted at
/// `t + delay`. Within one stroke the spacing between frames is never compressed:
/// a frame that arrives too late pushes the delay up for the rest of the stroke
/// instead. libinput derives speed from event times, and frames bunched together
/// read to it as jumps and are dropped. Each new stroke re-anchors the delay just
/// above the fastest delivery seen lately plus the link's jitter. Runs on its own
/// thread for sub-millisecond sleeps.
fn pace_touches(sh: Arc<Shared>, sid: u64, rx: std::sync::mpsc::Receiver<TouchFrame>) {
    let start = std::time::Instant::now();
    let us = |i: std::time::Instant| i.saturating_duration_since(start).as_micros() as i64;
    // (arrival µs, arrival - phone time) over the last three seconds.
    let mut offsets: std::collections::VecDeque<(i64, i64)> = std::collections::VecDeque::new();
    let mut delay: Option<i64> = None;
    let mut last_phone: Option<u32> = None;
    let (mut frames, mut late, mut max_delay) = (0u64, 0u64, 0i64);
    // Strokes by the most fingers they had (1..=5+).
    let (mut strokes, mut stroke_max) = ([0u64; 5], 0usize);

    while let Ok(f) = rx.recv() {
        let Some(t) = f.time else {
            sh.devices.lock().unwrap().touch(sid, &f.contacts);
            delay = None;
            continue;
        };
        frames += 1;
        let arrived = us(f.arrived);
        let phone = t as i64 * 1000;
        if last_phone.map_or(false, |l| t < l || t - l > 60_000) {
            offsets.clear();
            delay = None;
        }
        last_phone = Some(t);
        offsets.push_back((arrived, arrived - phone));
        while offsets.front().map_or(false, |&(at, _)| arrived - at > 3_000_000) {
            offsets.pop_front();
        }
        let d = *delay.get_or_insert_with(|| {
            let mut sorted: Vec<i64> = offsets.iter().map(|&(_, o)| o).collect();
            sorted.sort_unstable();
            let base = sorted[0];
            let jitter = sorted[(sorted.len() * 9 / 10).min(sorted.len() - 1)] - base;
            base + (jitter + 3_000).clamp(TOUCH_MIN_DELAY_US, TOUCH_MAX_DELAY_US)
        });
        let now = us(std::time::Instant::now());
        let mut due = phone + d;
        if due < now {
            late += 1;
            delay = Some(d + (now - due) + 1_000);
            due = now + 1_000;
        }
        max_delay = max_delay.max(due - phone - offsets.iter().map(|&(_, o)| o).min().unwrap_or(0));
        if due > now {
            std::thread::sleep(Duration::from_micros((due - now) as u64));
        }
        sh.devices.lock().unwrap().touch(sid, &f.contacts);
        stroke_max = stroke_max.max(f.contacts.len());
        if f.contacts.is_empty() {
            delay = None;
            if stroke_max > 0 {
                strokes[stroke_max.min(5) - 1] += 1;
            }
            stroke_max = 0;
        }
    }
    if frames > 0 {
        eprintln!(
            "yaod: session {sid} touch: {frames} frames, {late} late, added delay up to {} ms; \
             strokes by fingers 1-5: {strokes:?}",
            max_delay / 1000
        );
    }
}

fn token_hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

async fn execute(sh: Arc<Shared>, held: Arc<Mutex<HashSet<u16>>>, mut rx: mpsc::UnboundedReceiver<Action>) {
    // Whether this phone's composing text is showing in the computer's focused field.
    let mut preedit = false;
    while let Some(action) = rx.recv().await {
        match action {
            Action::Key(code, down) => {
                sh.devices.lock().unwrap().key(code, down);
                {
                    let mut h = held.lock().unwrap();
                    if down {
                        h.insert(code);
                    } else {
                        h.remove(&code);
                    }
                }
                if !down && is_lock_key(code) {
                    refresh_leds_soon(&sh);
                }
            }
            Action::Chord(codes) => chord(&sh, &codes).await,
            Action::Text(text) => commit_text(&sh, &mut preedit, &text).await,
            Action::Preedit(text) => show_preedit(&sh, &mut preedit, &text).await,
            Action::Steps(steps) => {
                for step in steps {
                    match step {
                        Step::Chord { chord: codes } => chord(&sh, &codes).await,
                        Step::Text { text } => commit_text(&sh, &mut preedit, &text).await,
                        Step::Wait { wait_ms } => {
                            tokio::time::sleep(Duration::from_millis(wait_ms.min(10_000))).await
                        }
                    }
                }
            }
        }
    }
    if preedit {
        show_preedit(&sh, &mut preedit, "").await;
    }
}

fn is_lock_key(code: u16) -> bool {
    code == keys::KEY_CAPSLOCK || code == keys::KEY_NUMLOCK
}

fn refresh_leds_soon(sh: &Arc<Shared>) {
    let sh = sh.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(40)).await;
        let _ = tokio::task::spawn_blocking(move || sh.refresh_leds()).await;
    });
}

async fn chord(sh: &Arc<Shared>, codes: &[u16]) {
    let gap = Duration::from_millis(8);
    for &c in codes {
        sh.devices.lock().unwrap().key(c, true);
        tokio::time::sleep(gap).await;
    }
    for &c in codes.iter().rev() {
        sh.devices.lock().unwrap().key(c, false);
        tokio::time::sleep(gap).await;
    }
    if codes.iter().any(|&c| is_lock_key(c)) {
        refresh_leds_soon(sh);
    }
}

/// Puts text the phone has already composed into the computer's focused field,
/// through fcitx5 (the channel apps take composed text from); typed with wtype only
/// when fcitx5 has no focused input context to give it to.
async fn commit_text(sh: &Arc<Shared>, preedit: &mut bool, text: &str) {
    let _g = sh.text_lock.lock().await;
    if sh.sandbox {
        eprintln!("[sandbox] text: {text:?}");
        *preedit = false;
        return;
    }
    let committed = if std::mem::take(preedit) {
        sh.ime.commit(text).await
    } else {
        sh.ime.begin().await && sh.ime.commit(text).await
    };
    if !committed {
        type_text(sh, text).await;
    }
}

/// Shows the phone's composing text (pinyin, a word in progress) underlined in the
/// computer's focused field; empty clears it.
async fn show_preedit(sh: &Arc<Shared>, preedit: &mut bool, text: &str) {
    let _g = sh.text_lock.lock().await;
    if sh.sandbox {
        eprintln!("[sandbox] preedit: {text:?}");
        return;
    }
    if text.is_empty() {
        if std::mem::take(preedit) {
            sh.ime.cancel().await;
        }
        return;
    }
    if !*preedit {
        if !sh.ime.begin().await {
            return;
        }
        *preedit = true;
    }
    if !sh.ime.set_preedit(text).await {
        *preedit = false;
    }
}

/// Typing through a virtual keyboard: the fallback for fields fcitx5 does not serve.
/// The computer's own input method must not reinterpret the text (Rime in Chinese
/// mode would take the letters as pinyin), so an active fcitx5 is switched off
/// around the typing and back on after.
async fn type_text(_sh: &Arc<Shared>, text: &str) {
    let fcitx = |arg: Option<&'static str>| async move {
        let mut c = tokio::process::Command::new("fcitx5-remote");
        if let Some(a) = arg {
            c.arg(a);
        }
        c.stderr(std::process::Stdio::null()).output().await.ok()
    };
    let was_active = fcitx(None)
        .await
        .map_or(false, |o| String::from_utf8_lossy(&o.stdout).trim() == "2");
    if was_active {
        fcitx(Some("-c")).await;
    }
    match tokio::process::Command::new("wtype").arg("--").arg(text).status().await {
        Ok(s) if s.success() => {}
        Ok(s) => eprintln!("yaod: wtype exited with {s}"),
        Err(e) => eprintln!("yaod: wtype: {e}"),
    }
    if was_active {
        fcitx(Some("-o")).await;
    }
}
