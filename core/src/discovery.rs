//! mDNS discovery (PROTOCOL.md §2), both directions:
//!
//! * **advertise** `_sendro._tcp.local.` with TXT v/id/nm/pf and the
//!   actually-bound port — the PC as host,
//! * **browse**, continuously, for everything else advertising the same
//!   service — the PC as client (§15: a phone or TV can itself be a host).
//!
//! The browser runs for the whole life of the process, keeps a live map of
//! peers, probes each one's `/api/v1/info`, and publishes changes as
//! [`CoreEvent::PeersChanged`] (debounced). `browse()` remains as a one-shot
//! for callers that just want a snapshot.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::Duration;

use anyhow::Context;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use tokio::sync::watch as tokio_watch;
use uuid::Uuid;

use crate::types::{now_ms, DiscoveredHost, DiscoveredPeer, InfoResponse, APP_NAME, PLATFORM,
                   PROTOCOL_VERSION};
use crate::{Core, CoreEvent};

pub const SERVICE_TYPE: &str = "_sendro._tcp.local.";

/// A burst of mDNS records (one per interface, plus the resolve) collapses
/// into a single [`CoreEvent::PeersChanged`].
pub const PEERS_DEBOUNCE: Duration = Duration::from_millis(500);

/// How often every known peer is re-probed with `GET /api/v1/info`, so
/// `reachable` decays when a device leaves without withdrawing its record.
pub const PEER_PROBE_INTERVAL: Duration = Duration::from_secs(30);

/// Per-probe timeout. A LAN peer answers in milliseconds; anything slower is
/// indistinguishable from gone, for UI purposes.
pub const PROBE_TIMEOUT: Duration = Duration::from_secs(3);

/// Register the host service. Returns the daemon (kept alive in Core; the
/// registration dies with it).
pub(crate) fn advertise(core: &Core) -> anyhow::Result<ServiceDaemon> {
    let daemon = ServiceDaemon::new().context("create mDNS daemon")?;
    advertise_on(core, &daemon)?;
    Ok(daemon)
}

/// (Re-)register the service on an existing daemon. Registering the same
/// instance again replaces the record, which is how a hotspot adapter that
/// appeared after startup gets picked up.
pub(crate) fn advertise_on(core: &Core, daemon: &ServiceDaemon) -> anyhow::Result<()> {
    let device_name = core.settings.read().device_name.clone();
    let instance = sanitize_instance_name(&device_name);
    let host_name = format!("sendro-{}.local.", short_id(core.device_id));

    let txt: [(&str, String); 4] = [
        ("v", PROTOCOL_VERSION.to_string()),
        ("id", core.device_id.to_string()),
        ("nm", device_name.clone()),
        ("pf", PLATFORM.to_string()),
    ];
    let info = ServiceInfo::new(
        SERVICE_TYPE,
        &instance,
        &host_name,
        // No fixed IP: enable_addr_auto picks up interface addresses
        // (and tracks changes, e.g. Wi-Fi roaming).
        "",
        core.port,
        &txt[..],
    )
    .context("build mDNS service info")?
    .enable_addr_auto();

    daemon
        .register(info)
        .context("register mDNS service")?;
    tracing::info!(
        "mDNS: advertising {instance}.{SERVICE_TYPE} on port {}",
        core.port
    );
    Ok(())
}

/// Browse the LAN for Sendro instances for `timeout`. `exclude` filters out
/// our own advertisement.
pub async fn browse(
    timeout: Duration,
    exclude: Option<Uuid>,
) -> anyhow::Result<Vec<DiscoveredHost>> {
    let daemon = ServiceDaemon::new().context("create mDNS daemon")?;
    let receiver = daemon.browse(SERVICE_TYPE).context("start browse")?;
    let exclude = exclude.map(|u| u.to_string());

    let mut found: HashMap<String, DiscoveredHost> = HashMap::new();
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            break;
        }
        let event = match tokio::time::timeout(remaining, receiver.recv_async()).await {
            Ok(Ok(event)) => event,
            _ => break,
        };
        if let ServiceEvent::ServiceResolved(info) = event {
            let get = |key: &str| {
                info.get_property_val_str(key)
                    .map(str::to_string)
                    .unwrap_or_default()
            };
            let device_id = get("id");
            if exclude.as_deref() == Some(device_id.as_str()) {
                continue;
            }
            let host = DiscoveredHost {
                device_id: device_id.clone(),
                device_name: get("nm"),
                platform: get("pf"),
                port: info.get_port(),
                addresses: info
                    .get_addresses()
                    .iter()
                    .map(|a| a.to_string())
                    .collect(),
                protocol_version: get("v"),
            };
            found.insert(device_id, host);
        }
    }
    let _ = daemon.stop_browse(SERVICE_TYPE);
    let _ = daemon.shutdown();
    Ok(found.into_values().collect())
}

// ---------------------------------------------------------------------------
// Continuous browsing — the PC as a client (§2 + §15)
// ---------------------------------------------------------------------------

/// One live mDNS record, plus what we have learned about it locally.
pub(crate) struct DiscoveredEntry {
    pub peer: DiscoveredPeer,
    /// `false` until the first `/api/v1/info` probe answered *or* failed, so
    /// the UI can tell "not probed yet" from "probed and unreachable".
    pub probed: bool,
}

/// Turn a resolved service into a peer, or `None` when it is unusable:
/// malformed `id`, our own advertisement, or no routable IPv4 address.
fn peer_from_service(info: &ServiceInfo, exclude: Uuid) -> Option<(String, DiscoveredPeer)> {
    let get = |key: &str| info.get_property_val_str(key).unwrap_or_default().to_string();

    // §2: `id` is a UUID v4 string. Anything else is not a Sendro peer we can
    // reason about (we key pairings by UUID), so it is skipped rather than
    // shown as an un-pairable row.
    let device_id: Uuid = get("id").parse().ok()?;
    if device_id == exclude {
        return None; // our own advertisement
    }

    // IPv4 only, like the rest of Sendro (§13). A peer may advertise several
    // (Ethernet + Wi-Fi + hotspot); pick the best-classified one, breaking
    // ties by address so the list does not shuffle between records.
    let mut addresses: Vec<String> = info
        .get_addresses()
        .iter()
        .filter_map(|addr| match addr {
            IpAddr::V4(v4) if !v4.is_loopback() && !v4.is_unspecified() => Some(*v4),
            _ => None,
        })
        .map(|v4| (crate::net::classify(v4), v4.to_string()))
        .map(|(kind, addr)| {
            let rank = match kind {
                crate::net::KIND_LAN => 0u8,
                crate::net::KIND_HOTSPOT => 1,
                _ => 2,
            };
            format!("{rank}{addr}")
        })
        .collect();
    addresses.sort();
    let address = addresses.first()?[1..].to_string();

    let device_name = {
        let nm = get("nm");
        if nm.trim().is_empty() {
            // Fall back to the instance name, which §2 says is the device name.
            info.get_fullname()
                .split(&format!(".{SERVICE_TYPE}"))
                .next()
                .unwrap_or("Sendro device")
                .to_string()
        } else {
            nm
        }
    };

    let peer = DiscoveredPeer {
        device_id,
        device_name,
        platform: get("pf"),
        address,
        port: info.get_port(),
        protocol_version: get("v").parse().unwrap_or(0),
        last_seen_ms: now_ms(),
        // Filled in by the caller / on read — they are local judgements.
        paired: false,
        reachable: false,
    };
    Some((info.get_fullname().to_string(), peer))
}

/// `GET /api/v1/info` against a peer (§5). `Some` only when it really is a
/// Sendro host (`app == "sendro"`).
pub(crate) async fn probe_info(
    client: &reqwest::Client,
    address: &str,
    port: u16,
) -> Option<InfoResponse> {
    let url = format!("http://{address}:{port}/api/v1/info");
    let response = client
        .get(&url)
        .timeout(PROBE_TIMEOUT)
        .send()
        .await
        .ok()?
        .error_for_status()
        .ok()?;
    let info: InfoResponse = response.json().await.ok()?;
    (info.app == APP_NAME).then_some(info)
}

/// The long-lived browser. Owns the browse subscription, keeps
/// [`Core::discovered`] in sync, probes peers, and emits `PeersChanged`.
pub(crate) async fn browse_loop(
    core: Arc<Core>,
    daemon: ServiceDaemon,
    mut shutdown: tokio_watch::Receiver<bool>,
) {
    let mut receiver = match daemon.browse(SERVICE_TYPE) {
        Ok(receiver) => receiver,
        Err(e) => {
            // A broken multicast stack must never be fatal: manual "Add by IP"
            // still works, exactly like the advertisement side.
            tracing::warn!("mDNS browse unavailable: {e}");
            return;
        }
    };
    tracing::info!("mDNS: browsing {SERVICE_TYPE}");

    let mut dirty = false;
    let mut probe_tick = tokio::time::interval(PEER_PROBE_INTERVAL);
    probe_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        tokio::select! {
            event = receiver.recv_async() => {
                match event {
                    Ok(ServiceEvent::ServiceResolved(info)) => {
                        if let Some((key, peer)) = peer_from_service(&info, core.device_id) {
                            dirty |= core.upsert_discovered(key, peer);
                        }
                    }
                    Ok(ServiceEvent::ServiceRemoved(_, fullname)) => {
                        dirty |= core.remove_discovered(&fullname);
                    }
                    Ok(_) => {}
                    Err(_) => break, // daemon shut down
                }
            }
            // Debounce: fires PEERS_DEBOUNCE after the *last* change, so one
            // burst of records is one event.
            _ = tokio::time::sleep(PEERS_DEBOUNCE), if dirty => {
                dirty = false;
                probe_peers(&core, true).await;
                core.emit_peers_changed();
            }
            _ = probe_tick.tick() => {
                if probe_peers(&core, false).await {
                    core.emit_peers_changed();
                }
            }
            _ = core.rebrowse.notified() => {
                // An interface appeared/disappeared (same trigger that
                // re-registers the advertisement): drop the subscription and
                // start a fresh query round so peers on the new interface are
                // resolved immediately instead of at the next periodic query.
                let _ = daemon.stop_browse(SERVICE_TYPE);
                match daemon.browse(SERVICE_TYPE) {
                    Ok(fresh) => {
                        receiver = fresh;
                        tracing::info!("mDNS: re-browsing after an interface change");
                    }
                    Err(e) => tracing::warn!("mDNS re-browse failed: {e}"),
                }
            }
            _ = wait_for_shutdown(&mut shutdown) => break,
        }
    }
    let _ = daemon.stop_browse(SERVICE_TYPE);
}

/// `watch::Receiver::wait_for` hands back a `Ref`, which is not `Send` and
/// would poison this whole (spawned) future the moment another `select!`
/// branch awaits. Awaiting through a helper keeps the guard out of the
/// generator's state.
async fn wait_for_shutdown(shutdown: &mut tokio_watch::Receiver<bool>) {
    let _ = shutdown.wait_for(|v| *v).await;
}

/// Probe known peers with `/api/v1/info`. `only_unprobed` limits the round to
/// peers we have never reached (the cheap path right after a discovery burst).
/// Returns true when anything observable changed.
async fn probe_peers(core: &Arc<Core>, only_unprobed: bool) -> bool {
    use futures::stream::StreamExt;

    let targets: Vec<(String, String, u16)> = core
        .discovered
        .read()
        .iter()
        .filter(|(_, entry)| !only_unprobed || !entry.probed)
        .map(|(key, entry)| (key.clone(), entry.peer.address.clone(), entry.peer.port))
        .collect();
    if targets.is_empty() {
        return false;
    }

    let client = core.http.clone();
    let results: Vec<(String, Option<InfoResponse>)> = futures::stream::iter(targets)
        .map(|(key, address, port)| {
            let client = client.clone();
            async move { (key, probe_info(&client, &address, port).await) }
        })
        .buffer_unordered(8)
        .collect()
        .await;

    let mut changed = false;
    let mut discovered = core.discovered.write();
    for (key, info) in results {
        let Some(entry) = discovered.get_mut(&key) else {
            continue; // vanished while we were probing
        };
        let reachable = info.is_some();
        if entry.peer.reachable != reachable || !entry.probed {
            changed = true;
        }
        entry.probed = true;
        entry.peer.reachable = reachable;
        if let Some(info) = info {
            // `/api/v1/info` is authoritative over the TXT record (§15.1).
            if entry.peer.device_name != info.device_name
                || entry.peer.platform != info.platform
                || entry.peer.protocol_version != info.protocol_version
            {
                entry.peer.device_name = info.device_name;
                entry.peer.platform = info.platform;
                entry.peer.protocol_version = info.protocol_version;
                changed = true;
            }
            entry.peer.last_seen_ms = now_ms();
        }
    }
    changed
}

impl Core {
    /// Peers currently visible on the LAN, name-sorted, with `paired`
    /// resolved against both directions of trust.
    pub fn discovered_peers(&self) -> Vec<DiscoveredPeer> {
        let mut peers: Vec<DiscoveredPeer> = self
            .discovered
            .read()
            .values()
            .map(|entry| {
                let mut peer = entry.peer.clone();
                peer.paired = self.is_known_peer(peer.device_id);
                peer
            })
            .collect();
        peers.sort_by(|a, b| {
            a.device_name
                .to_lowercase()
                .cmp(&b.device_name.to_lowercase())
                .then_with(|| a.device_id.cmp(&b.device_id))
        });
        peers
    }

    /// Do we already have a relationship with this device, in either
    /// direction? (It pairs to us → trusted list; we pair to it → peers.json.)
    pub(crate) fn is_known_peer(&self, device_id: Uuid) -> bool {
        self.peers.read().iter().any(|p| p.device_id == device_id)
            || self.trusted.read().iter().any(|d| d.device_id == device_id)
    }

    /// Insert or refresh a discovered record. Returns true when something the
    /// UI would notice changed (a pure `last_seen_ms` bump does not count —
    /// otherwise every mDNS refresh would wake the whole webview).
    pub(crate) fn upsert_discovered(&self, key: String, peer: DiscoveredPeer) -> bool {
        let mut discovered = self.discovered.write();
        match discovered.get_mut(&key) {
            Some(entry) => {
                let same = entry.peer.device_id == peer.device_id
                    && entry.peer.device_name == peer.device_name
                    && entry.peer.platform == peer.platform
                    && entry.peer.address == peer.address
                    && entry.peer.port == peer.port
                    && entry.peer.protocol_version == peer.protocol_version;
                let moved = entry.peer.address != peer.address || entry.peer.port != peer.port;
                entry.peer.last_seen_ms = peer.last_seen_ms;
                if same {
                    return false;
                }
                let reachable = entry.peer.reachable && !moved;
                entry.peer = DiscoveredPeer {
                    reachable,
                    ..peer
                };
                if moved {
                    entry.probed = false; // re-probe at the new address
                }
                true
            }
            None => {
                discovered.insert(key, DiscoveredEntry { peer, probed: false });
                true
            }
        }
    }

    pub(crate) fn remove_discovered(&self, key: &str) -> bool {
        self.discovered.write().remove(key).is_some()
    }

    pub(crate) fn emit_peers_changed(&self) {
        self.emit(CoreEvent::PeersChanged {
            peers: self.discovered_peers(),
        });
    }
}

/// mDNS instance names cannot contain dots; keep it readable otherwise.
fn sanitize_instance_name(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| if c == '.' { '_' } else { c })
        .collect();
    if cleaned.trim().is_empty() {
        "Sendro".to_string()
    } else {
        cleaned
    }
}

fn short_id(id: Uuid) -> String {
    id.simple().to_string()[..12].to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn service(id: &str, name: &str, platform: &str, addr: &str) -> ServiceInfo {
        let txt: [(&str, String); 4] = [
            ("v", "1".to_string()),
            ("id", id.to_string()),
            ("nm", name.to_string()),
            ("pf", platform.to_string()),
        ];
        ServiceInfo::new(
            SERVICE_TYPE,
            name,
            "sendro-test.local.",
            addr,
            48800,
            &txt[..],
        )
        .expect("service info")
    }

    #[test]
    fn our_own_advertisement_is_never_a_peer() {
        let me = Uuid::new_v4();
        let mine = service(&me.to_string(), "Semih-PC", "windows", "192.168.1.10");
        assert!(
            peer_from_service(&mine, me).is_none(),
            "the browser must not list this PC"
        );

        let theirs = Uuid::new_v4();
        let other = service(&theirs.to_string(), "Living Room TV", "androidtv", "192.168.1.44");
        let (key, peer) = peer_from_service(&other, me).expect("a real peer");
        assert!(key.contains(SERVICE_TYPE));
        assert_eq!(peer.device_id, theirs);
        assert_eq!(peer.device_name, "Living Room TV");
        assert_eq!(peer.platform, "androidtv");
        assert_eq!(peer.address, "192.168.1.44");
        assert_eq!(peer.port, 48800);
        assert_eq!(peer.protocol_version, 1);
        assert!(!peer.paired && !peer.reachable, "both are local judgements");
    }

    #[test]
    fn a_record_without_a_usable_id_or_address_is_skipped() {
        let me = Uuid::new_v4();
        assert!(peer_from_service(&service("not-a-uuid", "Weird", "linux", "192.168.1.9"), me)
            .is_none());
        assert!(
            peer_from_service(&service(&Uuid::new_v4().to_string(), "Loop", "ios", "127.0.0.1"), me)
                .is_none(),
            "loopback is not reachable from anywhere else on the LAN"
        );
    }

    #[test]
    fn a_lan_address_wins_over_a_hotspot_one() {
        let me = Uuid::new_v4();
        let id = Uuid::new_v4();
        let txt: [(&str, String); 4] = [
            ("v", "1".to_string()),
            ("id", id.to_string()),
            ("nm", "Semih's Pixel".to_string()),
            ("pf", "android".to_string()),
        ];
        let info = ServiceInfo::new(
            SERVICE_TYPE,
            "Semih's Pixel",
            "sendro-test.local.",
            &["192.168.137.4", "192.168.1.30"][..],
            48800,
            &txt[..],
        )
        .expect("service info");
        let (_, peer) = peer_from_service(&info, me).expect("peer");
        assert_eq!(peer.address, "192.168.1.30");
    }
}
