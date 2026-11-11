//! Small compiled-code helpers. Generated Rust calls these directly; this is not
//! a workflow AST evaluator or a mechanism for making external side effects.
use crate::{AwflValue, ValueError};

pub fn int_value(value: &AwflValue) -> Result<i64, ValueError> {
    match value {
        AwflValue::Integer(n) => Ok(*n),
        _ => Err(ValueError::Type("range boundary must be an integer".into())),
    }
}

pub fn list_value(value: &AwflValue) -> Result<Vec<AwflValue>, ValueError> {
    match value {
        AwflValue::List(items) => Ok(items.clone()),
        _ => Err(ValueError::Type("for iteration requires a list".into())),
    }
}

/// Restricted same-kind ordering until mixed-type comparisons have
/// differential fixtures against the Google backend.
pub fn compare(a: &AwflValue, b: &AwflValue) -> Result<std::cmp::Ordering, ValueError> {
    match (a, b) {
        (AwflValue::Integer(x), AwflValue::Integer(y)) => Ok(x.cmp(y)),
        (AwflValue::String(x), AwflValue::String(y)) => Ok(x.cmp(y)),
        (AwflValue::Float(x), AwflValue::Float(y)) => {
            x.partial_cmp(y).ok_or(ValueError::NonFiniteFloat)
        }
        _ => Err(ValueError::Type(
            "comparison requires matching integer, float or text operands".into(),
        )),
    }
}

pub fn field(value: &AwflValue, name: &str) -> Result<AwflValue, ValueError> {
    match value {
        AwflValue::Map(m) => m
            .get(name)
            .cloned()
            .ok_or_else(|| ValueError::MissingField(name.into())),
        _ => Err(ValueError::Type("field access requires a map".into())),
    }
}

pub fn index(value: &AwflValue, index: &AwflValue) -> Result<AwflValue, ValueError> {
    let idx = match index {
        AwflValue::Integer(idx) if *idx >= 0 => {
            usize::try_from(*idx).map_err(|_| ValueError::Type("index out of range".into()))?
        }
        _ => {
            return Err(ValueError::Type(
                "list index must be a nonnegative integer".into(),
            ));
        }
    };
    match value {
        AwflValue::List(items) => items
            .get(idx)
            .cloned()
            .ok_or(ValueError::MissingField(idx.to_string())),
        _ => Err(ValueError::Type("index access requires a list".into())),
    }
}

pub fn bool_value(value: &AwflValue) -> Result<bool, ValueError> {
    match value {
        AwflValue::Bool(b) => Ok(*b),
        _ => Err(ValueError::Type("boolean operator requires bool".into())),
    }
}

pub fn add(a: AwflValue, b: AwflValue) -> Result<AwflValue, ValueError> {
    match (a, b) {
        (AwflValue::Integer(x), AwflValue::Integer(y)) => x
            .checked_add(y)
            .map(AwflValue::Integer)
            .ok_or(ValueError::Overflow),
        (AwflValue::Float(x), AwflValue::Float(y)) => finite(x + y),
        (AwflValue::String(x), AwflValue::String(y)) => Ok(AwflValue::String(x + &y)),
        _ => Err(ValueError::Type("unsupported add operand types".into())),
    }
}
pub fn subtract(a: AwflValue, b: AwflValue) -> Result<AwflValue, ValueError> {
    match (a, b) {
        (AwflValue::Integer(x), AwflValue::Integer(y)) => x
            .checked_sub(y)
            .map(AwflValue::Integer)
            .ok_or(ValueError::Overflow),
        (AwflValue::Float(x), AwflValue::Float(y)) => finite(x - y),
        _ => Err(ValueError::Type(
            "unsupported subtraction operand types".into(),
        )),
    }
}
pub fn multiply(a: AwflValue, b: AwflValue) -> Result<AwflValue, ValueError> {
    match (a, b) {
        (AwflValue::Integer(x), AwflValue::Integer(y)) => x
            .checked_mul(y)
            .map(AwflValue::Integer)
            .ok_or(ValueError::Overflow),
        (AwflValue::Float(x), AwflValue::Float(y)) => finite(x * y),
        _ => Err(ValueError::Type(
            "unsupported multiplication operand types".into(),
        )),
    }
}
pub fn length(value: &AwflValue) -> Result<AwflValue, ValueError> {
    let n = match value {
        AwflValue::List(v) => v.len(),
        AwflValue::Map(v) => v.len(),
        AwflValue::String(v) => v.chars().count(),
        AwflValue::Bytes(v) => v.len(),
        _ => {
            return Err(ValueError::Type(
                "length requires list/map/text/bytes".into(),
            ));
        }
    };
    Ok(AwflValue::Integer(
        i64::try_from(n).map_err(|_| ValueError::Overflow)?,
    ))
}
/// Google Workflows default(val, fallback): only a null value selects the
/// fallback. Never substitute the fallback for false, zero, or empty text.
pub fn default_value(value: AwflValue, fallback: AwflValue) -> AwflValue {
    if value == AwflValue::Null {
        fallback
    } else {
        value
    }
}

/// Google Workflows get_type spellings (not Rust or JSON type names).
pub fn type_of(value: &AwflValue) -> AwflValue {
    let name = match value {
        AwflValue::Null => "null",
        AwflValue::Bool(_) => "boolean",
        AwflValue::Integer(_) => "integer",
        AwflValue::Float(_) => "double",
        AwflValue::String(_) => "string",
        AwflValue::Bytes(_) => "bytes",
        AwflValue::List(_) => "list",
        AwflValue::Map(_) => "map",
    };
    AwflValue::String(name.to_owned())
}

/// Workflows map.get is a safe *nested* lookup, unlike strict `ops::field`.
/// Key paths must consist only of strings. Missing keys and non-map values
/// along the path yield null, even when the initial value is not a map.
pub fn map_get(value: &AwflValue, keys: &AwflValue) -> Result<AwflValue, ValueError> {
    let path: Vec<&str> = match keys {
        AwflValue::String(key) => vec![key.as_str()],
        AwflValue::List(keys) => keys
            .iter()
            .map(|item| match item {
                AwflValue::String(key) => Ok(key.as_str()),
                _ => Err(ValueError::Type("map.get keys must be strings".into())),
            })
            .collect::<Result<Vec<_>, _>>()?,
        _ => {
            return Err(ValueError::Type(
                "map.get keys must be a string or list of strings".into(),
            ));
        }
    };
    let mut current = value;
    for key in path {
        match current {
            AwflValue::Map(map) => match map.get(key) {
                Some(found) => current = found,
                None => return Ok(AwflValue::Null),
            },
            _ => return Ok(AwflValue::Null),
        }
    }
    Ok(current.clone())
}

/// Workflows map.merge is a **shallow** copy/overwrite, never a recursive
/// merge. Both inputs are required to be maps.
pub fn map_merge(a: &AwflValue, b: &AwflValue) -> Result<AwflValue, ValueError> {
    match (a, b) {
        (AwflValue::Map(left), AwflValue::Map(right)) => {
            let mut merged = left.clone();
            merged.extend(right.clone());
            Ok(AwflValue::Map(merged))
        }
        _ => Err(ValueError::Type("map.merge requires two maps".into())),
    }
}

/// Workflows list.concat(list, element) appends exactly one value. An element
/// that is itself a list stays nested: never flatten or extend it.
pub fn list_concat(list: &AwflValue, element: AwflValue) -> Result<AwflValue, ValueError> {
    match list {
        AwflValue::List(items) => {
            let mut appended = items.clone();
            appended.push(element);
            Ok(AwflValue::List(appended))
        }
        _ => Err(ValueError::Type("list.concat requires a list".into())),
    }
}

/// JSON functions operate on plain JSON, not persisted tagged continuation
/// values. Bytes are supported as JSON *input*, not JSON output: we reject
/// rather than silently changing their type/encoding.
pub fn json_decode(value: &AwflValue) -> Result<AwflValue, ValueError> {
    let bytes: &[u8] = match value {
        AwflValue::Bytes(bytes) => bytes,
        AwflValue::String(value) => value.as_bytes(),
        _ => {
            return Err(ValueError::Type(
                "json.decode requires text or bytes".into(),
            ));
        }
    };
    let plain: serde_json::Value = serde_json::from_slice(bytes)
        .map_err(|e| ValueError::Type(format!("invalid JSON: {e}")))?;
    AwflValue::from_plain_json(&plain)
}

pub fn json_encode_string(value: &AwflValue) -> Result<AwflValue, ValueError> {
    let plain = value.to_plain_json()?;
    let encoded = serde_json::to_string(&plain)
        .map_err(|e| ValueError::Type(format!("cannot encode JSON: {e}")))?;
    Ok(AwflValue::String(encoded))
}

pub fn json_encode_bytes(value: &AwflValue) -> Result<AwflValue, ValueError> {
    let plain = value.to_plain_json()?;
    let encoded = serde_json::to_vec(&plain)
        .map_err(|e| ValueError::Type(format!("cannot encode JSON: {e}")))?;
    Ok(AwflValue::Bytes(encoded))
}

fn finite(n: f64) -> Result<AwflValue, ValueError> {
    if !n.is_finite() {
        return Err(ValueError::NonFiniteFloat);
    }
    Ok(AwflValue::Float(n))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn overflow_is_explicit() {
        assert!(matches!(
            add(AwflValue::Integer(i64::MAX), AwflValue::Integer(1)),
            Err(ValueError::Overflow)
        ));
    }
    #[test]
    fn unicode_length_is_codepoint_count() {
        assert_eq!(
            length(&AwflValue::String("a🙂".into())).unwrap(),
            AwflValue::Integer(2)
        );
    }
    fn map(items: &[(&str, AwflValue)]) -> AwflValue {
        AwflValue::Map(
            items
                .iter()
                .map(|(key, value)| (key.to_string(), value.clone()))
                .collect(),
        )
    }

    #[test]
    fn map_get_is_safe_for_missing_and_nested_paths() {
        let nested = map(&[("outer", map(&[("inner", AwflValue::Integer(7))]))]);
        assert_eq!(
            map_get(&nested, &AwflValue::String("missing".into())).unwrap(),
            AwflValue::Null
        );
        assert_eq!(
            map_get(&AwflValue::Null, &AwflValue::String("missing".into())).unwrap(),
            AwflValue::Null
        );
        assert_eq!(
            map_get(
                &nested,
                &AwflValue::List(vec![
                    AwflValue::String("outer".into()),
                    AwflValue::String("inner".into())
                ])
            )
            .unwrap(),
            AwflValue::Integer(7)
        );
        assert_eq!(
            map_get(
                &nested,
                &AwflValue::List(vec![
                    AwflValue::String("outer".into()),
                    AwflValue::String("not-found".into())
                ])
            )
            .unwrap(),
            AwflValue::Null
        );
        assert!(matches!(
            map_get(&nested, &AwflValue::Integer(1)),
            Err(ValueError::Type(_))
        ));
        assert!(matches!(
            map_get(&nested, &AwflValue::List(vec![AwflValue::Integer(1)])),
            Err(ValueError::Type(_))
        ));
    }

    #[test]
    fn default_is_only_for_null_and_type_of_uses_google_spellings() {
        let fallback = AwflValue::String("fallback".into());
        assert_eq!(default_value(AwflValue::Null, fallback.clone()), fallback);
        assert_eq!(
            default_value(AwflValue::Bool(false), fallback.clone()),
            AwflValue::Bool(false)
        );
        assert_eq!(
            default_value(AwflValue::Integer(0), fallback),
            AwflValue::Integer(0)
        );
        assert_eq!(
            type_of(&AwflValue::Float(1.5)),
            AwflValue::String("double".into())
        );
        assert_eq!(type_of(&AwflValue::Null), AwflValue::String("null".into()));
        assert_eq!(
            type_of(&AwflValue::Map(Default::default())),
            AwflValue::String("map".into())
        );
    }

    #[test]
    fn map_merge_is_shallow_and_list_concat_appends_one_nested_element() {
        let original = map(&[
            ("keep", AwflValue::Integer(2)),
            ("child", map(&[("left", AwflValue::Integer(1))])),
        ]);
        let updated = map(&[("child", map(&[("right", AwflValue::Integer(3))]))]);
        assert_eq!(
            map_merge(&original, &updated).unwrap(),
            map(&[
                ("keep", AwflValue::Integer(2)),
                ("child", map(&[("right", AwflValue::Integer(3))]))
            ])
        );
        assert!(map_merge(&original, &AwflValue::Null).is_err());
        let nested = AwflValue::List(vec![AwflValue::Integer(3), AwflValue::Integer(4)]);
        let before = AwflValue::List(vec![AwflValue::Integer(1)]);
        assert_eq!(
            list_concat(&before, nested.clone()).unwrap(),
            AwflValue::List(vec![AwflValue::Integer(1), nested])
        );
        assert_eq!(before, AwflValue::List(vec![AwflValue::Integer(1)]));
        assert!(list_concat(&AwflValue::Null, AwflValue::Null).is_err());
    }

    #[test]
    fn json_functions_are_plain_json_not_tagged_continuations() {
        let value = map(&[
            ("message", AwflValue::String("é🙂".into())),
            (
                "list",
                AwflValue::List(vec![AwflValue::Bool(true), AwflValue::Null]),
            ),
        ]);
        let encoded = json_encode_string(&value).unwrap();
        assert_eq!(json_decode(&encoded).unwrap(), value);
        let bytes = json_encode_bytes(&value).unwrap();
        assert_eq!(json_decode(&bytes).unwrap(), value);
        if let AwflValue::String(text) = encoded {
            assert!(text.contains("é🙂"));
            assert!(!text.contains("\"type\":\"map\""));
        } else {
            panic!("expected text")
        }
        assert!(json_decode(&AwflValue::String("{".into())).is_err());
        assert!(json_decode(&AwflValue::Integer(42)).is_err());
        assert!(json_encode_string(&AwflValue::Bytes(vec![255])).is_err());
        assert!(json_encode_string(&AwflValue::Float(f64::INFINITY)).is_err());
    }
}
