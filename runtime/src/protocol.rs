//! Versioned, bounded JSON-in/JSON-out boundary for native effect suspension.
//! This module only describes data. It never performs effects or interprets a
//! workflow; the server authenticates the execution and journals each intent.
use crate::AwflValue;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const VERSION: u32 = 1;

/// A validated dynamic invocation location for resumable control flow.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InvocationFrame {
    pub node_id: String,
    pub iteration: u64,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Continuation {
    /// The next *compiled top-level instruction* to execute.
    pub next_step: usize,
    /// Only the live, already-bound result slots. Missing is not null.
    pub slots: BTreeMap<String, AwflValue>,
    #[serde(default)]
    pub frames: Vec<InvocationFrame>,
}

impl Continuation {
    pub fn slot(&self, name: &str) -> Result<AwflValue, String> {
        self.slots
            .get(name)
            .cloned()
            .ok_or_else(|| format!("missing continuation slot: {name}"))
    }

    pub fn validate(&self, step_count: usize) -> Result<(), String> {
        if self.next_step > step_count {
            return Err("continuation step outside compiled workflow".into());
        }
        // Nested continuation frames are allowed in v1 for compiler-managed
        // resumable control flow. Validate bounds and identifier shapes.
        if self.frames.len() > 64 {
            return Err("continuation exceeds structural limits".into());
        }
        for frame in &self.frames {
            if frame.node_id.is_empty()
                || frame.node_id.len() > 120
                || !frame
                    .node_id
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-' || c == '.')
            {
                return Err("invalid continuation frame identifier".into());
            }
            // The server stores JSON numbers in JavaScript, so frame indices
            // must round-trip without precision loss.
            if frame.iteration > 9_007_199_254_740_991 {
                return Err("continuation frame iteration exceeds safe integer range".into());
            }
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
    #[serde(default)]
    pub continuation: Option<Continuation>,
}

impl Request {
    pub fn validate(&self, step_count: usize) -> Result<(), String> {
        if self.protocol_version != VERSION {
            return Err(format!(
                "unsupported native protocol version {}",
                self.protocol_version
            ));
        }
        if let Some(continuation) = &self.continuation {
            continuation.validate(step_count)?;
        }
        Ok(())
    }
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
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum Outcome {
    Completed {
        result: serde_json::Value,
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
        assert!(request.validate(2).unwrap_err().contains("version"));
        let invalid = Request {
            protocol_version: VERSION,
            input: serde_json::json!(null),
            continuation: Some(Continuation {
                next_step: 3,
                slots: BTreeMap::new(),
                frames: vec![],
            }),
        };
        assert!(invalid.validate(2).unwrap_err().contains("outside"));
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
    fn validates_nested_frames_within_bounds() {
        let mut slots = BTreeMap::new();
        slots.insert("s0".into(), AwflValue::Integer(1));
        let ok = Continuation {
            next_step: 1,
            slots,
            frames: vec![InvocationFrame {
                node_id: "0.2".into(),
                iteration: 3,
            }],
        };
        assert!(ok.validate(3).is_ok());

        let bad_id = Continuation {
            next_step: 1,
            slots: BTreeMap::new(),
            frames: vec![InvocationFrame {
                node_id: "bad id!".into(),
                iteration: 0,
            }],
        };
        assert!(bad_id.validate(2).unwrap_err().contains("frame identifier"));

        let too_many = Continuation {
            next_step: 0,
            slots: BTreeMap::new(),
            frames: (0..65)
                .map(|i| InvocationFrame {
                    node_id: format!("n{i}"),
                    iteration: i,
                })
                .collect(),
        };
        assert!(
            too_many
                .validate(10)
                .unwrap_err()
                .contains("exceeds structural limits")
        );
        let unsafe_iteration = Continuation {
            next_step: 0,
            slots: BTreeMap::new(),
            frames: vec![InvocationFrame {
                node_id: "0.0".into(),
                iteration: 9_007_199_254_740_992,
            }],
        };
        assert!(
            unsafe_iteration
                .validate(2)
                .unwrap_err()
                .contains("safe integer")
        );
    }
}
