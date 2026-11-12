//! Versioned, bounded JSON-in/JSON-out boundary for native effect suspension.
//! This module only describes data. It never performs effects or interprets a
//! workflow; the server authenticates the execution and journals each intent.
use crate::AwflValue;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const VERSION: u32 = 2;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Continuation {
    /// The next statically compiled CFG block to execute.
    pub next_step: usize,
    /// Only the live, already-bound result slots. Missing is not null.
    pub slots: BTreeMap<String, AwflValue>,
    /// Always empty: nested control flow is checkpointed in live slots.
    /// Kept in the wire shape expected by the already deployed Jobs service.
    #[serde(default)]
    pub frames: Vec<serde_json::Value>,
}

impl Continuation {
    pub fn slot(&self, name: &str) -> Result<AwflValue, String> {
        self.slots
            .get(name)
            .cloned()
            .ok_or_else(|| format!("missing continuation slot: {name}"))
    }

    pub fn validate(&self, step_count: usize) -> Result<(), String> {
        if self.next_step >= step_count {
            return Err("continuation step outside compiled workflow".into());
        }
        if !self.frames.is_empty() {
            return Err("invalid native continuation: frames must be empty".into());
        }
        if self.slots.len() > 10000 {
            return Err("continuation exceeds structural limits".into());
        }
        for (key, value) in &self.slots {
            if key.len() > 120 || !key.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
                return Err("invalid continuation slot identifier".into());
            }
            value.validate().map_err(|e| e.to_string())?;
        }
        Ok(())
    }
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub protocol_version: u32,
    /// Public API input is plain JSON. Continuation values are tagged.
    pub input: serde_json::Value,
    /// Explicitly supplied by the authenticated server from its persisted
    /// execution identity and deployment configuration, never from user input.
    /// Optional for existing pure fixtures and older protocol-v2 runners.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trusted_globals: Option<BTreeMap<String, String>>,
    #[serde(default)]
    pub continuation: Option<Continuation>,
}

impl Request {
    pub fn validate(&self, step_count: usize) -> Result<(), String> {
        if self.protocol_version != VERSION {
            return Err(format!(
                "unsupported native protocol version {} (expected {})",
                self.protocol_version, VERSION
            ));
        }
        if let Some(globals) = &self.trusted_globals {
            const KEYS: [&str; 4] = [
                "GOOGLE_CLOUD_WORKFLOW_ID",
                "GOOGLE_CLOUD_WORKFLOW_EXECUTION_ID",
                "GOOGLE_CLOUD_PROJECT_ID",
                "GOOGLE_CLOUD_LOCATION",
            ];
            if globals.len() != KEYS.len()
                || KEYS.iter().any(|key| !globals.contains_key(*key))
                || globals
                    .values()
                    .any(|value| value.is_empty() || value.len() > 256)
            {
                return Err("invalid trusted native execution globals".into());
            }
        }
        if let Some(continuation) = &self.continuation {
            continuation.validate(step_count)?;
        }
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EffectErrorHandler {
    pub next_step: usize,
    pub error_slot: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EffectIntent {
    pub effect_id: String,
    pub node_id: String,
    pub operation: String,
    /// Stable IR result slot to bind exactly once after the journal records
    /// the remote outcome. Never treat a missing result as a bound null.
    pub result_slot: String,
    pub args: serde_json::Value,
    /// Optional compiled exception edge, persisted by the server when
    /// preparing a recoverable effect. Never catch uncertain outcomes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub on_error: Option<EffectErrorHandler>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum Outcome {
    Completed {
        result: serde_json::Value,
    },
    Failed {
        error: serde_json::Value,
    },
    Suspended {
        effect: EffectIntent,
        continuation: Continuation,
    },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Response {
    pub protocol_version: u32,
    #[serde(flatten)]
    pub outcome: Outcome,
}

impl Response {
    pub fn new(outcome: Outcome) -> Self {
        Self {
            protocol_version: VERSION,
            outcome,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn continuation_roundtrips_tagged_values_and_missing_is_not_null() {
        let mut slots = BTreeMap::new();
        slots.insert("s0".into(), AwflValue::Null);
        slots.insert("s1".into(), AwflValue::Bytes(vec![0, 255]));
        let continuation = Continuation {
            next_step: 2,
            slots,
            frames: vec![],
        };
        let serialized = serde_json::to_string(&continuation).unwrap();
        let restored: Continuation = serde_json::from_str(&serialized).unwrap();
        assert_eq!(restored, continuation);
        assert_eq!(restored.slot("s0").unwrap(), AwflValue::Null);
        assert!(restored.slot("s2").unwrap_err().contains("missing"));
    }

    #[test]
    fn rejects_unknown_version_and_invalid_pc_or_slots() {
        let request: Request = serde_json::from_str(r#"{"protocol_version":2,"input":1}"#).unwrap();
        assert!(request.validate(2).is_ok());
        let wrong_version: Request =
            serde_json::from_str(r#"{"protocol_version":1,"input":1}"#).unwrap();
        assert!(wrong_version.validate(2).unwrap_err().contains("version"));
        let invalid = Request {
            protocol_version: VERSION,
            input: serde_json::json!(null),
            trusted_globals: None,
            continuation: Some(Continuation {
                next_step: 3,
                slots: BTreeMap::new(),
                frames: vec![],
            }),
        };
        assert!(invalid.validate(2).unwrap_err().contains("outside"));
    }

    #[test]
    fn trusted_execution_context_requires_exact_builtins() {
        let globals = BTreeMap::from([
            ("GOOGLE_CLOUD_WORKFLOW_ID".into(), "wf".into()),
            (
                "GOOGLE_CLOUD_WORKFLOW_EXECUTION_ID".into(),
                "native_abc".into(),
            ),
            ("GOOGLE_CLOUD_PROJECT_ID".into(), "cloud-project".into()),
            ("GOOGLE_CLOUD_LOCATION".into(), "us-central1".into()),
        ]);
        let make = |trusted_globals| Request {
            protocol_version: VERSION,
            input: serde_json::json!({"env": {"GOOGLE_CLOUD_PROJECT_ID": "spoofed"}}),
            trusted_globals,
            continuation: None,
        };
        assert!(make(Some(globals.clone())).validate(2).is_ok());
        assert!(make(None).validate(2).is_ok()); // Legacy pure fixtures.
        let encoded = serde_json::to_string(&make(Some(globals.clone()))).unwrap();
        assert_eq!(
            serde_json::from_str::<Request>(&encoded)
                .unwrap()
                .trusted_globals
                .unwrap(),
            globals
        );
        let mut invalid = globals;
        invalid.insert("USER_SECRET".into(), "untrusted".into());
        assert!(make(Some(invalid)).validate(2).is_err());
    }

    #[test]
    fn response_roundtrips_suspended_intent() {
        let response = Response::new(Outcome::Suspended {
            effect: EffectIntent {
                effect_id: "call_1".into(),
                node_id: "0.1".into(),
                operation: "http.post".into(),
                result_slot: "s0".into(),
                args: serde_json::json!({"url":"https://example.test"}),
                on_error: None,
            },
            continuation: Continuation {
                next_step: 2,
                slots: BTreeMap::new(),
                frames: vec![],
            },
        });
        let output = serde_json::to_string(&response).unwrap();
        assert_eq!(serde_json::from_str::<Response>(&output).unwrap(), response);
    }

    #[test]
    fn state_machine_continuations_reject_frames_and_bad_pc() {
        let make = |version, next_step, frames| Request {
            protocol_version: version,
            input: serde_json::Value::Null,
            trusted_globals: None,
            continuation: Some(Continuation {
                next_step,
                slots: BTreeMap::new(),
                frames,
            }),
        };
        assert!(make(2, 2, vec![]).validate(3).is_ok());
        assert!(make(1, 2, vec![]).validate(3).is_err());
        assert!(make(2, 3, vec![]).validate(3).is_err());
        let frames = vec![serde_json::json!({"node_id": "0.0", "iteration": 0})];
        assert!(
            make(2, 0, frames)
                .validate(3)
                .unwrap_err()
                .contains("frames")
        );
        let response = Response::new(Outcome::Completed {
            result: serde_json::json!([1, 2]),
        });
        assert_eq!(response.protocol_version, 2);
    }
}
