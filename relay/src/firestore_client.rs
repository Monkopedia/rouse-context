//! Real Firestore client backed by the Firestore REST API.
//!
//! Uses service account OAuth2 tokens via [`TokenProvider`] and speaks
//! the Firestore REST wire format (Value wrappers around every field).

use crate::firestore::{
    DeviceRecord, FirestoreClient, FirestoreError, PendingCert, SubdomainReservation,
};
use crate::google_auth::TokenProvider;
use async_trait::async_trait;
use serde::Deserialize;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tracing::{debug, warn};

/// OAuth2 scope for Firestore data access.
pub const FIRESTORE_SCOPE: &str = "https://www.googleapis.com/auth/datastore";

/// A [`FirestoreClient`] that calls the Firestore REST API.
pub struct RealFirestoreClient {
    http: reqwest::Client,
    token_provider: Arc<dyn TokenProvider>,
    /// e.g. `projects/my-project/databases/(default)/documents`
    base_url: String,
}

impl RealFirestoreClient {
    /// Create a new client.
    ///
    /// `project_id` is the Firebase/GCP project ID (e.g. `rouse-context`).
    pub fn new(project_id: &str, token_provider: Arc<dyn TokenProvider>) -> Self {
        let base_url = format!(
            "https://firestore.googleapis.com/v1/projects/{project_id}/databases/(default)/documents"
        );
        Self {
            http: reqwest::Client::new(),
            token_provider,
            base_url,
        }
    }

    /// Client against an arbitrary documents base URL (local test server).
    #[cfg(test)]
    fn with_base_url(base_url: String, token_provider: Arc<dyn TokenProvider>) -> Self {
        Self {
            http: reqwest::Client::new(),
            token_provider,
            base_url,
        }
    }

    /// Get an access token, mapping errors.
    async fn token(&self) -> Result<String, FirestoreError> {
        self.token_provider
            .access_token()
            .await
            .map_err(|e| FirestoreError::Http(format!("auth error: {e}")))
    }

    /// Full URL for a document path.
    fn url(&self, path: &str) -> String {
        format!("{}/{path}", self.base_url)
    }

    /// Map an HTTP response status to a `FirestoreError`, returning `Ok(resp)` on 2xx.
    fn map_status(resp: reqwest::Response, path: &str) -> Result<reqwest::Response, PendingError> {
        let status = resp.status().as_u16();
        match status {
            200..=299 => Ok(resp),
            404 => Err(PendingError::Final(FirestoreError::NotFound(
                path.to_string(),
            ))),
            409 => Err(PendingError::Final(FirestoreError::AlreadyExists(
                path.to_string(),
            ))),
            401 | 403 => Err(PendingError::AuthRetry(status)),
            _ => Err(PendingError::Final(FirestoreError::Http(format!(
                "Firestore returned {status} for {path}"
            )))),
        }
    }

    /// Send an authenticated request with one retry on auth errors.
    ///
    /// `build_request` receives the URL and a bearer token and must return a
    /// ready-to-send `reqwest::RequestBuilder`. The closure is called twice
    /// when the first attempt gets a 401/403 (with a fresh token on retry).
    async fn authenticated_request_with_retry<F>(
        &self,
        path: &str,
        build_request: F,
    ) -> Result<reqwest::Response, FirestoreError>
    where
        F: Fn(&str, &str) -> reqwest::RequestBuilder,
    {
        let url = self.url(path);
        let token = self.token().await?;
        let resp = build_request(&url, &token)
            .send()
            .await
            .map_err(|e| FirestoreError::Http(e.to_string()))?;
        match Self::map_status(resp, path) {
            Ok(r) => Ok(r),
            Err(PendingError::AuthRetry(status)) => {
                warn!(status, path, "Firestore auth error, retrying");
                let token = self.token().await?;
                let resp = build_request(&url, &token)
                    .send()
                    .await
                    .map_err(|e| FirestoreError::Http(e.to_string()))?;
                Self::map_status(resp, path).map_err(PendingError::into_final)
            }
            Err(PendingError::Final(e)) => Err(e),
        }
    }

    /// Authenticated GET with one retry on auth errors.
    async fn get_with_retry(&self, path: &str) -> Result<reqwest::Response, FirestoreError> {
        self.authenticated_request_with_retry(path, |url, token| {
            self.http.get(url).bearer_auth(token)
        })
        .await
    }

    /// Authenticated PATCH (upsert) with one retry on auth errors.
    async fn patch_with_retry(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> Result<reqwest::Response, FirestoreError> {
        self.authenticated_request_with_retry(path, |url, token| {
            self.http.patch(url).bearer_auth(token).json(body)
        })
        .await
    }

    /// Authenticated create-only POST (`createDocument`) with one retry on
    /// auth errors. Firestore answers 409 ALREADY_EXISTS, mapped to
    /// [`FirestoreError::AlreadyExists`], when the document is present.
    async fn create_with_retry(
        &self,
        collection: &str,
        document_id: &str,
        body: &serde_json::Value,
    ) -> Result<reqwest::Response, FirestoreError> {
        let path = format!("{collection}/{document_id}");
        let url = self.url(collection);
        self.authenticated_request_with_retry(&path, |_, token| {
            self.http
                .post(&url)
                .query(&[("documentId", document_id)])
                .bearer_auth(token)
                .json(body)
        })
        .await
    }

    /// Authenticated DELETE with one retry on auth errors.
    async fn delete_with_retry(&self, path: &str) -> Result<reqwest::Response, FirestoreError> {
        self.authenticated_request_with_retry(path, |url, token| {
            self.http.delete(url).bearer_auth(token)
        })
        .await
    }
}

/// Internal enum to distinguish retriable auth errors from final errors
/// in status-code mapping.
enum PendingError {
    AuthRetry(u16),
    Final(FirestoreError),
}

impl PendingError {
    fn into_final(self) -> FirestoreError {
        match self {
            PendingError::AuthRetry(status) => {
                FirestoreError::Http(format!("Firestore auth error ({status}) after retry"))
            }
            PendingError::Final(e) => e,
        }
    }
}

// ── Firestore REST wire format ──────────────────────────────────────

/// A Firestore document as returned by the REST API.
#[derive(Debug, Deserialize)]
struct Document {
    #[serde(default)]
    name: String,
    #[serde(default)]
    fields: serde_json::Map<String, serde_json::Value>,
}

/// Wrapper for the structured query response.
#[derive(Debug, Deserialize)]
struct RunQueryResponseElement {
    document: Option<Document>,
}

fn string_val(s: &str) -> serde_json::Value {
    serde_json::json!({ "stringValue": s })
}

fn opt_string_val(s: &Option<String>) -> serde_json::Value {
    match s {
        Some(v) => serde_json::json!({ "stringValue": v }),
        None => serde_json::json!({ "nullValue": null }),
    }
}

fn string_array_val(arr: &[String]) -> serde_json::Value {
    let values: Vec<serde_json::Value> = arr
        .iter()
        .map(|s| serde_json::json!({ "stringValue": s }))
        .collect();
    serde_json::json!({ "arrayValue": { "values": values } })
}

fn read_string_array(
    fields: &serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> Vec<String> {
    fields
        .get(key)
        .and_then(|v| v.get("arrayValue"))
        .and_then(|v| v.get("values"))
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|v| {
                    v.get("stringValue")
                        .and_then(|s| s.as_str())
                        .map(|s| s.to_string())
                })
                .collect()
        })
        .unwrap_or_default()
}

fn string_map_val(map: &std::collections::HashMap<String, String>) -> serde_json::Value {
    let mut entries = serde_json::Map::new();
    for (k, v) in map {
        entries.insert(k.clone(), serde_json::json!({ "stringValue": v }));
    }
    serde_json::json!({ "mapValue": { "fields": entries } })
}

fn read_string_map(
    fields: &serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> std::collections::HashMap<String, String> {
    fields
        .get(key)
        .and_then(|v| v.get("mapValue"))
        .and_then(|v| v.get("fields"))
        .and_then(|v| v.as_object())
        .map(|obj| {
            obj.iter()
                .filter_map(|(k, v)| {
                    v.get("stringValue")
                        .and_then(|s| s.as_str())
                        .map(|s| (k.clone(), s.to_string()))
                })
                .collect()
        })
        .unwrap_or_default()
}

fn read_opt_string(
    fields: &serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> Option<String> {
    fields
        .get(key)
        .and_then(|v| v.get("stringValue"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
}

fn timestamp_val(t: SystemTime) -> serde_json::Value {
    let secs = t.duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
    // RFC 3339
    let dt = time::OffsetDateTime::from_unix_timestamp(secs as i64)
        .unwrap_or(time::OffsetDateTime::UNIX_EPOCH);
    let formatted = dt
        .format(&time::format_description::well_known::Rfc3339)
        .unwrap_or_else(|_| "1970-01-01T00:00:00Z".to_string());
    serde_json::json!({ "timestampValue": formatted })
}

fn opt_timestamp_val(t: &Option<SystemTime>) -> serde_json::Value {
    match t {
        Some(t) => timestamp_val(*t),
        None => serde_json::json!({ "nullValue": null }),
    }
}

fn read_string(
    fields: &serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> Result<String, FirestoreError> {
    fields
        .get(key)
        .and_then(|v| v.get("stringValue"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| {
            FirestoreError::Serialization(format!("missing or invalid string field: {key}"))
        })
}

fn read_timestamp(
    fields: &serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> Result<SystemTime, FirestoreError> {
    let ts_str = fields
        .get(key)
        .and_then(|v| v.get("timestampValue"))
        .and_then(|v| v.as_str())
        .ok_or_else(|| {
            FirestoreError::Serialization(format!("missing or invalid timestamp field: {key}"))
        })?;
    parse_rfc3339(ts_str)
}

fn read_opt_timestamp(
    fields: &serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> Result<Option<SystemTime>, FirestoreError> {
    match fields.get(key) {
        None => Ok(None),
        Some(v) => {
            if v.get("nullValue").is_some() {
                Ok(None)
            } else if let Some(ts_str) = v.get("timestampValue").and_then(|v| v.as_str()) {
                Ok(Some(parse_rfc3339(ts_str)?))
            } else {
                Ok(None)
            }
        }
    }
}

fn parse_rfc3339(s: &str) -> Result<SystemTime, FirestoreError> {
    let dt = time::OffsetDateTime::parse(s, &time::format_description::well_known::Rfc3339)
        .map_err(|e| {
            FirestoreError::Serialization(format!("invalid RFC 3339 timestamp '{s}': {e}"))
        })?;
    let unix_secs = dt.unix_timestamp();
    if unix_secs < 0 {
        return Ok(UNIX_EPOCH);
    }
    Ok(UNIX_EPOCH + Duration::from_secs(unix_secs as u64))
}

// ── Conversion helpers ──────────────────────────────────────────────

/// Every stored field except `last_seen`, which only
/// [`FirestoreClient::set_device_last_seen`] writes.
fn device_record_to_fields(record: &DeviceRecord) -> serde_json::Map<String, serde_json::Value> {
    let mut fields = serde_json::Map::new();
    fields.insert("fcm_token".to_string(), string_val(&record.fcm_token));
    fields.insert("firebase_uid".to_string(), string_val(&record.firebase_uid));
    fields.insert(
        "key_thumbprint".to_string(),
        opt_string_val(&record.key_thumbprint),
    );
    fields.insert("public_key".to_string(), string_val(&record.public_key));
    fields.insert(
        "key_binding".to_string(),
        string_val(record.key_binding.as_wire()),
    );
    fields.insert(
        "cert_expires".to_string(),
        timestamp_val(record.cert_expires),
    );
    fields.insert(
        "registered_at".to_string(),
        timestamp_val(record.registered_at),
    );
    fields.insert(
        "last_rotation".to_string(),
        opt_timestamp_val(&record.last_rotation),
    );
    fields.insert(
        "renewal_nudge_sent".to_string(),
        opt_timestamp_val(&record.renewal_nudge_sent),
    );
    fields.insert(
        "secret_prefix".to_string(),
        opt_string_val(&record.secret_prefix),
    );
    fields.insert(
        "push_kind".to_string(),
        string_val(record.push_kind.as_wire()),
    );
    fields.insert(
        "push_endpoint".to_string(),
        string_val(&record.push_endpoint),
    );
    fields.insert(
        "valid_secrets".to_string(),
        string_array_val(&record.valid_secrets),
    );
    fields.insert(
        "integration_secrets".to_string(),
        string_map_val(&record.integration_secrets),
    );
    fields.insert(
        "retired_secrets".to_string(),
        string_map_val(&record.retired_secrets),
    );
    fields
}

fn device_record_from_fields(
    fields: &serde_json::Map<String, serde_json::Value>,
) -> Result<DeviceRecord, FirestoreError> {
    let fcm_token = read_string(fields, "fcm_token")?;
    let firebase_uid = read_string(fields, "firebase_uid")?;
    let public_key = read_string(fields, "public_key")?;
    // Documents written before key_binding existed: inferred from the key.
    let key_binding = crate::firestore::KeyBinding::from_stored(
        read_opt_string(fields, "key_binding").as_deref(),
        &public_key,
    );
    Ok(DeviceRecord {
        fcm_token,
        firebase_uid,
        key_thumbprint: read_opt_string(fields, "key_thumbprint"),
        public_key,
        key_binding,
        cert_expires: read_timestamp(fields, "cert_expires")?,
        registered_at: read_timestamp(fields, "registered_at")?,
        // Legacy documents predate last_seen: missing reads as None.
        last_seen: read_opt_timestamp(fields, "last_seen")?,
        last_rotation: read_opt_timestamp(fields, "last_rotation")?,
        renewal_nudge_sent: read_opt_timestamp(fields, "renewal_nudge_sent")?,
        secret_prefix: read_opt_string(fields, "secret_prefix"),
        // Legacy documents predate push_kind/push_endpoint: a missing or
        // unrecognised push_kind defaults to FCM, missing endpoint to empty.
        push_kind: read_opt_string(fields, "push_kind")
            .and_then(|s| crate::firestore::PushKind::from_wire(&s))
            .unwrap_or_default(),
        push_endpoint: read_opt_string(fields, "push_endpoint").unwrap_or_default(),
        valid_secrets: read_string_array(fields, "valid_secrets"),
        integration_secrets: read_string_map(fields, "integration_secrets"),
        // Legacy documents predate retired_secrets: a missing map reads empty.
        retired_secrets: read_string_map(fields, "retired_secrets"),
    })
}

fn pending_cert_to_fields(pending: &PendingCert) -> serde_json::Map<String, serde_json::Value> {
    let mut fields = serde_json::Map::new();
    fields.insert("fcm_token".to_string(), string_val(&pending.fcm_token));
    fields.insert("csr".to_string(), string_val(&pending.csr));
    fields.insert("blocked_at".to_string(), timestamp_val(pending.blocked_at));
    fields.insert(
        "retry_after".to_string(),
        timestamp_val(pending.retry_after),
    );
    fields
}

fn pending_cert_from_fields(
    fields: &serde_json::Map<String, serde_json::Value>,
) -> Result<PendingCert, FirestoreError> {
    Ok(PendingCert {
        fcm_token: read_string(fields, "fcm_token")?,
        csr: read_string(fields, "csr")?,
        blocked_at: read_timestamp(fields, "blocked_at")?,
        retry_after: read_timestamp(fields, "retry_after")?,
    })
}

fn reservation_to_fields(
    reservation: &SubdomainReservation,
) -> serde_json::Map<String, serde_json::Value> {
    let mut fields = serde_json::Map::new();
    fields.insert("fqdn".to_string(), string_val(&reservation.fqdn));
    fields.insert(
        "firebase_uid".to_string(),
        string_val(&reservation.firebase_uid),
    );
    fields.insert(
        "expires_at".to_string(),
        timestamp_val(reservation.expires_at),
    );
    fields.insert(
        "base_domain".to_string(),
        string_val(&reservation.base_domain),
    );
    fields.insert(
        "created_at".to_string(),
        timestamp_val(reservation.created_at),
    );
    fields
}

fn reservation_from_fields(
    fields: &serde_json::Map<String, serde_json::Value>,
) -> Result<SubdomainReservation, FirestoreError> {
    Ok(SubdomainReservation {
        fqdn: read_string(fields, "fqdn")?,
        firebase_uid: read_string(fields, "firebase_uid")?,
        expires_at: read_timestamp(fields, "expires_at")?,
        base_domain: read_string(fields, "base_domain")?,
        created_at: read_timestamp(fields, "created_at")?,
    })
}

/// Extract the document ID from a Firestore document `name` field.
/// The name looks like `projects/.../documents/devices/my-subdomain`.
fn doc_id_from_name(name: &str) -> String {
    name.rsplit('/').next().unwrap_or(name).to_string()
}

// ── Trait implementation ────────────────────────────────────────────

#[async_trait]
impl FirestoreClient for RealFirestoreClient {
    async fn get_device(&self, subdomain: &str) -> Result<DeviceRecord, FirestoreError> {
        let path = format!("devices/{subdomain}");
        let resp = self.get_with_retry(&path).await?;
        let doc: Document = resp
            .json()
            .await
            .map_err(|e| FirestoreError::Serialization(e.to_string()))?;
        device_record_from_fields(&doc.fields)
    }

    async fn find_device_by_uid(
        &self,
        firebase_uid: &str,
    ) -> Result<Option<(String, DeviceRecord)>, FirestoreError> {
        let token = self.token().await?;
        let url = format!("{}:runQuery", self.base_url);

        let query = serde_json::json!({
            "structuredQuery": {
                "from": [{ "collectionId": "devices" }],
                "where": {
                    "fieldFilter": {
                        "field": { "fieldPath": "firebase_uid" },
                        "op": "EQUAL",
                        "value": { "stringValue": firebase_uid }
                    }
                },
                "limit": 1
            }
        });

        let resp = self
            .http
            .post(&url)
            .bearer_auth(token)
            .json(&query)
            .send()
            .await
            .map_err(|e| FirestoreError::Http(e.to_string()))?;

        if !resp.status().is_success() {
            let status = resp.status().as_u16();
            let body = resp.text().await.unwrap_or_default();
            return Err(FirestoreError::Http(format!(
                "Firestore query returned {status}: {body}"
            )));
        }

        let elements: Vec<RunQueryResponseElement> = resp
            .json()
            .await
            .map_err(|e| FirestoreError::Serialization(e.to_string()))?;

        for elem in elements {
            if let Some(doc) = elem.document {
                let id = doc_id_from_name(&doc.name);
                let record = device_record_from_fields(&doc.fields)?;
                return Ok(Some((id, record)));
            }
        }

        Ok(None)
    }

    async fn find_device_by_thumbprint(
        &self,
        key_thumbprint: &str,
    ) -> Result<Option<(String, DeviceRecord)>, FirestoreError> {
        let token = self.token().await?;
        let url = format!("{}:runQuery", self.base_url);

        let query = serde_json::json!({
            "structuredQuery": {
                "from": [{ "collectionId": "devices" }],
                "where": {
                    "fieldFilter": {
                        "field": { "fieldPath": "key_thumbprint" },
                        "op": "EQUAL",
                        "value": { "stringValue": key_thumbprint }
                    }
                },
                "limit": 1
            }
        });

        let resp = self
            .http
            .post(&url)
            .bearer_auth(token)
            .json(&query)
            .send()
            .await
            .map_err(|e| FirestoreError::Http(e.to_string()))?;

        if !resp.status().is_success() {
            let status = resp.status().as_u16();
            let body = resp.text().await.unwrap_or_default();
            return Err(FirestoreError::Http(format!(
                "Firestore query returned {status}: {body}"
            )));
        }

        let elements: Vec<RunQueryResponseElement> = resp
            .json()
            .await
            .map_err(|e| FirestoreError::Serialization(e.to_string()))?;

        for elem in elements {
            if let Some(doc) = elem.document {
                let id = doc_id_from_name(&doc.name);
                let record = device_record_from_fields(&doc.fields)?;
                return Ok(Some((id, record)));
            }
        }

        Ok(None)
    }

    async fn create_device(
        &self,
        subdomain: &str,
        record: &DeviceRecord,
    ) -> Result<(), FirestoreError> {
        let body = serde_json::json!({ "fields": device_record_to_fields(record) });
        let resp = self.create_with_retry("devices", subdomain, &body).await?;
        debug!(
            subdomain,
            status = resp.status().as_u16(),
            "create_device OK"
        );
        Ok(())
    }

    async fn put_device(
        &self,
        subdomain: &str,
        record: &DeviceRecord,
    ) -> Result<(), FirestoreError> {
        // The update mask names exactly the written fields, so the PATCH
        // leaves `last_seen` as stored and a record read before a concurrent
        // `set_device_last_seen` cannot revert it.
        let path = format!("devices/{subdomain}");
        let fields = device_record_to_fields(record);
        let mask: Vec<(&str, &str)> = fields
            .keys()
            .map(|k| ("updateMask.fieldPaths", k.as_str()))
            .collect();
        let body = serde_json::json!({ "fields": fields });
        let resp = self
            .authenticated_request_with_retry(&path, |url, token| {
                self.http
                    .patch(url)
                    .query(&mask)
                    .bearer_auth(token)
                    .json(&body)
            })
            .await?;
        debug!(subdomain, status = resp.status().as_u16(), "put_device OK");
        Ok(())
    }

    async fn set_device_last_seen(
        &self,
        subdomain: &str,
        last_seen: SystemTime,
    ) -> Result<(), FirestoreError> {
        // The update mask limits the write to `last_seen`, so a concurrent
        // write to any other field is never overwritten. The `exists`
        // precondition stops the PATCH from creating a stub document for a
        // device deleted in the meantime.
        let path = format!("devices/{subdomain}");
        let body = serde_json::json!({ "fields": { "last_seen": timestamp_val(last_seen) } });
        self.authenticated_request_with_retry(&path, |url, token| {
            self.http
                .patch(url)
                .query(&[
                    ("updateMask.fieldPaths", "last_seen"),
                    ("currentDocument.exists", "true"),
                ])
                .bearer_auth(token)
                .json(&body)
        })
        .await?;
        Ok(())
    }

    async fn delete_device(&self, subdomain: &str) -> Result<(), FirestoreError> {
        let path = format!("devices/{subdomain}");
        match self.delete_with_retry(&path).await {
            Ok(_) => Ok(()),
            Err(FirestoreError::NotFound(_)) => Ok(()),
            Err(e) => Err(e),
        }
    }

    async fn put_pending_cert(
        &self,
        subdomain: &str,
        pending: &PendingCert,
    ) -> Result<(), FirestoreError> {
        let path = format!("pending_certs/{subdomain}");
        let body = serde_json::json!({ "fields": pending_cert_to_fields(pending) });
        self.patch_with_retry(&path, &body).await?;
        Ok(())
    }

    async fn get_pending_cert(&self, subdomain: &str) -> Result<PendingCert, FirestoreError> {
        let path = format!("pending_certs/{subdomain}");
        let resp = self.get_with_retry(&path).await?;
        let doc: Document = resp
            .json()
            .await
            .map_err(|e| FirestoreError::Serialization(e.to_string()))?;
        pending_cert_from_fields(&doc.fields)
    }

    async fn delete_pending_cert(&self, subdomain: &str) -> Result<(), FirestoreError> {
        let path = format!("pending_certs/{subdomain}");
        match self.delete_with_retry(&path).await {
            Ok(_) => Ok(()),
            Err(FirestoreError::NotFound(_)) => Ok(()),
            Err(e) => Err(e),
        }
    }

    async fn list_devices(&self) -> Result<Vec<(String, DeviceRecord)>, FirestoreError> {
        let mut results = Vec::new();
        let mut page_token: Option<String> = None;

        loop {
            let token = self.token().await?;
            let mut url = format!("{}/devices", self.base_url);
            if let Some(ref pt) = page_token {
                url = format!("{url}?pageToken={pt}");
            }

            let resp = self
                .http
                .get(&url)
                .bearer_auth(token)
                .send()
                .await
                .map_err(|e| FirestoreError::Http(e.to_string()))?;

            if !resp.status().is_success() {
                let status = resp.status().as_u16();
                let body = resp.text().await.unwrap_or_default();
                return Err(FirestoreError::Http(format!(
                    "Firestore list returned {status}: {body}"
                )));
            }

            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct ListResponse {
                #[serde(default)]
                documents: Vec<Document>,
                next_page_token: Option<String>,
            }

            let list: ListResponse = resp
                .json()
                .await
                .map_err(|e| FirestoreError::Serialization(e.to_string()))?;

            for doc in &list.documents {
                let id = doc_id_from_name(&doc.name);
                match device_record_from_fields(&doc.fields) {
                    Ok(record) => results.push((id, record)),
                    Err(e) => {
                        warn!(doc_name = %doc.name, "Skipping malformed device document: {e}");
                    }
                }
            }

            match list.next_page_token {
                Some(pt) if !pt.is_empty() => page_token = Some(pt),
                _ => break,
            }
        }

        Ok(results)
    }

    async fn list_pending_certs(&self) -> Result<Vec<(String, PendingCert)>, FirestoreError> {
        let mut results = Vec::new();
        let mut page_token: Option<String> = None;

        loop {
            let token = self.token().await?;
            let mut url = format!("{}/pending_certs", self.base_url);
            if let Some(ref pt) = page_token {
                url = format!("{url}?pageToken={pt}");
            }

            let resp = self
                .http
                .get(&url)
                .bearer_auth(token)
                .send()
                .await
                .map_err(|e| FirestoreError::Http(e.to_string()))?;

            if !resp.status().is_success() {
                let status = resp.status().as_u16();
                let body = resp.text().await.unwrap_or_default();
                return Err(FirestoreError::Http(format!(
                    "Firestore list returned {status}: {body}"
                )));
            }

            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct ListResponse {
                #[serde(default)]
                documents: Vec<Document>,
                next_page_token: Option<String>,
            }

            let list: ListResponse = resp
                .json()
                .await
                .map_err(|e| FirestoreError::Serialization(e.to_string()))?;

            for doc in &list.documents {
                let id = doc_id_from_name(&doc.name);
                match pending_cert_from_fields(&doc.fields) {
                    Ok(cert) => results.push((id, cert)),
                    Err(e) => {
                        warn!(doc_name = %doc.name, "Skipping malformed pending_cert document: {e}");
                    }
                }
            }

            match list.next_page_token {
                Some(pt) if !pt.is_empty() => page_token = Some(pt),
                _ => break,
            }
        }

        Ok(results)
    }

    async fn put_reservation(
        &self,
        subdomain: &str,
        reservation: &SubdomainReservation,
    ) -> Result<(), FirestoreError> {
        let path = format!("subdomain_reservations/{subdomain}");
        let body = serde_json::json!({ "fields": reservation_to_fields(reservation) });
        self.patch_with_retry(&path, &body).await?;
        Ok(())
    }

    async fn get_reservation(
        &self,
        subdomain: &str,
    ) -> Result<SubdomainReservation, FirestoreError> {
        let path = format!("subdomain_reservations/{subdomain}");
        let resp = self.get_with_retry(&path).await?;
        let doc: Document = resp
            .json()
            .await
            .map_err(|e| FirestoreError::Serialization(e.to_string()))?;
        reservation_from_fields(&doc.fields)
    }

    async fn find_reservation_by_uid(
        &self,
        firebase_uid: &str,
    ) -> Result<Option<(String, SubdomainReservation)>, FirestoreError> {
        let token = self.token().await?;
        let url = format!("{}:runQuery", self.base_url);

        let query = serde_json::json!({
            "structuredQuery": {
                "from": [{ "collectionId": "subdomain_reservations" }],
                "where": {
                    "fieldFilter": {
                        "field": { "fieldPath": "firebase_uid" },
                        "op": "EQUAL",
                        "value": { "stringValue": firebase_uid }
                    }
                },
                "limit": 1
            }
        });

        let resp = self
            .http
            .post(&url)
            .bearer_auth(token)
            .json(&query)
            .send()
            .await
            .map_err(|e| FirestoreError::Http(e.to_string()))?;

        if !resp.status().is_success() {
            let status = resp.status().as_u16();
            let body = resp.text().await.unwrap_or_default();
            return Err(FirestoreError::Http(format!(
                "Firestore query returned {status}: {body}"
            )));
        }

        let elements: Vec<RunQueryResponseElement> = resp
            .json()
            .await
            .map_err(|e| FirestoreError::Serialization(e.to_string()))?;

        for elem in elements {
            if let Some(doc) = elem.document {
                let id = doc_id_from_name(&doc.name);
                let reservation = reservation_from_fields(&doc.fields)?;
                return Ok(Some((id, reservation)));
            }
        }

        Ok(None)
    }

    async fn delete_reservation(&self, subdomain: &str) -> Result<(), FirestoreError> {
        let path = format!("subdomain_reservations/{subdomain}");
        match self.delete_with_retry(&path).await {
            Ok(_) => Ok(()),
            Err(FirestoreError::NotFound(_)) => Ok(()),
            Err(e) => Err(e),
        }
    }

    async fn list_reservations(
        &self,
    ) -> Result<Vec<(String, SubdomainReservation)>, FirestoreError> {
        let mut results = Vec::new();
        let mut page_token: Option<String> = None;

        loop {
            let token = self.token().await?;
            let mut url = format!("{}/subdomain_reservations", self.base_url);
            if let Some(ref pt) = page_token {
                url = format!("{url}?pageToken={pt}");
            }

            let resp = self
                .http
                .get(&url)
                .bearer_auth(token)
                .send()
                .await
                .map_err(|e| FirestoreError::Http(e.to_string()))?;

            if !resp.status().is_success() {
                let status = resp.status().as_u16();
                let body = resp.text().await.unwrap_or_default();
                return Err(FirestoreError::Http(format!(
                    "Firestore list returned {status}: {body}"
                )));
            }

            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct ListResponse {
                #[serde(default)]
                documents: Vec<Document>,
                next_page_token: Option<String>,
            }

            let list: ListResponse = resp
                .json()
                .await
                .map_err(|e| FirestoreError::Serialization(e.to_string()))?;

            for doc in &list.documents {
                let id = doc_id_from_name(&doc.name);
                match reservation_from_fields(&doc.fields) {
                    Ok(r) => results.push((id, r)),
                    Err(e) => {
                        warn!(doc_name = %doc.name, "Skipping malformed reservation document: {e}");
                    }
                }
            }

            match list.next_page_token {
                Some(pt) if !pt.is_empty() => page_token = Some(pt),
                _ => break,
            }
        }

        Ok(results)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, UNIX_EPOCH};

    fn sample_device_record() -> DeviceRecord {
        DeviceRecord {
            fcm_token: String::new(),
            firebase_uid: "uid".to_string(),
            key_thumbprint: None,
            public_key: String::new(),
            key_binding: crate::firestore::KeyBinding::AwaitingFirstKey,
            cert_expires: UNIX_EPOCH,
            registered_at: UNIX_EPOCH,
            last_seen: None,
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            push_kind: Default::default(),
            push_endpoint: String::new(),
            valid_secrets: Vec::new(),
            integration_secrets: Default::default(),
            retired_secrets: Default::default(),
        }
    }

    struct StaticToken;

    #[async_trait]
    impl TokenProvider for StaticToken {
        async fn access_token(&self) -> Result<String, crate::google_auth::AuthError> {
            Ok("test-token".to_string())
        }
    }

    /// `create_device` must use Firestore's create-only `createDocument`
    /// (POST to the collection with `documentId`), never the upserting PATCH,
    /// and must surface a 409 as `AlreadyExists`. The stand-in server keeps
    /// Firestore's semantics: the first create of a document id wins, later
    /// ones get 409 and change nothing.
    #[tokio::test]
    async fn create_device_is_create_only_on_the_wire() {
        use axum::extract::{Query, State as AxState};
        use axum::http::StatusCode;
        use std::collections::HashMap as Map;
        use std::sync::Mutex;

        type Store = Arc<Mutex<Map<String, serde_json::Value>>>;
        async fn create(
            AxState(store): AxState<Store>,
            Query(q): Query<Map<String, String>>,
            axum::Json(body): axum::Json<serde_json::Value>,
        ) -> StatusCode {
            let Some(id) = q.get("documentId") else {
                return StatusCode::BAD_REQUEST;
            };
            let mut store = store.lock().unwrap();
            if store.contains_key(id) {
                return StatusCode::CONFLICT;
            }
            store.insert(id.clone(), body);
            StatusCode::OK
        }
        let store: Store = Arc::new(Mutex::new(Map::new()));
        let app = axum::Router::new()
            .route("/devices", axum::routing::post(create))
            .with_state(store.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server = tokio::spawn(async move { axum::serve(listener, app).await });

        let client =
            RealFirestoreClient::with_base_url(format!("http://{addr}"), Arc::new(StaticToken));
        let mut first = sample_device_record();
        first.fcm_token = "first".to_string();
        let mut second = sample_device_record();
        second.fcm_token = "second".to_string();

        let (a, b) = tokio::join!(
            client.create_device("same-name", &first),
            client.create_device("same-name", &second)
        );
        let outcomes = [a, b];
        let wins = outcomes.iter().filter(|r| r.is_ok()).count();
        let conflicts = outcomes
            .iter()
            .filter(|r| matches!(r, Err(FirestoreError::AlreadyExists(_))))
            .count();
        assert_eq!((wins, conflicts), (1, 1), "{outcomes:?}");
        assert_eq!(store.lock().unwrap().len(), 1);

        server.abort();
    }

    fn key_binding_after_roundtrip(
        public_key: &str,
        stored: Option<&str>,
    ) -> crate::firestore::KeyBinding {
        let mut record = sample_device_record();
        record.public_key = public_key.to_string();
        let mut fields = device_record_to_fields(&record);
        match stored {
            Some(v) => {
                fields.insert("key_binding".to_string(), string_val(v));
            }
            None => {
                fields.remove("key_binding");
            }
        }
        device_record_from_fields(&fields).unwrap().key_binding
    }

    #[test]
    fn legacy_record_with_key_and_no_binding_field_reads_as_bound() {
        use crate::firestore::KeyBinding;
        assert_eq!(
            key_binding_after_roundtrip("cHVia2V5", None),
            KeyBinding::Bound
        );
    }

    #[test]
    fn legacy_record_without_key_or_binding_field_reads_as_awaiting() {
        use crate::firestore::KeyBinding;
        assert_eq!(
            key_binding_after_roundtrip("", None),
            KeyBinding::AwaitingFirstKey
        );
    }

    #[test]
    fn stored_binding_field_wins_over_empty_key() {
        use crate::firestore::KeyBinding;
        assert_eq!(
            key_binding_after_roundtrip("", Some("bound")),
            KeyBinding::Bound
        );
        assert_eq!(
            key_binding_after_roundtrip("", Some("awaiting_first_key")),
            KeyBinding::AwaitingFirstKey
        );
    }

    #[test]
    fn unrecognised_binding_value_reads_as_bound() {
        use crate::firestore::KeyBinding;
        assert_eq!(
            key_binding_after_roundtrip("", Some("garbage")),
            KeyBinding::Bound
        );
    }

    #[test]
    fn key_binding_is_written_explicitly() {
        use crate::firestore::KeyBinding;
        let mut record = sample_device_record();
        record.key_binding = KeyBinding::Bound;
        let fields = device_record_to_fields(&record);
        assert_eq!(fields["key_binding"]["stringValue"], "bound");
        record.key_binding = KeyBinding::AwaitingFirstKey;
        let fields = device_record_to_fields(&record);
        assert_eq!(fields["key_binding"]["stringValue"], "awaiting_first_key");
    }

    #[test]
    fn stored_last_seen_is_read() {
        let at = UNIX_EPOCH + Duration::from_secs(1_800_000_000);
        let mut fields = device_record_to_fields(&sample_device_record());
        fields.insert("last_seen".to_string(), timestamp_val(at));
        let back = device_record_from_fields(&fields).unwrap();
        assert_eq!(back.last_seen, Some(at));
    }

    #[test]
    fn legacy_record_without_last_seen_reads_none_and_falls_back_to_registered_at() {
        let mut record = sample_device_record();
        record.registered_at = UNIX_EPOCH + Duration::from_secs(1_700_000_000);
        let mut fields = device_record_to_fields(&record);
        fields.remove("last_seen");
        let back = device_record_from_fields(&fields).unwrap();
        assert_eq!(back.last_seen, None);
        assert_eq!(back.last_active(), record.registered_at);
    }

    /// `set_device_last_seen` must write only `last_seen` (an update mask),
    /// never the whole document, and must not create a missing document.
    #[tokio::test]
    async fn set_device_last_seen_writes_only_that_field_on_the_wire() {
        use axum::extract::{RawQuery, State as AxState};
        use axum::http::StatusCode;
        use std::sync::Mutex;

        type Seen = Arc<Mutex<Vec<(String, serde_json::Value)>>>;
        async fn patch(
            AxState(seen): AxState<Seen>,
            RawQuery(q): RawQuery,
            axum::Json(body): axum::Json<serde_json::Value>,
        ) -> StatusCode {
            seen.lock().unwrap().push((q.unwrap_or_default(), body));
            StatusCode::OK
        }
        let seen: Seen = Arc::new(Mutex::new(Vec::new()));
        let app = axum::Router::new()
            .route("/devices/{id}", axum::routing::patch(patch))
            .with_state(seen.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server = tokio::spawn(async move { axum::serve(listener, app).await });

        let client =
            RealFirestoreClient::with_base_url(format!("http://{addr}"), Arc::new(StaticToken));
        let at = UNIX_EPOCH + Duration::from_secs(1_800_000_000);
        client.set_device_last_seen("dev", at).await.unwrap();

        let seen = seen.lock().unwrap();
        assert_eq!(seen.len(), 1);
        let (query, body) = &seen[0];
        let mut params: Vec<&str> = query.split('&').collect();
        params.sort_unstable();
        assert_eq!(
            params,
            [
                "currentDocument.exists=true",
                "updateMask.fieldPaths=last_seen"
            ]
        );
        let fields = body["fields"].as_object().unwrap();
        assert_eq!(fields.keys().collect::<Vec<_>>(), ["last_seen"]);
        assert_eq!(fields["last_seen"], timestamp_val(at));

        server.abort();
    }

    /// `put_device` writes whole records read earlier (e.g. the `/ws` push
    /// token update on every connect), so it must leave `last_seen` alone or
    /// it could revert a concurrent `set_device_last_seen`. Its update mask
    /// names every other field and its body omits `last_seen`.
    #[tokio::test]
    async fn put_device_never_writes_last_seen() {
        use axum::extract::{RawQuery, State as AxState};
        use axum::http::StatusCode;
        use std::sync::Mutex;

        type Seen = Arc<Mutex<Vec<(String, serde_json::Value)>>>;
        async fn patch(
            AxState(seen): AxState<Seen>,
            RawQuery(q): RawQuery,
            axum::Json(body): axum::Json<serde_json::Value>,
        ) -> StatusCode {
            seen.lock().unwrap().push((q.unwrap_or_default(), body));
            StatusCode::OK
        }
        let seen: Seen = Arc::new(Mutex::new(Vec::new()));
        let app = axum::Router::new()
            .route("/devices/{id}", axum::routing::patch(patch))
            .with_state(seen.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server = tokio::spawn(async move { axum::serve(listener, app).await });

        let client =
            RealFirestoreClient::with_base_url(format!("http://{addr}"), Arc::new(StaticToken));
        let mut record = sample_device_record();
        record.last_seen = Some(UNIX_EPOCH + Duration::from_secs(1_800_000_000));
        client.put_device("dev", &record).await.unwrap();

        let seen = seen.lock().unwrap();
        assert_eq!(seen.len(), 1);
        let (query, body) = &seen[0];
        let mut masked: Vec<&str> = query
            .split('&')
            .filter_map(|p| p.strip_prefix("updateMask.fieldPaths="))
            .collect();
        masked.sort_unstable();
        let mut written: Vec<&str> = body["fields"]
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        written.sort_unstable();
        assert!(!written.contains(&"last_seen"), "{written:?}");
        assert_eq!(
            masked, written,
            "mask must cover exactly the written fields"
        );

        server.abort();
    }

    #[test]
    fn device_record_roundtrip() {
        let record = DeviceRecord {
            fcm_token: "tok123".to_string(),
            firebase_uid: "uid456".to_string(),
            key_thumbprint: None,
            public_key: "cHVia2V5".to_string(),
            key_binding: crate::firestore::KeyBinding::Bound,
            cert_expires: UNIX_EPOCH + Duration::from_secs(1_750_000_000),
            registered_at: UNIX_EPOCH + Duration::from_secs(1_712_000_000),
            last_seen: None,
            last_rotation: Some(UNIX_EPOCH + Duration::from_secs(1_713_000_000)),
            renewal_nudge_sent: None,
            secret_prefix: Some("brave-falcon".to_string()),
            push_kind: Default::default(),
            push_endpoint: String::new(),
            valid_secrets: vec!["brave-health".to_string(), "swift-outreach".to_string()],
            integration_secrets: std::collections::HashMap::from([
                ("health".to_string(), "brave-health".to_string()),
                ("outreach".to_string(), "swift-outreach".to_string()),
            ]),
            retired_secrets: std::collections::HashMap::from([(
                "calendar".to_string(),
                "stale-calendar".to_string(),
            )]),
        };
        let fields = device_record_to_fields(&record);
        let back = device_record_from_fields(&fields).unwrap();
        assert_eq!(back.fcm_token, record.fcm_token);
        assert_eq!(back.firebase_uid, record.firebase_uid);
        assert_eq!(back.public_key, record.public_key);
        // Timestamps lose sub-second precision, compare seconds
        assert_eq!(
            back.cert_expires
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs(),
            record
                .cert_expires
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs()
        );
        assert_eq!(
            back.registered_at
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs(),
            record
                .registered_at
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs()
        );
        assert!(back.last_rotation.is_some());
        assert!(back.renewal_nudge_sent.is_none());
        assert_eq!(back.secret_prefix, Some("brave-falcon".to_string()));
        assert_eq!(back.valid_secrets.len(), 2);
        assert!(back.valid_secrets.contains(&"brave-health".to_string()));
        assert!(back.valid_secrets.contains(&"swift-outreach".to_string()));
        // retired_secrets must survive the Firestore wire roundtrip (#519).
        assert_eq!(
            back.retired_secrets.get("calendar").map(String::as_str),
            Some("stale-calendar")
        );
    }

    #[test]
    fn reservation_roundtrip() {
        let r = SubdomainReservation {
            fqdn: "coral.rousecontext.com".to_string(),
            firebase_uid: "uid-xyz".to_string(),
            expires_at: UNIX_EPOCH + Duration::from_secs(1_712_000_600),
            base_domain: "rousecontext.com".to_string(),
            created_at: UNIX_EPOCH + Duration::from_secs(1_712_000_000),
        };
        let fields = reservation_to_fields(&r);
        let back = reservation_from_fields(&fields).unwrap();
        assert_eq!(back.fqdn, r.fqdn);
        assert_eq!(back.firebase_uid, r.firebase_uid);
        assert_eq!(back.base_domain, r.base_domain);
        assert_eq!(
            back.expires_at
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs(),
            r.expires_at.duration_since(UNIX_EPOCH).unwrap().as_secs()
        );
        assert_eq!(
            back.created_at
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs(),
            r.created_at.duration_since(UNIX_EPOCH).unwrap().as_secs()
        );
    }

    #[test]
    fn pending_cert_roundtrip() {
        let cert = PendingCert {
            fcm_token: "tok".to_string(),
            csr: "csr-pem".to_string(),
            blocked_at: UNIX_EPOCH + Duration::from_secs(1_712_000_000),
            retry_after: UNIX_EPOCH + Duration::from_secs(1_712_000_060),
        };
        let fields = pending_cert_to_fields(&cert);
        let back = pending_cert_from_fields(&fields).unwrap();
        assert_eq!(back.fcm_token, cert.fcm_token);
        assert_eq!(back.csr, cert.csr);
        assert_eq!(
            back.blocked_at
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs(),
            cert.blocked_at
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs()
        );
    }

    #[test]
    fn doc_id_extraction() {
        assert_eq!(
            doc_id_from_name("projects/p/databases/(default)/documents/devices/abc123"),
            "abc123"
        );
        assert_eq!(doc_id_from_name("abc123"), "abc123");
    }

    #[test]
    fn null_optional_timestamps() {
        let record = DeviceRecord {
            fcm_token: "t".to_string(),
            firebase_uid: "u".to_string(),
            key_thumbprint: None,
            public_key: "k".to_string(),
            key_binding: crate::firestore::KeyBinding::Bound,
            cert_expires: UNIX_EPOCH + Duration::from_secs(1_000_000),
            registered_at: UNIX_EPOCH + Duration::from_secs(1_000_000),
            last_seen: None,
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            push_kind: Default::default(),
            push_endpoint: String::new(),
            valid_secrets: Vec::new(),
            integration_secrets: std::collections::HashMap::new(),
            retired_secrets: std::collections::HashMap::new(),
        };
        let fields = device_record_to_fields(&record);
        let back = device_record_from_fields(&fields).unwrap();
        assert!(back.last_rotation.is_none());
        assert!(back.renewal_nudge_sent.is_none());
        assert!(back.secret_prefix.is_none());
        assert!(back.valid_secrets.is_empty());
        assert!(back.integration_secrets.is_empty());
    }

    #[test]
    fn integration_secrets_roundtrip_preserves_mapping() {
        let record = DeviceRecord {
            fcm_token: "t".to_string(),
            firebase_uid: "u".to_string(),
            key_thumbprint: None,
            public_key: "k".to_string(),
            key_binding: crate::firestore::KeyBinding::Bound,
            cert_expires: UNIX_EPOCH + Duration::from_secs(1_000_000),
            registered_at: UNIX_EPOCH + Duration::from_secs(1_000_000),
            last_seen: None,
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            push_kind: Default::default(),
            push_endpoint: String::new(),
            valid_secrets: vec!["brave-health".to_string(), "swift-outreach".to_string()],
            integration_secrets: std::collections::HashMap::from([
                ("health".to_string(), "brave-health".to_string()),
                ("outreach".to_string(), "swift-outreach".to_string()),
            ]),
            retired_secrets: std::collections::HashMap::new(),
        };
        let fields = device_record_to_fields(&record);
        let back = device_record_from_fields(&fields).unwrap();
        assert_eq!(back.integration_secrets.len(), 2);
        assert_eq!(
            back.integration_secrets.get("health").map(String::as_str),
            Some("brave-health")
        );
        assert_eq!(
            back.integration_secrets.get("outreach").map(String::as_str),
            Some("swift-outreach")
        );
    }

    #[test]
    fn device_record_serde_defaults_integration_secrets_when_missing() {
        // Guards the `#[serde(default)]` annotation: direct serde_json
        // deserialization of a legacy JSON payload (no integration_secrets
        // field) must succeed with an empty map.
        let legacy = serde_json::json!({
            "fcm_token": "t",
            "firebase_uid": "u",
            "public_key": "k",
            "cert_expires": {"secs_since_epoch": 1_000_000, "nanos_since_epoch": 0},
            "registered_at": {"secs_since_epoch": 1_000_000, "nanos_since_epoch": 0},
            "last_rotation": null,
            "renewal_nudge_sent": null,
            "secret_prefix": null,
            "valid_secrets": ["brave-health"]
        });
        let back: DeviceRecord = serde_json::from_value(legacy).unwrap();
        assert!(back.integration_secrets.is_empty());
        assert_eq!(back.valid_secrets, vec!["brave-health".to_string()]);
    }

    #[test]
    fn legacy_doc_without_integration_secrets_deserializes_as_empty_map() {
        // A Firestore document written before #148 has no integration_secrets
        // field at all. The reader must tolerate the missing field and
        // default to an empty map.
        let mut fields = device_record_to_fields(&DeviceRecord {
            fcm_token: "t".to_string(),
            firebase_uid: "u".to_string(),
            key_thumbprint: None,
            public_key: "k".to_string(),
            key_binding: crate::firestore::KeyBinding::Bound,
            cert_expires: UNIX_EPOCH + Duration::from_secs(1_000_000),
            registered_at: UNIX_EPOCH + Duration::from_secs(1_000_000),
            last_seen: None,
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            push_kind: Default::default(),
            push_endpoint: String::new(),
            valid_secrets: vec!["brave-health".to_string()],
            integration_secrets: std::collections::HashMap::new(),
            retired_secrets: std::collections::HashMap::new(),
        });
        // Simulate the legacy shape by removing the field entirely.
        fields.remove("integration_secrets");
        let back = device_record_from_fields(&fields).unwrap();
        assert!(back.integration_secrets.is_empty());
        // The SNI path still sees the flat list intact.
        assert_eq!(back.valid_secrets, vec!["brave-health".to_string()]);
    }

    #[test]
    fn unifiedpush_device_record_roundtrip() {
        // A foss device stores push_kind=unifiedpush + an endpoint URL and no
        // fcm_token; the round-trip must preserve both.
        let record = DeviceRecord {
            fcm_token: String::new(),
            firebase_uid: String::new(),
            key_thumbprint: Some("thumb-abc".to_string()),
            public_key: "k".to_string(),
            key_binding: crate::firestore::KeyBinding::Bound,
            cert_expires: UNIX_EPOCH + Duration::from_secs(1_000_000),
            registered_at: UNIX_EPOCH + Duration::from_secs(1_000_000),
            last_seen: None,
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            push_kind: crate::firestore::PushKind::UnifiedPush,
            push_endpoint: "https://ntfy.sh/up_abc123".to_string(),
            valid_secrets: Vec::new(),
            integration_secrets: std::collections::HashMap::new(),
            retired_secrets: std::collections::HashMap::new(),
        };
        let fields = device_record_to_fields(&record);
        let back = device_record_from_fields(&fields).unwrap();
        assert_eq!(back.push_kind, crate::firestore::PushKind::UnifiedPush);
        assert_eq!(back.push_endpoint, "https://ntfy.sh/up_abc123");
        assert!(back.fcm_token.is_empty());
    }

    #[test]
    fn legacy_doc_without_push_fields_defaults_to_fcm() {
        // A Firestore document written before #463 has neither push_kind nor
        // push_endpoint. The reader must default push_kind to FCM and the
        // endpoint to empty, so existing google devices keep waking via FCM.
        let mut fields = device_record_to_fields(&DeviceRecord {
            fcm_token: "tok".to_string(),
            firebase_uid: "u".to_string(),
            key_thumbprint: None,
            public_key: "k".to_string(),
            key_binding: crate::firestore::KeyBinding::Bound,
            cert_expires: UNIX_EPOCH + Duration::from_secs(1_000_000),
            registered_at: UNIX_EPOCH + Duration::from_secs(1_000_000),
            last_seen: None,
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            push_kind: Default::default(),
            push_endpoint: String::new(),
            valid_secrets: Vec::new(),
            integration_secrets: std::collections::HashMap::new(),
            retired_secrets: std::collections::HashMap::new(),
        });
        fields.remove("push_kind");
        fields.remove("push_endpoint");
        let back = device_record_from_fields(&fields).unwrap();
        assert_eq!(back.push_kind, crate::firestore::PushKind::Fcm);
        assert!(back.push_endpoint.is_empty());
        assert_eq!(back.fcm_token, "tok");
    }

    #[test]
    fn parse_rfc3339_valid() {
        let t = parse_rfc3339("2025-06-15T12:30:00Z").unwrap();
        let secs = t.duration_since(UNIX_EPOCH).unwrap().as_secs();
        // 2025-06-15T12:30:00Z
        assert!(secs > 1_700_000_000);
    }

    #[test]
    fn parse_rfc3339_invalid() {
        assert!(parse_rfc3339("not-a-date").is_err());
    }

    #[test]
    fn missing_field_gives_serialization_error() {
        let fields = serde_json::Map::new();
        let result = device_record_from_fields(&fields);
        assert!(result.is_err());
        match result.unwrap_err() {
            FirestoreError::Serialization(msg) => {
                assert!(msg.contains("fcm_token"), "got: {msg}");
            }
            other => panic!("expected Serialization error, got: {other:?}"),
        }
    }
}
