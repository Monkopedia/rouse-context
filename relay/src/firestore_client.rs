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
            401 | 403 => Err(PendingError::AuthRetry(status)),
            _ => Err(PendingError::Final(FirestoreError::Http(format!(
                "Firestore returned {status} for {path}"
            )))),
        }
    }

    /// Authenticated GET with one retry on auth errors.
    async fn get_with_retry(&self, path: &str) -> Result<reqwest::Response, FirestoreError> {
        let url = self.url(path);
        let token = self.token().await?;
        let resp = self
            .http
            .get(&url)
            .bearer_auth(&token)
            .send()
            .await
            .map_err(|e| FirestoreError::Http(e.to_string()))?;
        match Self::map_status(resp, path) {
            Ok(r) => Ok(r),
            Err(PendingError::AuthRetry(status)) => {
                warn!(status, path, "Firestore auth error, retrying");
                let token = self.token().await?;
                let resp = self
                    .http
                    .get(&url)
                    .bearer_auth(&token)
                    .send()
                    .await
                    .map_err(|e| FirestoreError::Http(e.to_string()))?;
                Self::map_status(resp, path).map_err(PendingError::into_final)
            }
            Err(PendingError::Final(e)) => Err(e),
        }
    }

    /// Authenticated PATCH (upsert) with one retry on auth errors.
    async fn patch_with_retry(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> Result<reqwest::Response, FirestoreError> {
        let url = self.url(path);
        let token = self.token().await?;
        let resp = self
            .http
            .patch(&url)
            .bearer_auth(&token)
            .json(body)
            .send()
            .await
            .map_err(|e| FirestoreError::Http(e.to_string()))?;
        match Self::map_status(resp, path) {
            Ok(r) => Ok(r),
            Err(PendingError::AuthRetry(status)) => {
                warn!(status, path, "Firestore auth error, retrying");
                let token = self.token().await?;
                let resp = self
                    .http
                    .patch(&url)
                    .bearer_auth(&token)
                    .json(body)
                    .send()
                    .await
                    .map_err(|e| FirestoreError::Http(e.to_string()))?;
                Self::map_status(resp, path).map_err(PendingError::into_final)
            }
            Err(PendingError::Final(e)) => Err(e),
        }
    }

    /// Authenticated DELETE with one retry on auth errors.
    async fn delete_with_retry(&self, path: &str) -> Result<reqwest::Response, FirestoreError> {
        let url = self.url(path);
        let token = self.token().await?;
        let resp = self
            .http
            .delete(&url)
            .bearer_auth(&token)
            .send()
            .await
            .map_err(|e| FirestoreError::Http(e.to_string()))?;
        match Self::map_status(resp, path) {
            Ok(r) => Ok(r),
            Err(PendingError::AuthRetry(status)) => {
                warn!(status, path, "Firestore auth error, retrying");
                let token = self.token().await?;
                let resp = self
                    .http
                    .delete(&url)
                    .bearer_auth(&token)
                    .send()
                    .await
                    .map_err(|e| FirestoreError::Http(e.to_string()))?;
                Self::map_status(resp, path).map_err(PendingError::into_final)
            }
            Err(PendingError::Final(e)) => Err(e),
        }
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

fn device_record_to_fields(record: &DeviceRecord) -> serde_json::Map<String, serde_json::Value> {
    let mut fields = serde_json::Map::new();
    fields.insert("fcm_token".to_string(), string_val(&record.fcm_token));
    fields.insert("firebase_uid".to_string(), string_val(&record.firebase_uid));
    fields.insert("public_key".to_string(), string_val(&record.public_key));
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
        "valid_secrets".to_string(),
        string_array_val(&record.valid_secrets),
    );
    fields
}

fn device_record_from_fields(
    fields: &serde_json::Map<String, serde_json::Value>,
) -> Result<DeviceRecord, FirestoreError> {
    Ok(DeviceRecord {
        fcm_token: read_string(fields, "fcm_token")?,
        firebase_uid: read_string(fields, "firebase_uid")?,
        public_key: read_string(fields, "public_key")?,
        cert_expires: read_timestamp(fields, "cert_expires")?,
        registered_at: read_timestamp(fields, "registered_at")?,
        last_rotation: read_opt_timestamp(fields, "last_rotation")?,
        renewal_nudge_sent: read_opt_timestamp(fields, "renewal_nudge_sent")?,
        secret_prefix: read_opt_string(fields, "secret_prefix"),
        valid_secrets: read_string_array(fields, "valid_secrets"),
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

    async fn put_device(
        &self,
        subdomain: &str,
        record: &DeviceRecord,
    ) -> Result<(), FirestoreError> {
        let path = format!("devices/{subdomain}");
        let body = serde_json::json!({ "fields": device_record_to_fields(record) });
        let resp = self.patch_with_retry(&path, &body).await?;
        debug!(subdomain, status = resp.status().as_u16(), "put_device OK");
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

    #[test]
    fn device_record_roundtrip() {
        let record = DeviceRecord {
            fcm_token: "tok123".to_string(),
            firebase_uid: "uid456".to_string(),
            public_key: "cHVia2V5".to_string(),
            cert_expires: UNIX_EPOCH + Duration::from_secs(1_750_000_000),
            registered_at: UNIX_EPOCH + Duration::from_secs(1_712_000_000),
            last_rotation: Some(UNIX_EPOCH + Duration::from_secs(1_713_000_000)),
            renewal_nudge_sent: None,
            secret_prefix: Some("brave-falcon".to_string()),
            valid_secrets: vec!["brave-health".to_string(), "swift-outreach".to_string()],
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
            public_key: "k".to_string(),
            cert_expires: UNIX_EPOCH + Duration::from_secs(1_000_000),
            registered_at: UNIX_EPOCH + Duration::from_secs(1_000_000),
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            valid_secrets: Vec::new(),
        };
        let fields = device_record_to_fields(&record);
        let back = device_record_from_fields(&fields).unwrap();
        assert!(back.last_rotation.is_none());
        assert!(back.renewal_nudge_sent.is_none());
        assert!(back.secret_prefix.is_none());
        assert!(back.valid_secrets.is_empty());
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
