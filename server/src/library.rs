//! The shortcut library sent to phones: the user's shortcuts plus the Hyprland
//! binds that carry a description.

use crate::config::{Shortcuts, Step};
use crate::keys;
use serde::Serialize;

#[derive(Clone, Debug, PartialEq)]
pub struct HyprBind {
    pub id: String,
    pub group: String,
    pub name: String,
    pub codes: Vec<u16>,
}

/// Parses `j/binds`. Keeps binds with a description, in the default submap,
/// on a keyboard key this layout table knows.
pub fn hypr_binds(json: &str) -> Vec<HyprBind> {
    let Ok(serde_json::Value::Array(binds)) = serde_json::from_str::<serde_json::Value>(json) else {
        return Vec::new();
    };
    let mut out: Vec<HyprBind> = Vec::new();
    for b in binds {
        let s = |k: &str| b.get(k).and_then(|v| v.as_str()).unwrap_or("");
        let flag = |k: &str| b.get(k).and_then(|v| v.as_bool()).unwrap_or(false);
        let description = s("description");
        if description.is_empty() || !s("submap").is_empty() || flag("mouse") || flag("catch_all") {
            continue;
        }
        let key = s("key");
        if key.starts_with("mouse") {
            continue;
        }
        let code = if key.is_empty() {
            match b.get("keycode").and_then(|v| v.as_u64()) {
                Some(kc) if kc > 8 => Some(kc as u16 - 8),
                _ => None,
            }
        } else {
            keys::xkb_to_code(key)
        };
        let Some(code) = code else { continue };
        let mask = b.get("modmask").and_then(|v| v.as_u64()).unwrap_or(0) as u32;
        let mut codes = keys::modmask_codes(mask);
        codes.push(code);
        let (group, name) = match description.split_once(": ") {
            Some((g, n)) => (g.to_string(), n.to_string()),
            None => (String::new(), description.to_string()),
        };
        let key_id = if key.is_empty() { format!("code:{}", code + 8) } else { key.to_string() };
        let id = format!("h:{mask}:{key_id}");
        if out.iter().any(|x| x.id == id) {
            continue;
        }
        out.push(HyprBind { id, group, name, codes });
    }
    out
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Shortcut {
    pub id: String,
    pub source: &'static str,
    pub app: String,
    pub group: String,
    pub name: String,
    pub keys: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub steps: Option<Vec<Step>>,
    pub favorite: bool,
}

pub fn steps_display(steps: &[Step]) -> String {
    match steps {
        [Step::Chord { chord }] => keys::chord_display(chord),
        [Step::Text { text }] => format!("“{text}”"),
        _ => format!("序列 · {} 步", steps.len()),
    }
}

pub fn build(user: &Shortcuts, hypr: &[HyprBind]) -> Vec<Shortcut> {
    let fav = |id: &str| user.favorites.iter().any(|f| f == id);
    let mut out: Vec<Shortcut> = user
        .shortcuts
        .iter()
        .map(|s| Shortcut {
            id: s.id.clone(),
            source: "user",
            app: s.app.clone(),
            group: String::new(),
            name: s.name.clone(),
            keys: steps_display(&s.steps),
            steps: Some(s.steps.clone()),
            favorite: fav(&s.id),
        })
        .collect();
    out.extend(hypr.iter().map(|h| Shortcut {
        id: h.id.clone(),
        source: "hyprland",
        app: String::new(),
        group: h.group.clone(),
        name: h.name.clone(),
        keys: keys::chord_display(&h.codes),
        steps: None,
        favorite: fav(&h.id),
    }));
    out
}

pub fn to_json(shortcuts: &[Shortcut]) -> serde_json::Value {
    serde_json::json!({ "shortcuts": shortcuts })
}
