//! yao: manage the Yao daemon's paired phones and inspect its shortcut library.

use anyhow::{bail, Result};
use yao::config::{Devices, Paths, Shortcuts};
use yao::{hypr, library};

fn usage() -> ! {
    eprintln!(
        "yao devices              list paired phones\n\
         yao revoke <id|name>     unpair a phone (it is disconnected within seconds)\n\
         yao library              print the shortcut library phones receive"
    );
    std::process::exit(2)
}

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let paths = Paths::standard();
    match args.first().map(String::as_str) {
        Some("devices") => {
            let d = Devices::load(&paths)?;
            if d.devices.is_empty() {
                println!("没有已配对的手机");
            }
            for dev in &d.devices {
                println!("{}  {}  {}", &dev.id[..dev.id.len().min(8)], dev.paired_at, dev.name);
            }
        }
        Some("revoke") => {
            let Some(who) = args.get(1) else { usage() };
            let mut d = Devices::load(&paths)?;
            let before = d.devices.len();
            let matches: Vec<String> = d
                .devices
                .iter()
                .filter(|x| x.id.starts_with(who.as_str()) || x.name == *who)
                .map(|x| x.id.clone())
                .collect();
            if matches.len() > 1 {
                bail!("{who} matches {} phones; give more of the id", matches.len());
            }
            d.devices.retain(|x| !matches.contains(&x.id));
            if d.devices.len() == before {
                bail!("no paired phone matches {who}");
            }
            d.save(&paths)?;
            println!("已撤销");
        }
        Some("library") => {
            let user = Shortcuts::load(&paths)?;
            let binds = hypr::request("j/binds").map(|r| library::hypr_binds(&r)).unwrap_or_default();
            let lib = library::to_json(&library::build(&user, &binds));
            println!("{}", serde_json::to_string_pretty(&lib)?);
        }
        _ => usage(),
    }
    Ok(())
}
