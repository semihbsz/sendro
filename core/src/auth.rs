//! Bearer-token authentication (PROTOCOL.md §3).
//!
//! The host stores only SHA-256(deviceToken); every authenticated request
//! carries `Authorization: Bearer <deviceToken>`.

use std::sync::Arc;

use axum::extract::{Request, State};
use axum::http::StatusCode;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use uuid::Uuid;

use crate::hashing::sha256_hex;
use crate::server::api_error;
use crate::Core;

/// Request extension inserted by the auth middleware.
#[derive(Debug, Clone)]
pub struct AuthedDevice {
    pub device_id: Uuid,
    pub device_name: String,
}

/// SHA-256 of the token string (as transmitted, i.e. the base64url form),
/// lowercase hex. This is what is stored in `trusted_devices.json`.
pub fn token_hash(token: &str) -> String {
    sha256_hex(token.as_bytes())
}

/// Axum middleware: resolve `Authorization: Bearer <token>` against the
/// trusted-device store; 401 `{"error":"unauthorized"}` otherwise.
pub async fn require_auth(
    State(core): State<Arc<Core>>,
    mut req: Request,
    next: Next,
) -> Response {
    let token = req
        .headers()
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .map(str::trim)
        .filter(|t| !t.is_empty());

    let Some(token) = token else {
        return unauthorized();
    };
    let hash = token_hash(token);
    let Some(device) = core.device_by_token_hash(&hash) else {
        return unauthorized();
    };
    core.touch_device(device.device_id);
    req.extensions_mut().insert(AuthedDevice {
        device_id: device.device_id,
        device_name: device.device_name,
    });
    next.run(req).await
}

fn unauthorized() -> Response {
    api_error(StatusCode::UNAUTHORIZED, "unauthorized", None).into_response()
}
