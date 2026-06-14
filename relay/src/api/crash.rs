//! POST /crash -- Crash-report ingestion for `foss`-flavor devices (#464).
//!
//! `foss` builds use ACRA's `HttpSender` to POST a JSON crash report here. The
//! relay sanitizes it, dedups by stack-trace fingerprint, spike-caps crash
//! loops, and files/append a GitHub issue labeled `crash`. See [`crate::crash`]
//! for the pipeline and its security/abuse-surface rationale.
//!
//! Protection (no device auth — ACRA can't present the device mTLS cert, and
//! crashes must be reportable pre-provisioning): per-IP rate limit (429) +
//! per-fingerprint spike-cap + global new-issue cap, all in [`crate::crash`].
//!
//! Responses are intentionally coarse so a caller can't probe the dedup/cap
//! state: `202 Accepted` for anything ingested (filed, appended, deduped,
//! capped, or no-sink), `400` for a body with no usable stack trace, `429`
//! when the per-IP limit trips, `413` when the body is too large.

use axum::body::Bytes;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::{Extension, Json};
use serde_json::Value;
use std::sync::Arc;
use tracing::{debug, warn};

use crate::api::{ApiError, AppState, ClientIp};

/// Hard cap on the crash payload we'll buffer/parse. ACRA reports with a
/// stack trace + a little metadata are a few KB; 512 KB is generous while
/// bounding memory on the constrained VPS.
const MAX_CRASH_BODY_BYTES: usize = 512 * 1024;

pub async fn handle_crash(
    State(state): State<Arc<AppState>>,
    client_ip: Option<Extension<ClientIp>>,
    body: Bytes,
) -> Response {
    // Per-IP admission BEFORE any parsing work. Fall back to a single shared
    // key when the source IP isn't plumbed (in-process test router), so the
    // limiter still functions deterministically there.
    let ip_key = client_ip
        .map(|Extension(ClientIp(ip))| ip.to_string())
        .unwrap_or_else(|| "unknown".to_string());
    if let Err(retry_after) = state.crash.allow_ip(&ip_key) {
        debug!(ip = %ip_key, "crash report rate limited (per-IP)");
        return ApiError::rate_limited("Too many crash reports", retry_after).into_response();
    }

    if body.len() > MAX_CRASH_BODY_BYTES {
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(serde_json::json!({ "status": "too_large" })),
        )
            .into_response();
    }

    let raw: Value = match serde_json::from_slice(&body) {
        Ok(v) => v,
        Err(e) => {
            debug!(error = %e, "crash report JSON parse failed");
            return ApiError::bad_request("Invalid crash report JSON").into_response();
        }
    };

    let outcome = state.crash.process(&raw).await;
    use crate::crash::CrashIngestOutcome::*;
    match outcome {
        InvalidReport => ApiError::bad_request("Crash report missing stack trace").into_response(),
        FilingFailed => {
            // Don't leak filing internals to the device; it already crashed.
            warn!("crash report accepted but GitHub filing failed");
            accepted("accepted")
        }
        Filed(_) => accepted("filed"),
        Appended(_) => accepted("appended"),
        DroppedDuplicate => accepted("deduplicated"),
        DroppedCapped => accepted("rate_capped"),
        NoSink => accepted("accepted"),
    }
}

fn accepted(status: &str) -> Response {
    (
        StatusCode::ACCEPTED,
        Json(serde_json::json!({ "status": status })),
    )
        .into_response()
}
