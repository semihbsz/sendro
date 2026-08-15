//! Local network surface: IPv4 interface enumeration and classification.
//!
//! Used by QR pairing (PROTOCOL.md §13), Sendro Link (§14) and the desktop's
//! "no router needed" screen. Everything here is re-resolved on every call —
//! a Windows "Mobile hotspot" adapter appears *after* the app started, which
//! is exactly the case this feature exists for, so nothing may be cached at
//! startup.

use std::net::Ipv4Addr;

use serde::{Deserialize, Serialize};

use crate::Core;

/// A routable LAN address (Ethernet / Wi-Fi).
pub const KIND_LAN: &str = "lan";
/// A PC-hosted or phone-hosted hotspot address (no router involved).
pub const KIND_HOTSPOT: &str = "hotspot";
/// Reachable, but neither of the above (public, CGNAT, link-local, …).
pub const KIND_OTHER: &str = "other";

/// One usable IPv4 address of this host (CORE_API.md).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetIface {
    pub name: String,
    pub address: String,
    /// "lan" | "hotspot" | "other"
    pub kind: String,
    pub is_up: bool,
}

/// Classify an IPv4 address for the UI.
///
/// * `192.168.137.0/24` — the fixed subnet Windows' "Mobile hotspot" (ICS)
///   hands out, and `172.20.10.0/28` — the iPhone Personal Hotspot subnet →
///   `hotspot`. These are the "no router needed" paths.
/// * any other RFC 1918 private address → `lan`.
/// * anything else (public, CGNAT, 169.254 link-local) → `other`.
///
/// Loopback is never classified — callers drop it before getting here.
pub fn classify(ip: Ipv4Addr) -> &'static str {
    let o = ip.octets();
    // Windows "Mobile hotspot" / ICS, and iPhone Personal Hotspot.
    if (o[0] == 192 && o[1] == 168 && o[2] == 137) || (o[0] == 172 && o[1] == 20 && o[2] == 10) {
        return KIND_HOTSPOT;
    }
    if ip.is_private() {
        return KIND_LAN;
    }
    KIND_OTHER
}

/// Sort key: lan first, then hotspot, then other.
fn kind_rank(kind: &str) -> u8 {
    match kind {
        KIND_LAN => 0,
        KIND_HOTSPOT => 1,
        _ => 2,
    }
}

/// Every non-loopback IPv4 address of this host, ordered lan → hotspot →
/// other (address-ascending inside a kind, so the list is stable).
pub fn interfaces() -> Vec<NetIface> {
    let mut out: Vec<NetIface> = match if_addrs::get_if_addrs() {
        Ok(list) => list
            .into_iter()
            .filter_map(|iface| {
                // IPv4 only (§13: `h` is a routable LAN address, and the
                // guest page must be typeable/scannable).
                let ip = match iface.addr {
                    if_addrs::IfAddr::V4(ref v4) => v4.ip,
                    if_addrs::IfAddr::V6(_) => return None,
                };
                if ip.is_loopback() || ip.is_unspecified() {
                    return None;
                }
                Some(NetIface {
                    name: iface.name.clone(),
                    address: ip.to_string(),
                    kind: classify(ip).to_string(),
                    // get_if_addrs only reports interfaces that currently
                    // carry an address, i.e. ones that are up.
                    is_up: true,
                })
            })
            .collect(),
        Err(e) => {
            tracing::warn!("interface enumeration failed: {e}");
            Vec::new()
        }
    };
    out.sort_by(|a, b| {
        kind_rank(&a.kind)
            .cmp(&kind_rank(&b.kind))
            .then_with(|| a.address.cmp(&b.address))
    });
    out.dedup_by(|a, b| a.address == b.address);
    out
}

/// Just the addresses of [`interfaces`], best candidate first.
pub fn routable_ipv4s() -> Vec<String> {
    interfaces().into_iter().map(|i| i.address).collect()
}

/// Best-effort local IP list for `GET /api/v1/info` / [`crate::HostInfo`].
///
/// Enumeration first; if that yields nothing (no NIC, container with only
/// loopback) fall back to the default-route probe — a UDP "connect" to a
/// public address reveals the outbound interface address without sending a
/// single packet — and finally to loopback so the field is never empty.
pub(crate) fn info_local_ips() -> Vec<String> {
    let ips = routable_ipv4s();
    if !ips.is_empty() {
        return ips;
    }
    if let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") {
        if socket.connect("8.8.8.8:80").is_ok() {
            if let Ok(addr) = socket.local_addr() {
                if let std::net::IpAddr::V4(v4) = addr.ip() {
                    if !v4.is_loopback() && !v4.is_unspecified() {
                        return vec![v4.to_string()];
                    }
                }
            }
        }
    }
    vec!["127.0.0.1".to_string()]
}

impl Core {
    /// Usable IPv4 addresses of this host, freshly enumerated (CORE_API.md).
    ///
    /// Also refreshes the mDNS advertisement if the address set changed since
    /// the last look — a hotspot adapter that appeared after startup must be
    /// advertised too.
    pub fn network_interfaces(&self) -> Vec<NetIface> {
        let ifaces = interfaces();
        self.refresh_advertisement(&ifaces);
        ifaces
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn windows_and_iphone_hotspots_are_hotspots() {
        assert_eq!(classify("192.168.137.1".parse().unwrap()), KIND_HOTSPOT);
        assert_eq!(classify("192.168.137.204".parse().unwrap()), KIND_HOTSPOT);
        assert_eq!(classify("172.20.10.2".parse().unwrap()), KIND_HOTSPOT);
    }

    #[test]
    fn private_addresses_are_lan() {
        assert_eq!(classify("192.168.1.42".parse().unwrap()), KIND_LAN);
        assert_eq!(classify("192.168.136.1".parse().unwrap()), KIND_LAN);
        assert_eq!(classify("10.0.0.5".parse().unwrap()), KIND_LAN);
        assert_eq!(classify("172.16.9.9".parse().unwrap()), KIND_LAN);
        assert_eq!(classify("172.20.11.2".parse().unwrap()), KIND_LAN);
    }

    #[test]
    fn everything_else_is_other() {
        assert_eq!(classify("8.8.8.8".parse().unwrap()), KIND_OTHER);
        assert_eq!(classify("169.254.10.1".parse().unwrap()), KIND_OTHER);
        assert_eq!(classify("100.64.0.1".parse().unwrap()), KIND_OTHER);
        assert_eq!(classify("172.32.0.1".parse().unwrap()), KIND_OTHER);
    }

    #[test]
    fn enumeration_excludes_loopback_and_sorts_by_kind() {
        let list = interfaces();
        assert!(list.iter().all(|i| i.address != "127.0.0.1"));
        let ranks: Vec<u8> = list.iter().map(|i| kind_rank(&i.kind)).collect();
        assert!(ranks.windows(2).all(|w| w[0] <= w[1]), "{ranks:?}");
    }
}
