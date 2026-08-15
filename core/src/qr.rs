//! QR pairing — PROTOCOL.md §13.
//!
//! A QR session is an ordinary §4 pairing session (same 120 s expiry, same
//! attempt limits, same `PairingStarted` event so the typed code keeps
//! showing in the UI) whose parameters are additionally rendered as a
//! `sendro://pair?…` URL. The secret travels over the optical channel
//! (your screen → your camera), never over the network, so the security
//! property of §4 is unchanged: scanning proves physical presence.

use std::collections::HashMap;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use percent_encoding::{percent_decode_str, utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::pairing::PAIRING_TTL;
use crate::{Core, CoreEvent};

/// Percent-encode everything but the RFC 3986 unreserved set
/// (`ALPHA / DIGIT / "-" / "." / "_" / "~"`). §13 requires *every* value to
/// be encoded; device names contain spaces, `&` and Turkish characters.
/// base64url values (salt) survive untouched because `-` and `_` are
/// unreserved.
const QR_VALUE_SET: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

/// One QR-encodable URL, one per routable host address (§13).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QrUrl {
    pub address: String,
    pub url: String,
    /// "lan" | "hotspot" | "other" — see [`crate::net::classify`].
    pub kind: String,
}

/// Payload for the QR pairing screen (CORE_API.md).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QrPairing {
    pub pairing_id: Uuid,
    /// The same 6-digit code as the typed flow — shown as a fallback.
    pub code: String,
    /// base64url, no padding (as in `POST /api/v1/pair/start`).
    pub salt: String,
    /// One URL per routable local address, best candidate first.
    pub urls: Vec<QrUrl>,
    pub expires_in_seconds: u32,
}

/// The parameters carried by a `sendro://pair?…` URL (§13).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairUrlParams {
    pub version: u32,
    pub host: String,
    pub port: u16,
    pub device_id: Uuid,
    pub device_name: String,
    pub pairing_id: Uuid,
    pub salt: String,
    pub code: String,
}

/// Build the §13 URL. Every value is percent-encoded.
pub fn build_pair_url(p: &PairUrlParams) -> String {
    let enc = |v: &str| utf8_percent_encode(v, QR_VALUE_SET).to_string();
    format!(
        "sendro://pair?v={}&h={}&p={}&id={}&n={}&pid={}&s={}&c={}",
        p.version,
        enc(&p.host),
        p.port,
        enc(&p.device_id.to_string()),
        enc(&p.device_name),
        enc(&p.pairing_id.to_string()),
        enc(&p.salt),
        enc(&p.code),
    )
}

/// Inverse of [`build_pair_url`] — the parse a client performs after a scan.
/// Returns `None` for anything that is not a well-formed `sendro://pair`
/// URL. (Kept in the core so the round trip is testable and so host-side
/// tooling never has to re-derive the format.)
pub fn parse_pair_url(url: &str) -> Option<PairUrlParams> {
    let query = url.strip_prefix("sendro://pair?")?;
    let mut kv: HashMap<String, String> = HashMap::new();
    for pair in query.split('&') {
        let (k, v) = pair.split_once('=')?;
        let value = percent_decode_str(v).decode_utf8().ok()?.into_owned();
        // A duplicated key is a malformed URL, not a "last one wins".
        if kv.insert(k.to_string(), value).is_some() {
            return None;
        }
    }
    let get = |k: &str| kv.get(k).cloned();
    Some(PairUrlParams {
        version: get("v")?.parse().ok()?,
        host: get("h")?,
        port: get("p")?.parse().ok()?,
        device_id: get("id")?.parse().ok()?,
        device_name: get("n")?,
        pairing_id: get("pid")?.parse().ok()?,
        salt: get("s")?,
        code: get("c")?,
    })
}

impl Core {
    /// Open a pairing session for QR display (§13).
    ///
    /// This is the *same* session machinery as the typed flow — 120 s
    /// expiry, 5 attempts, 3 concurrent sessions — and it emits the same
    /// [`CoreEvent::PairingStarted`], so the typed code stays visible in the
    /// UI as a fallback. Addresses are re-enumerated on every call so a
    /// hotspot adapter brought up after startup is included.
    pub fn start_qr_pairing(&self) -> QrPairing {
        let ifaces = self.network_interfaces();
        let session = self.pairings.start_qr();
        let salt = URL_SAFE_NO_PAD.encode(session.salt);
        let device_name = self.settings.read().device_name.clone();

        let urls = ifaces
            .iter()
            .map(|iface| QrUrl {
                address: iface.address.clone(),
                url: build_pair_url(&PairUrlParams {
                    version: crate::types::PROTOCOL_VERSION,
                    host: iface.address.clone(),
                    port: self.port,
                    device_id: self.device_id,
                    device_name: device_name.clone(),
                    pairing_id: session.pairing_id,
                    salt: salt.clone(),
                    code: session.code.clone(),
                }),
                kind: iface.kind.clone(),
            })
            .collect();

        // Same event as the typed flow. The scanning device is unknown until
        // it confirms, so the UI gets a neutral label.
        self.emit(CoreEvent::PairingStarted {
            pairing_id: session.pairing_id,
            code: session.code.clone(),
            device_name: QR_DEVICE_LABEL.to_string(),
        });

        QrPairing {
            pairing_id: session.pairing_id,
            code: session.code,
            salt,
            urls,
            expires_in_seconds: PAIRING_TTL.as_secs() as u32,
        }
    }
}

/// Placeholder shown while a scanned session has not identified itself yet.
pub const QR_DEVICE_LABEL: &str = "QR";

/// Fallback name for a device that pairs by QR without sending one.
pub const QR_FALLBACK_DEVICE_NAME: &str = "iPhone";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_round_trips_with_spaces_ampersand_and_turkish() {
        let params = PairUrlParams {
            version: 1,
            host: "192.168.137.1".to_string(),
            port: 48800,
            device_id: Uuid::new_v4(),
            device_name: "Semih'in Bilgisayarı & Şeyler".to_string(),
            pairing_id: Uuid::new_v4(),
            salt: "abcDEF-_012".to_string(),
            code: "004213".to_string(),
        };
        let url = build_pair_url(&params);
        assert!(url.starts_with("sendro://pair?v=1&h=192.168.137.1&p=48800&"));
        // No raw space, ampersand-in-value or non-ASCII survives.
        assert!(!url.contains(' '));
        assert!(url.contains("%26")); // the "&" inside the name
        assert!(url.is_ascii());
        assert_eq!(parse_pair_url(&url).unwrap(), params);
    }

    #[test]
    fn parse_rejects_garbage() {
        assert!(parse_pair_url("https://example.com/pair?v=1").is_none());
        assert!(parse_pair_url("sendro://pair?v=1").is_none());
        assert!(parse_pair_url("sendro://pair?v=x&h=a&p=1&id=x&n=x&pid=x&s=x&c=x").is_none());
    }
}
