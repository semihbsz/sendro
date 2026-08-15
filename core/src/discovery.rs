//! mDNS discovery (PROTOCOL.md §2): advertise `_sendro._tcp.local.` with
//! TXT v/id/nm/pf and the actually-bound port; plus a browse API for
//! future desktop features (finding other Sendro instances on the LAN).

use std::collections::HashMap;
use std::time::Duration;

use anyhow::Context;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use uuid::Uuid;

use crate::types::{DiscoveredHost, PLATFORM, PROTOCOL_VERSION};
use crate::Core;

pub const SERVICE_TYPE: &str = "_sendro._tcp.local.";

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
