//! POST /request-subdomain — server-assigned FQDN with single-word preference.
//!
//! See GitHub issue #92 for the design. Flow:
//!
//! 1. Validate Firebase ID token.
//! 2. Rate-limit per-firebase-uid (pool enumeration defence).
//! 3. If the UID already holds a non-expired reservation, return it
//!    (idempotent retry).
//! 4. Pick a base domain from the release-valve set (currently just one).
//! 5. Pick a candidate name from the single-word pool (adjectives ∪ nouns).
//!    Retry once in the same pool on collision, then fall back to
//!    adjective-noun.
//! 6. Persist the reservation with a TTL, return `{subdomain, base_domain,
//!    fqdn, reservation_ttl_seconds}`.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use crate::api::{ApiError, AppState};
use crate::firestore::{FirestoreError, SubdomainReservation};

#[derive(Debug, Deserialize)]
pub struct RequestSubdomainRequest {
    pub firebase_token: String,
}

#[derive(Debug, Serialize)]
pub struct RequestSubdomainResponse {
    pub subdomain: String,
    pub base_domain: String,
    pub fqdn: String,
    pub reservation_ttl_seconds: u64,
}

/// Maximum retries in the single-word pool before falling back to
/// adjective-noun. Per the issue, we try the single-word pool twice.
const SINGLE_WORD_RETRIES: usize = 2;
/// Cap on adjective-noun retries when even the overflow pool collides.
const ADJECTIVE_NOUN_RETRIES: usize = 5;

pub async fn handle_request_subdomain(
    State(state): State<Arc<AppState>>,
    Json(req): Json<RequestSubdomainRequest>,
) -> Response {
    if req.firebase_token.is_empty() {
        return ApiError::bad_request("Missing required field: firebase_token").into_response();
    }

    let claims = match state
        .firebase_auth
        .verify_id_token(&req.firebase_token)
        .await
    {
        Ok(c) => c,
        Err(e) => {
            return ApiError::unauthorized(format!("Invalid Firebase ID token: {e}"))
                .into_response()
        }
    };
    let uid = claims.uid;

    // Per-UID rate limit before any Firestore reads.
    if let Err(retry_after) = state.request_subdomain_rate_limiter.try_acquire(&uid) {
        return ApiError::rate_limited(
            "Too many /request-subdomain calls for this account",
            retry_after,
        )
        .into_response();
    }

    let ttl_secs = state.config.limits.subdomain_reservation_ttl_secs.max(30);
    let ttl = Duration::from_secs(ttl_secs);
    let now = SystemTime::now();

    // Idempotent retry: if the UID already holds a fresh reservation, return it.
    match state.firestore.find_reservation_by_uid(&uid).await {
        Ok(Some((existing_subdomain, existing))) => {
            if existing.expires_at > now {
                let remaining = existing
                    .expires_at
                    .duration_since(now)
                    .unwrap_or(Duration::ZERO)
                    .as_secs();
                return (
                    StatusCode::OK,
                    Json(RequestSubdomainResponse {
                        subdomain: existing_subdomain,
                        base_domain: existing.base_domain.clone(),
                        fqdn: existing.fqdn,
                        reservation_ttl_seconds: remaining.max(1),
                    }),
                )
                    .into_response();
            }
            // Expired reservation: drop it and continue to a fresh pick.
            if let Err(e) = state
                .firestore
                .delete_reservation(&existing_subdomain)
                .await
            {
                tracing::warn!(
                    subdomain = %existing_subdomain,
                    error = %e,
                    "Failed to delete expired reservation during retry"
                );
            }
        }
        Ok(None) => {}
        Err(e) => {
            return ApiError::internal(format!("Reservation lookup failed: {e}")).into_response();
        }
    }

    // Release valve: pick the base domain. For now, always the primary; the
    // function exists so that swapping in a density-weighted pick is a
    // one-line change.
    let base_domain = match pick_base_domain(&state).await {
        Ok(d) => d,
        Err(e) => {
            return ApiError::internal(format!("Failed to pick base domain: {e}")).into_response();
        }
    };

    // Candidate selection: two rounds in the single-word pool, then
    // fall back to adjective-noun for a bounded number of attempts.
    let (subdomain, fqdn) = match select_free_subdomain(&state, &base_domain).await {
        Ok(pair) => pair,
        Err(e) => {
            return ApiError::internal(format!("Failed to select subdomain: {e}")).into_response();
        }
    };

    let reservation = SubdomainReservation {
        fqdn: fqdn.clone(),
        firebase_uid: uid,
        expires_at: now + ttl,
        base_domain: base_domain.clone(),
        created_at: now,
    };

    if let Err(e) = state
        .firestore
        .put_reservation(&subdomain, &reservation)
        .await
    {
        return ApiError::internal(format!("Failed to persist reservation: {e}")).into_response();
    }

    (
        StatusCode::OK,
        Json(RequestSubdomainResponse {
            subdomain,
            base_domain,
            fqdn,
            reservation_ttl_seconds: ttl_secs,
        }),
    )
        .into_response()
}

/// Pick a base domain. Current policy: primary only (the list is in place so
/// this is a trivial change later). Future policy: pick the domain with the
/// most ACME quota remaining, or the lowest device density.
async fn pick_base_domain(state: &Arc<AppState>) -> Result<String, FirestoreError> {
    let candidates = state.config.server.all_base_domains();
    // Release valve stub: we always return the first for now. If multiple
    // domains are configured, swap in density- or quota-weighted selection
    // here. See issue #92.
    Ok(candidates.into_iter().next().unwrap_or_default())
}

/// Walk the tiered pool until we find an unused subdomain.
async fn select_free_subdomain(
    state: &Arc<AppState>,
    base_domain: &str,
) -> Result<(String, String), FirestoreError> {
    // Tier 1: single-word pool (two attempts).
    for _ in 0..SINGLE_WORD_RETRIES {
        let candidate = state.subdomain_generator.generate_single_word();
        if is_free(state, &candidate).await? {
            let fqdn = format!("{candidate}.{base_domain}");
            return Ok((candidate, fqdn));
        }
    }

    // Tier 2: adjective-noun overflow.
    for _ in 0..ADJECTIVE_NOUN_RETRIES {
        let candidate = state.subdomain_generator.generate();
        if is_free(state, &candidate).await? {
            let fqdn = format!("{candidate}.{base_domain}");
            return Ok((candidate, fqdn));
        }
    }

    // Extremely unlikely with a 54k pool, but treat as an internal error.
    Err(FirestoreError::Http(
        "exhausted subdomain pool retries".to_string(),
    ))
}

/// A candidate is free if no active device record and no active reservation
/// claims it. Expired reservations count as free (the maintenance sweep will
/// clean them up eventually).
async fn is_free(state: &Arc<AppState>, candidate: &str) -> Result<bool, FirestoreError> {
    match state.firestore.get_device(candidate).await {
        Ok(_) => return Ok(false),
        Err(FirestoreError::NotFound(_)) => {}
        Err(e) => return Err(e),
    }
    match state.firestore.get_reservation(candidate).await {
        Ok(existing) => {
            // Expired reservations are free.
            Ok(existing.expires_at <= SystemTime::now())
        }
        Err(FirestoreError::NotFound(_)) => Ok(true),
        Err(e) => Err(e),
    }
}
