//! Shared data contracts for compiled native workflows.
//!
//! This is a value/protocol foundation, NOT a workflow graph interpreter. Generated
//! Rust will run computation locally and yield only at durable effect boundaries.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;

pub mod operations;
pub mod protocol;

#[derive(Debug, PartialEq)]
pub enum ValueError {
    Type(String),
    MissingField(String),
    Overflow,
    NonFiniteFloat,
    /// Compiler-attached DSL context. Retain ValueError for native Try/Catch.
    Context {
        node: String,
        operation: String,
        operand_types: Option<(String, String)>,
        source: Box<ValueError>,
    },
}
impl ValueError {
    pub fn at_node(self, node: &str, operation: &str) -> Self {
        Self::Context {
            node: node.into(),
            operation: operation.into(),
            operand_types: None,
            source: Box::new(self),
        }
    }

    pub fn at_binary(
        self,
        node: &str,
        operation: &str,
        left: &AwflValue,
        right: &AwflValue,
    ) -> Self {
        self.at_operand_types(node, operation, left.type_name(), right.type_name())
    }

    pub fn at_operand_types(self, node: &str, operation: &str, left: &str, right: &str) -> Self {
        Self::Context {
            node: node.into(),
            operation: operation.into(),
            operand_types: Some((left.into(), right.into())),
            source: Box::new(self),
        }
    }
}
impl fmt::Display for ValueError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Type(message) => write!(f, "{message}"),
            Self::MissingField(name) => write!(f, "missing field or element: {name}"),
            Self::Overflow => write!(f, "signed 64-bit integer overflow"),
            Self::NonFiniteFloat => write!(f, "non-finite floating point number"),
            Self::Context {
                node,
                operation,
                operand_types,
                source,
            } => {
                write!(f, "node={node} operation={operation}")?;
                if let Some((left, right)) = operand_types {
                    write!(f, " left_type={left} right_type={right}")?;
                }
                write!(f, ": {source}")
            }
        }
    }
}
impl std::error::Error for ValueError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Context { source, .. } => Some(source.as_ref()),
            _ => None,
        }
    }
}

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
impl AwflValue {
    /// Expose only the type, never argument values in error diagnostics.
    pub fn type_name(&self) -> &'static str {
        match self {
            Self::Null => "Null",
            Self::Bool(_) => "Bool",
            Self::Integer(_) => "Integer",
            Self::Float(_) => "Float",
            Self::String(_) => "String",
            Self::Bytes(_) => "Bytes",
            Self::List(_) => "List",
            Self::Map(_) => "Map",
        }
    }
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
    /// Public JSON boundary. Unlike persisted tagged values, JSON has no bytes
    /// representation. Reject bytes and out-of-range integers rather than
    /// accidentally coercing them to strings or floating point.
    pub fn from_plain_json(json: &serde_json::Value) -> Result<Self, ValueError> {
        use serde_json::Value;
        match json {
            Value::Null => Ok(Self::Null),
            Value::Bool(b) => Ok(Self::Bool(*b)),
            Value::String(s) => Ok(Self::String(s.clone())),
            Value::Number(n) => {
                if let Some(i) = n.as_i64() {
                    Ok(Self::Integer(i))
                } else if n.as_u64().is_some() {
                    Err(ValueError::Overflow)
                } else {
                    let v = n.as_f64().ok_or(ValueError::NonFiniteFloat)?;
                    if v.is_finite() {
                        Ok(Self::Float(v))
                    } else {
                        Err(ValueError::NonFiniteFloat)
                    }
                }
            }
            Value::Array(a) => a
                .iter()
                .map(Self::from_plain_json)
                .collect::<Result<Vec<_>, _>>()
                .map(Self::List),
            Value::Object(m) => m
                .iter()
                .map(|(k, v)| Ok((k.clone(), Self::from_plain_json(v)?)))
                .collect::<Result<BTreeMap<_, _>, _>>()
                .map(Self::Map),
        }
    }

    pub fn to_plain_json(&self) -> Result<serde_json::Value, ValueError> {
        use serde_json::Value;
        match self {
            Self::Null => Ok(Value::Null),
            Self::Bool(b) => Ok(Value::Bool(*b)),
            Self::Integer(i) => Ok(Value::Number((*i).into())),
            Self::Float(f) => serde_json::Number::from_f64(*f)
                .map(Value::Number)
                .ok_or(ValueError::NonFiniteFloat),
            Self::String(s) => Ok(Value::String(s.clone())),
            Self::Bytes(_) => Err(ValueError::Type("bytes require tagged encoding".into())),
            Self::List(a) => a
                .iter()
                .map(Self::to_plain_json)
                .collect::<Result<Vec<_>, _>>()
                .map(Value::Array),
            Self::Map(m) => m
                .iter()
                .map(|(k, v)| Ok((k.clone(), v.to_plain_json()?)))
                .collect::<Result<serde_json::Map<_, _>, _>>()
                .map(Value::Object),
        }
    }

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

#[cfg(test)]
mod expression_diagnostic_tests {
    use super::{AwflValue, ValueError};
    use std::error::Error;

    #[test]
    fn context_preserves_value_error_type_and_hides_operand_contents() {
        let left = AwflValue::Float(8.25);
        let right = AwflValue::Integer(2);
        let error = ValueError::Type("integer floor division requires two integers".into())
            .at_binary("weekIndex", "divide_int", &left, &right);
        let message = error.to_string();
        assert!(message.contains("node=weekIndex operation=divide_int"));
        assert!(message.contains("left_type=Float right_type=Integer"));
        assert!(message.contains("integer floor division requires two integers"));
        assert!(!message.contains("8.25"));
        assert!(matches!(error, ValueError::Context { .. }));
        assert!(error.source().is_some());
    }

    #[test]
    fn node_context_preserves_inner_error_for_dsl_catch() {
        let error = ValueError::MissingField("example".into()).at_node("read", "field");
        assert!(matches!(error, ValueError::Context { .. }));
        assert!(error.to_string().starts_with("node=read operation=field"));
        assert!(error.source().is_some());
    }
}
