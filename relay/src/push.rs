//! Transport-agnostic push routing.
//!
//! A device wakes either via FCM (`google` flavor) or UnifiedPush (`foss`
//! flavor), discriminated by [`DeviceRecord::push_kind`]. [`dispatch_push`]
//! picks the right client and sends the shared [`FcmData`] payload, so the wake
//! path (`passthrough`) and the renew-nudge path (`maintenance`) stay
//! transport-agnostic.

use crate::fcm::{FcmClient, FcmData};
use crate::firestore::{DeviceRecord, PushKind};
use crate::unifiedpush::UnifiedPushClient;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum PushError {
    #[error("fcm: {0}")]
    Fcm(String),
    #[error("unifiedpush: {0}")]
    UnifiedPush(String),
    #[error("device has no push target configured for {0:?}")]
    NoTarget(PushKind),
}

/// Route a push to a device based on its [`PushKind`].
///
/// `high_priority` is honoured by FCM (Android high-priority FCM); UnifiedPush
/// has no priority concept and ignores it. Returns [`PushError::NoTarget`] when
/// the device's record has no usable target for its `push_kind` (empty
/// `fcm_token` for FCM, empty `push_endpoint` for UnifiedPush).
pub async fn dispatch_push(
    record: &DeviceRecord,
    fcm: &dyn FcmClient,
    unifiedpush: &dyn UnifiedPushClient,
    data: &FcmData,
    high_priority: bool,
) -> Result<(), PushError> {
    match record.push_kind {
        PushKind::Fcm => {
            if record.fcm_token.is_empty() {
                return Err(PushError::NoTarget(PushKind::Fcm));
            }
            fcm.send_data_message(&record.fcm_token, data, high_priority)
                .await
                .map_err(|e| PushError::Fcm(e.to_string()))
        }
        PushKind::UnifiedPush => {
            if record.push_endpoint.is_empty() {
                return Err(PushError::NoTarget(PushKind::UnifiedPush));
            }
            unifiedpush
                .send_data_message(&record.push_endpoint, data)
                .await
                .map_err(|e| PushError::UnifiedPush(e.to_string()))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fcm::{wake_payload, FcmError};
    use crate::unifiedpush::UnifiedPushError;
    use async_trait::async_trait;
    use std::sync::Mutex;
    use std::time::SystemTime;

    #[derive(Default)]
    struct RecordingFcm {
        sent: Mutex<Vec<(String, FcmData, bool)>>,
    }
    #[async_trait]
    impl FcmClient for RecordingFcm {
        async fn send_data_message(
            &self,
            token: &str,
            data: &FcmData,
            high_priority: bool,
        ) -> Result<(), FcmError> {
            self.sent
                .lock()
                .unwrap()
                .push((token.to_string(), data.clone(), high_priority));
            Ok(())
        }
    }

    #[derive(Default)]
    struct RecordingUp {
        sent: Mutex<Vec<(String, FcmData)>>,
    }
    #[async_trait]
    impl UnifiedPushClient for RecordingUp {
        async fn send_data_message(
            &self,
            endpoint: &str,
            data: &FcmData,
        ) -> Result<(), UnifiedPushError> {
            self.sent
                .lock()
                .unwrap()
                .push((endpoint.to_string(), data.clone()));
            Ok(())
        }
    }

    fn record(push_kind: PushKind, fcm_token: &str, push_endpoint: &str) -> DeviceRecord {
        DeviceRecord {
            fcm_token: fcm_token.to_string(),
            firebase_uid: "uid".to_string(),
            key_thumbprint: None,
            public_key: String::new(),
            cert_expires: SystemTime::UNIX_EPOCH,
            registered_at: SystemTime::now(),
            last_rotation: None,
            renewal_nudge_sent: None,
            secret_prefix: None,
            push_kind,
            push_endpoint: push_endpoint.to_string(),
            valid_secrets: Vec::new(),
            integration_secrets: std::collections::HashMap::new(),
        }
    }

    #[tokio::test]
    async fn fcm_device_routes_to_fcm_client() {
        let fcm = RecordingFcm::default();
        let up = RecordingUp::default();
        let rec = record(PushKind::Fcm, "fcm-tok", "");
        dispatch_push(&rec, &fcm, &up, &wake_payload(), true)
            .await
            .unwrap();
        assert_eq!(fcm.sent.lock().unwrap().len(), 1);
        assert_eq!(fcm.sent.lock().unwrap()[0].0, "fcm-tok");
        assert!(up.sent.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn unifiedpush_device_routes_to_unifiedpush_client() {
        let fcm = RecordingFcm::default();
        let up = RecordingUp::default();
        let rec = record(PushKind::UnifiedPush, "", "https://ntfy.sh/up123");
        dispatch_push(&rec, &fcm, &up, &wake_payload(), true)
            .await
            .unwrap();
        assert_eq!(up.sent.lock().unwrap().len(), 1);
        assert_eq!(up.sent.lock().unwrap()[0].0, "https://ntfy.sh/up123");
        assert_eq!(up.sent.lock().unwrap()[0].1.message_type, "wake");
        assert!(fcm.sent.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn fcm_device_without_token_is_no_target() {
        let fcm = RecordingFcm::default();
        let up = RecordingUp::default();
        let rec = record(PushKind::Fcm, "", "");
        let err = dispatch_push(&rec, &fcm, &up, &wake_payload(), true)
            .await
            .unwrap_err();
        assert!(matches!(err, PushError::NoTarget(PushKind::Fcm)));
    }

    #[tokio::test]
    async fn unifiedpush_device_without_endpoint_is_no_target() {
        let fcm = RecordingFcm::default();
        let up = RecordingUp::default();
        let rec = record(PushKind::UnifiedPush, "", "");
        let err = dispatch_push(&rec, &fcm, &up, &wake_payload(), true)
            .await
            .unwrap_err();
        assert!(matches!(err, PushError::NoTarget(PushKind::UnifiedPush)));
    }
}
