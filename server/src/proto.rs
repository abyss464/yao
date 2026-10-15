//! Frame types and framing (see PROTOCOL.md).

use tokio::io::{AsyncRead, AsyncReadExt};

pub const HELLO: u8 = 0x01;
pub const PAIR_REQUEST: u8 = 0x02;
pub const PAIR_CODE: u8 = 0x03;
pub const TOUCH_CONFIG: u8 = 0x10;
pub const TOUCH: u8 = 0x11;
pub const BUTTON: u8 = 0x12;
pub const KEY: u8 = 0x13;
pub const MOTION: u8 = 0x14;
pub const CHORD: u8 = 0x15;
pub const TEXT: u8 = 0x20;
pub const RUN: u8 = 0x21;
pub const SHORTCUT_PUT: u8 = 0x22;
pub const SHORTCUT_DELETE: u8 = 0x23;
pub const FAVORITE: u8 = 0x24;
pub const PING: u8 = 0x30;

pub const WELCOME: u8 = 0x81;
pub const PAIR_PENDING: u8 = 0x82;
pub const PAIR_OK: u8 = 0x83;
pub const ERROR: u8 = 0x84;
pub const PONG: u8 = 0x90;
pub const STATE: u8 = 0x91;
pub const LIBRARY: u8 = 0x92;

pub fn encode(kind: u8, payload: &[u8]) -> Vec<u8> {
    let len = payload.len() + 1;
    assert!(len <= u16::MAX as usize, "frame too large");
    let mut out = Vec::with_capacity(len + 2);
    out.extend_from_slice(&(len as u16).to_be_bytes());
    out.push(kind);
    out.extend_from_slice(payload);
    out
}

pub fn encode_json(kind: u8, value: &serde_json::Value) -> Vec<u8> {
    encode(kind, value.to_string().as_bytes())
}

pub fn error(code: &str, message: &str) -> Vec<u8> {
    encode_json(ERROR, &serde_json::json!({ "code": code, "message": message }))
}

/// Reads one frame; `Ok(None)` at a clean end of stream.
pub async fn read_frame<R: AsyncRead + Unpin>(r: &mut R) -> std::io::Result<Option<(u8, Vec<u8>)>> {
    let mut len = [0u8; 2];
    match r.read_exact(&mut len).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = u16::from_be_bytes(len) as usize;
    if len == 0 {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "empty frame"));
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).await?;
    let kind = buf[0];
    buf.remove(0);
    Ok(Some((kind, buf)))
}

/// Big-endian cursor over a binary payload.
pub struct Cursor<'a> {
    buf: &'a [u8],
}

impl<'a> Cursor<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Cursor { buf }
    }
    pub fn u8(&mut self) -> Option<u8> {
        let (&b, rest) = self.buf.split_first()?;
        self.buf = rest;
        Some(b)
    }
    pub fn u16(&mut self) -> Option<u16> {
        if self.buf.len() < 2 {
            return None;
        }
        let v = u16::from_be_bytes([self.buf[0], self.buf[1]]);
        self.buf = &self.buf[2..];
        Some(v)
    }
    pub fn i16(&mut self) -> Option<i16> {
        self.u16().map(|v| v as i16)
    }
}
