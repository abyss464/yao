//! The uinput devices the phone drives: a keyboard, a relative pointer and a
//! multitouch touchpad that libinput treats like a real one.

use anyhow::{Context, Result};
use evdev::uinput::VirtualDevice;
use evdev::{
    AbsInfo, AbsoluteAxisCode, AttributeSet, BusType, EventType, InputEvent, InputId, KeyCode,
    PropType, RelativeAxisCode, UinputAbsSetup,
};
use std::time::{Duration, Instant};

pub const BTN_LEFT: u16 = 0x110;
pub const BTN_RIGHT: u16 = 0x111;
pub const BTN_MIDDLE: u16 = 0x112;
const BTN_TOOL_FINGER: u16 = 0x145;
const BTN_TOOL_QUINTTAP: u16 = 0x148;
const BTN_TOUCH: u16 = 0x14a;
const BTN_TOOL_DOUBLETAP: u16 = 0x14d;
const BTN_TOOL_TRIPLETAP: u16 = 0x14e;
const BTN_TOOL_QUADTAP: u16 = 0x14f;

const ABS_X: u16 = 0x00;
const ABS_Y: u16 = 0x01;
const ABS_MT_SLOT: u16 = 0x2f;
const ABS_MT_POSITION_X: u16 = 0x35;
const ABS_MT_POSITION_Y: u16 = 0x36;
const ABS_MT_TRACKING_ID: u16 = 0x39;

const SLOTS: usize = 10;

/// Name Hyprland shows for the keyboard (it lower-cases and dashes device names).
pub const KEYBOARD_HYPR_NAME: &str = "yao-keyboard";

/// A virtual device; in sandbox mode it is grabbed so nothing reaches the desktop.
struct Dev {
    name: &'static str,
    dev: VirtualDevice,
    _grab: Option<evdev::Device>,
    sandbox: bool,
}

impl Dev {
    fn new(name: &'static str, mut dev: VirtualDevice, sandbox: bool) -> Result<Dev> {
        let grab = if sandbox { Some(grab(&mut dev).with_context(|| format!("grabbing {name}"))?) } else { None };
        Ok(Dev { name, dev, _grab: grab, sandbox })
    }

    fn emit(&mut self, events: &[InputEvent]) {
        if events.is_empty() {
            return;
        }
        if self.sandbox {
            let list: Vec<String> = events
                .iter()
                .map(|e| format!("{}:{:#x}={}", type_name(e.event_type()), e.code(), e.value()))
                .collect();
            eprintln!("[sandbox] {}: {}", self.name, list.join(" "));
        }
        if let Err(e) = self.dev.emit(events) {
            eprintln!("yaod: {} emit failed: {e}", self.name);
        }
    }
}

fn type_name(t: EventType) -> &'static str {
    match t {
        EventType::KEY => "KEY",
        EventType::ABSOLUTE => "ABS",
        EventType::RELATIVE => "REL",
        _ => "EV",
    }
}

/// Grabs the device node of a freshly created uinput device. The node appears
/// asynchronously and is only readable once udev has set its permissions.
fn grab(dev: &mut VirtualDevice) -> Result<evdev::Device> {
    let deadline = Instant::now() + Duration::from_secs(3);
    loop {
        let mut last_err = None;
        if let Ok(nodes) = dev.enumerate_dev_nodes_blocking() {
            for node in nodes.flatten() {
                match evdev::Device::open(&node) {
                    Ok(mut d) => {
                        d.grab()?;
                        return Ok(d);
                    }
                    Err(e) => last_err = Some(e),
                }
            }
        }
        if Instant::now() > deadline {
            return Err(last_err.map(Into::into).unwrap_or_else(|| anyhow::anyhow!("no device node")));
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn key_ev(code: u16, value: i32) -> InputEvent {
    InputEvent::new(EventType::KEY.0, code, value)
}

fn abs_ev(code: u16, value: i32) -> InputEvent {
    InputEvent::new(EventType::ABSOLUTE.0, code, value)
}

fn rel_ev(code: u16, value: i32) -> InputEvent {
    InputEvent::new(EventType::RELATIVE.0, code, value)
}

#[derive(Clone, Copy)]
struct Contact {
    id: u8,
    tracking: i32,
    x: u16,
    y: u16,
}

struct Touchpad {
    dev: Dev,
    size: (u16, u16, u16, u16),
    slots: [Option<Contact>; SLOTS],
    next_tracking: i32,
    tool: u16,
    touching: bool,
    owner: u64,
}

pub struct Devices {
    sandbox: bool,
    keyboard: Dev,
    pointer: Dev,
    touchpad: Option<Touchpad>,
}

impl Devices {
    pub fn new(sandbox: bool) -> Result<Devices> {
        let mut keys = AttributeSet::<KeyCode>::new();
        // Every keyboard key; the BTN_* range in between is left out so libinput
        // classifies this as a keyboard only.
        for code in (1u16..=255).chain(0x160..=0x2bf) {
            keys.insert(KeyCode(code));
        }
        let keyboard = VirtualDevice::builder()?
            .name("Yao Keyboard")
            .input_id(InputId::new(BusType::BUS_USB, 0x1209, 0x7961, 1))
            .with_keys(&keys)?
            .build()
            .context("creating the virtual keyboard (is /dev/uinput writable?)")?;

        let mut buttons = AttributeSet::<KeyCode>::new();
        for b in [BTN_LEFT, BTN_RIGHT, BTN_MIDDLE] {
            buttons.insert(KeyCode(b));
        }
        let mut rel = AttributeSet::<RelativeAxisCode>::new();
        rel.insert(RelativeAxisCode::REL_X);
        rel.insert(RelativeAxisCode::REL_Y);
        let pointer = VirtualDevice::builder()?
            .name("Yao Pointer")
            .input_id(InputId::new(BusType::BUS_USB, 0x1209, 0x7962, 1))
            .with_keys(&buttons)?
            .with_relative_axes(&rel)?
            .build()
            .context("creating the virtual pointer")?;

        Ok(Devices {
            sandbox,
            keyboard: Dev::new("keyboard", keyboard, sandbox)?,
            pointer: Dev::new("pointer", pointer, sandbox)?,
            touchpad: None,
        })
    }

    pub fn key(&mut self, code: u16, down: bool) {
        self.keyboard.emit(&[key_ev(code, down as i32)]);
    }

    pub fn button(&mut self, code: u16, down: bool) {
        match self.touchpad.as_mut() {
            Some(tp) => tp.dev.emit(&[key_ev(code, down as i32)]),
            None => self.pointer.emit(&[key_ev(code, down as i32)]),
        }
    }

    pub fn motion(&mut self, dx: i32, dy: i32) {
        let mut ev = Vec::with_capacity(2);
        if dx != 0 {
            ev.push(rel_ev(RelativeAxisCode::REL_X.0, dx));
        }
        if dy != 0 {
            ev.push(rel_ev(RelativeAxisCode::REL_Y.0, dy));
        }
        self.pointer.emit(&ev);
    }

    /// (Re)creates the touchpad for a surface of `w`×`h` px at the given px/mm.
    pub fn touch_config(&mut self, w: u16, h: u16, rx: u16, ry: u16) -> Result<()> {
        let size = (w.max(2), h.max(2), rx.max(1), ry.max(1));
        if self.touchpad.as_ref().map_or(false, |t| t.size == size) {
            return Ok(());
        }
        self.touchpad = None;
        let (w, h, rx, ry) = size;
        let axis = |code: AbsoluteAxisCode, max: i32, res: i32| {
            UinputAbsSetup::new(code, AbsInfo::new(0, 0, max, 0, 0, res))
        };
        let mut keys = AttributeSet::<KeyCode>::new();
        for b in [
            BTN_LEFT,
            BTN_RIGHT,
            BTN_MIDDLE,
            BTN_TOUCH,
            BTN_TOOL_FINGER,
            BTN_TOOL_DOUBLETAP,
            BTN_TOOL_TRIPLETAP,
            BTN_TOOL_QUADTAP,
            BTN_TOOL_QUINTTAP,
        ] {
            keys.insert(KeyCode(b));
        }
        let mut props = AttributeSet::<PropType>::new();
        props.insert(PropType::POINTER);
        let dev = VirtualDevice::builder()?
            .name("Yao Touchpad")
            .input_id(InputId::new(BusType::BUS_USB, 0x1209, 0x7963, 1))
            .with_keys(&keys)?
            .with_properties(&props)?
            .with_absolute_axis(&axis(AbsoluteAxisCode::ABS_X, w as i32 - 1, rx as i32))?
            .with_absolute_axis(&axis(AbsoluteAxisCode::ABS_Y, h as i32 - 1, ry as i32))?
            .with_absolute_axis(&axis(AbsoluteAxisCode::ABS_MT_SLOT, SLOTS as i32 - 1, 0))?
            .with_absolute_axis(&axis(AbsoluteAxisCode::ABS_MT_TRACKING_ID, 65535, 0))?
            .with_absolute_axis(&axis(AbsoluteAxisCode::ABS_MT_POSITION_X, w as i32 - 1, rx as i32))?
            .with_absolute_axis(&axis(AbsoluteAxisCode::ABS_MT_POSITION_Y, h as i32 - 1, ry as i32))?
            .build()
            .context("creating the virtual touchpad")?;
        eprintln!("yaod: touchpad {w}x{h} px, {rx}x{ry} px/mm");
        self.touchpad = Some(Touchpad {
            dev: Dev::new("touchpad", dev, self.sandbox)?,
            size,
            slots: [None; SLOTS],
            next_tracking: 1,
            tool: 0,
            touching: false,
            owner: 0,
        });
        Ok(())
    }

    /// Applies the complete set of fingers currently on the phone's surface.
    pub fn touch(&mut self, session: u64, contacts: &[(u8, u16, u16)]) {
        let Some(tp) = self.touchpad.as_mut() else { return };
        tp.owner = session;
        let (w, h, _, _) = tp.size;
        let mut ev = Vec::new();
        // Lift contacts that are gone.
        for (slot, c) in tp.slots.iter_mut().enumerate() {
            if let Some(contact) = c {
                if !contacts.iter().any(|&(id, _, _)| id == contact.id) {
                    ev.push(abs_ev(ABS_MT_SLOT, slot as i32));
                    ev.push(abs_ev(ABS_MT_TRACKING_ID, -1));
                    *c = None;
                }
            }
        }
        for &(id, x, y) in contacts {
            let x = x.min(w - 1);
            let y = y.min(h - 1);
            if let Some(slot) = tp.slots.iter().position(|c| c.map_or(false, |c| c.id == id)) {
                let c = tp.slots[slot].as_mut().unwrap();
                if c.x != x || c.y != y {
                    ev.push(abs_ev(ABS_MT_SLOT, slot as i32));
                    if c.x != x {
                        ev.push(abs_ev(ABS_MT_POSITION_X, x as i32));
                    }
                    if c.y != y {
                        ev.push(abs_ev(ABS_MT_POSITION_Y, y as i32));
                    }
                    c.x = x;
                    c.y = y;
                }
            } else if let Some(slot) = tp.slots.iter().position(|c| c.is_none()) {
                let tracking = tp.next_tracking;
                tp.next_tracking = if tracking >= 65535 { 1 } else { tracking + 1 };
                tp.slots[slot] = Some(Contact { id, tracking, x, y });
                ev.push(abs_ev(ABS_MT_SLOT, slot as i32));
                ev.push(abs_ev(ABS_MT_TRACKING_ID, tracking));
                ev.push(abs_ev(ABS_MT_POSITION_X, x as i32));
                ev.push(abs_ev(ABS_MT_POSITION_Y, y as i32));
            }
        }
        Self::finish_touch_frame(tp, &mut ev);
        tp.dev.emit(&ev);
    }

    /// Single-touch axes, BTN_TOUCH and the finger-count tool for the current slots.
    fn finish_touch_frame(tp: &mut Touchpad, ev: &mut Vec<InputEvent>) {
        let active: Vec<&Contact> = tp.slots.iter().flatten().collect();
        let n = active.len();
        let tool = match n {
            0 => 0,
            1 => BTN_TOOL_FINGER,
            2 => BTN_TOOL_DOUBLETAP,
            3 => BTN_TOOL_TRIPLETAP,
            4 => BTN_TOOL_QUADTAP,
            _ => BTN_TOOL_QUINTTAP,
        };
        if let Some(first) = active.first() {
            ev.push(abs_ev(ABS_X, first.x as i32));
            ev.push(abs_ev(ABS_Y, first.y as i32));
        }
        if tool != tp.tool {
            if tp.tool != 0 {
                ev.push(key_ev(tp.tool, 0));
            }
            if tool != 0 {
                ev.push(key_ev(tool, 1));
            }
            tp.tool = tool;
        }
        let touching = n > 0;
        if touching != tp.touching {
            ev.push(key_ev(BTN_TOUCH, touching as i32));
            tp.touching = touching;
        }
    }

    /// Lifts every finger, if `session` was the one touching.
    pub fn release_touches(&mut self, session: u64) {
        let owned = self.touchpad.as_ref().map_or(false, |t| t.owner == session);
        if owned {
            self.touch(session, &[]);
        }
    }
}
