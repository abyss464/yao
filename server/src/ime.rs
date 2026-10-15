//! Text through the desktop's input method: fcitx5's `wechatvoice` addon commits a
//! string to, or shows preedit in, the focused input context — the same channel
//! apps take composed text from, so nothing is re-interpreted as keystrokes.
//!
//! D-Bus: org.fcitx.Fcitx5 /wechatvoice org.fcitx.Fcitx.WechatVoice1
//!   Begin() -> b, SetPreedit(s), Commit(s), Cancel()

use tokio::sync::Mutex;

const SERVICE: &str = "org.fcitx.Fcitx5";
const PATH: &str = "/wechatvoice";
const INTERFACE: &str = "org.fcitx.Fcitx.WechatVoice1";

#[derive(Default)]
pub struct Fcitx {
    conn: Mutex<Option<zbus::Connection>>,
}

impl Fcitx {
    async fn connection(&self) -> Option<zbus::Connection> {
        let mut c = self.conn.lock().await;
        if c.is_none() {
            *c = zbus::Connection::session().await.ok();
        }
        c.clone()
    }

    async fn call<B>(&self, method: &str, body: &B) -> Option<zbus::Message>
    where
        B: serde::Serialize + zbus::zvariant::DynamicType,
    {
        let conn = self.connection().await?;
        match conn.call_method(Some(SERVICE), PATH, Some(INTERFACE), method, body).await {
            Ok(m) => Some(m),
            Err(e) => {
                eprintln!("yaod: fcitx5 {method}: {e}");
                if matches!(e, zbus::Error::InputOutput(_)) {
                    *self.conn.lock().await = None;
                }
                None
            }
        }
    }

    /// Targets the focused input context; false when there is none (or no addon).
    pub async fn begin(&self) -> bool {
        match self.call("Begin", &()).await {
            Some(m) => m.body().deserialize::<bool>().unwrap_or(false),
            None => false,
        }
    }

    pub async fn set_preedit(&self, text: &str) -> bool {
        self.call("SetPreedit", &(text,)).await.is_some()
    }

    pub async fn commit(&self, text: &str) -> bool {
        self.call("Commit", &(text,)).await.is_some()
    }

    pub async fn cancel(&self) {
        self.call("Cancel", &()).await;
    }
}
