//! TLS identity and mDNS advertisement.

use anyhow::{Context, Result};
use mdns_sd::{IfKind, ServiceDaemon, ServiceInfo};
use std::net::{IpAddr, Ipv4Addr};
use std::sync::Arc;
use std::time::Duration;
use tokio_rustls::rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use tokio_rustls::rustls::{self, ServerConfig};
use tokio_rustls::TlsAcceptor;
use yao::config::{self, Paths};

/// The self-signed certificate made on first start and kept for good: phones pin it.
fn identity(paths: &Paths) -> Result<(Vec<u8>, Vec<u8>)> {
    if let (Ok(cert), Ok(key)) = (std::fs::read(paths.cert_file()), std::fs::read(paths.key_file())) {
        return Ok((cert, key));
    }
    let ck = rcgen::generate_simple_self_signed(vec![config::hostname(), "yao".into()])
        .context("generating the TLS certificate")?;
    let cert = ck.cert.der().to_vec();
    let key = ck.key_pair.serialize_der();
    config::write_private(&paths.key_file(), &key)?;
    config::write_private(&paths.cert_file(), &cert)?;
    Ok((cert, key))
}

pub fn acceptor(paths: &Paths) -> Result<TlsAcceptor> {
    let (cert, key) = identity(paths)?;
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let cfg = ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(cert)],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)),
        )?;
    Ok(TlsAcceptor::from(Arc::new(cfg)))
}

/// IPv4 address of the interface that holds the default route.
fn default_ipv4() -> Option<Ipv4Addr> {
    let routes = std::fs::read_to_string("/proc/net/route").ok()?;
    let iface = routes.lines().skip(1).find_map(|l| {
        let f: Vec<&str> = l.split_whitespace().collect();
        (f.len() > 2 && f[1] == "00000000").then(|| f[0].to_string())
    })?;
    if_addrs::get_if_addrs().ok()?.into_iter().find_map(|i| match i.ip() {
        IpAddr::V4(v4) if i.name == iface => Some(v4),
        _ => None,
    })
}

/// Advertises `_yao._tcp` on one address, re-announcing when that address changes.
pub async fn advertise(host: String, port: u16, fixed: Option<Ipv4Addr>) {
    let mut current: Option<(Ipv4Addr, ServiceDaemon)> = None;
    loop {
        let ip = fixed.or_else(default_ipv4);
        if ip != current.as_ref().map(|(ip, _)| *ip) {
            if let Some((_, d)) = current.take() {
                let _ = d.shutdown();
            }
            if let Some(ip) = ip {
                match register(&host, port, ip) {
                    Ok(d) => {
                        eprintln!("yaod: advertising {host} on {ip}:{port}");
                        current = Some((ip, d));
                    }
                    Err(e) => eprintln!("yaod: mDNS: {e:#}"),
                }
            }
        }
        tokio::time::sleep(Duration::from_secs(30)).await;
    }
}

fn register(host: &str, port: u16, ip: Ipv4Addr) -> Result<ServiceDaemon> {
    let d = ServiceDaemon::new()?;
    d.disable_interface(IfKind::All)?;
    d.enable_interface(IfKind::Addr(IpAddr::V4(ip)))?;
    let info = ServiceInfo::new(
        "_yao._tcp.local.",
        host,
        &format!("{host}.local."),
        IpAddr::V4(ip),
        port,
        &[("v", "1")][..],
    )?;
    d.register(info)?;
    Ok(d)
}
