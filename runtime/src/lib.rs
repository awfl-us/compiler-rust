//! Shared data contracts for compiled native workflows.
//!
//! This is a value/protocol foundation, NOT a workflow graph interpreter. Generated
//! Rust will run computation locally and yield only at durable effect boundaries.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;

/// The persistent representation must distinguish bytes from text, integers from
/// floating point, and a missing slot from an explicitly bound null value.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum AwflValue {
    Null,
    Bool(bool),
    Integer(i64),
    Float(f64),
    String(String),
    Bytes(Vec<u8>),
    List(Vec<AwflValue>),
    Map(BTreeMap<String, AwflValue>),
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "state", content = "value", rename_all = "snake_case")]
pub enum SlotValue {
    Missing,
    Bound(AwflValue),
}

#[derive(Debug)]
pub enum CodecError {
    NonFiniteFloat,
    InvalidEncoding(serde_json::Error),
}

impl fmt::Display for CodecError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CodecError::NonFiniteFloat => {
                write!(f, "non-finite floating point value is not persistable")
            }
            CodecError::InvalidEncoding(e) => write!(f, "invalid tagged value encoding: {e}"),
        }
    }
}

impl std::error::Error for CodecError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::InvalidEncoding(e) => Some(e),
            Self::NonFiniteFloat => None,
        }
    }
}

impl From<serde_json::Error> for CodecError {
    fn from(value: serde_json::Error) -> Self {
        Self::InvalidEncoding(value)
    }
}

impl AwflValue {
    pub fn validate(&self) -> Result<(), CodecError> {
        match self {
            AwflValue::Float(n) if !n.is_finite() => Err(CodecError::NonFiniteFloat),
            AwflValue::List(values) => {
                for value in values {
                    value.validate()?;
                }
                Ok(())
            }
            AwflValue::Map(values) => {
                for value in values.values() {
                    value.validate()?;
                }
                Ok(())
            }
            _ => Ok(()),
        }
    }

    /// Stable, tagged, UTF-8 JSON encoding for the initial protocol. The exact
    /// format is versioned independently by the eventual artifact manifest.
    pub fn to_tagged_json(&self) -> Result<Vec<u8>, CodecError> {
        self.validate()?;
        Ok(serde_json::to_vec(self)?)
    }

    pub fn from_tagged_json(data: &[u8]) -> Result<Self, CodecError> {
        let value: Self = serde_json::from_slice(data)?;
        value.validate()?;
        Ok(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(value: AwflValue) {
        let bytes = value.to_tagged_json().expect("encode tagged value");
        assert_eq!(AwflValue::from_tagged_json(&bytes).unwrap(), value);
    }

    #[test]
    fn tagged_values_are_lossless_across_replay() {
        let mut map = BTreeMap::new();
        map.insert("null".to_owned(), AwflValue::Null);
        map.insert("number".to_owned(), AwflValue::Integer(i64::MAX));
        map.insert("real".to_owned(), AwflValue::Float(-0.125));
        map.insert("bytes".to_owned(), AwflValue::Bytes(vec![0, 255, 10]));
        map.insert("text".to_owned(), AwflValue::String("é🙂".to_owned()));
        roundtrip(AwflValue::Map(map));
    }

    #[test]
    fn missing_slot_is_not_null() {
        let missing = serde_json::to_vec(&SlotValue::Missing).unwrap();
        let bound_null = serde_json::to_vec(&SlotValue::Bound(AwflValue::Null)).unwrap();
        assert_ne!(missing, bound_null);
    }

    #[test]
    fn bytes_cannot_be_misread_as_text() {
        let bytes = AwflValue::Bytes(vec![97, 98]);
        let text = AwflValue::String("ab".to_owned());
        assert_ne!(
            bytes.to_tagged_json().unwrap(),
            text.to_tagged_json().unwrap()
        );
        roundtrip(bytes);
        roundtrip(text);
    }

    #[test]
    fn rejects_non_finite_even_when_nested() {
        let v = AwflValue::List(vec![AwflValue::Float(f64::NAN)]);
        assert!(matches!(
            v.to_tagged_json(),
            Err(CodecError::NonFiniteFloat)
        ));
        let v = AwflValue::Float(f64::INFINITY);
        assert!(matches!(
            v.to_tagged_json(),
            Err(CodecError::NonFiniteFloat)
        ));
    }
}
