//! Small compiled-code helpers. Generated Rust calls these directly; this is not
//! a workflow AST evaluator or a mechanism for making external side effects.
use crate::{AwflValue, ValueError};
use base64::Engine;
use chrono::{DateTime, SecondsFormat, Utc};
use chrono_tz::Tz;

/// Workflows time.format: Unix seconds (integer or float) to an ISO 8601
/// timestamp with exactly six fractional digits and a tzdb zone. The UTC
/// spelling uses Z; zone offsets use +/-HH:MM. Convert using integer
/// microseconds so negative fractional epochs work across second boundaries.
pub fn time_format(
    seconds: &AwflValue,
    timezone: Option<&AwflValue>,
) -> Result<AwflValue, ValueError> {
    let (epoch_seconds, nanos) = match seconds {
        AwflValue::Integer(s) => (*s, 0),
        AwflValue::Float(s) if s.is_finite() => {
            let us = (*s * 1_000_000.0).round();
            if !us.is_finite() || us < i64::MIN as f64 || us >= i64::MAX as f64 {
                return Err(ValueError::Type(
                    "time.format timestamp out of range".into(),
                ));
            }
            let us = us as i64;
            (
                us.div_euclid(1_000_000),
                (us.rem_euclid(1_000_000) as u32) * 1000,
            )
        }
        _ => {
            return Err(ValueError::Type(
                "time.format seconds must be finite integer or float".into(),
            ));
        }
    };
    let date = DateTime::<Utc>::from_timestamp(epoch_seconds, nanos)
        .ok_or_else(|| ValueError::Type("time.format timestamp out of range".into()))?;
    let zone_name = match timezone {
        None => "UTC",
        Some(AwflValue::String(s)) if s.is_empty() => "UTC",
        Some(AwflValue::String(s)) => s.as_str(),
        Some(_) => {
            return Err(ValueError::Type(
                "time.format timezone must be string".into(),
            ));
        }
    };
    let zone: Tz = zone_name
        .parse()
        .map_err(|_| ValueError::Type(format!("time.format unsupported timezone '{zone_name}'")))?;
    Ok(AwflValue::String(
        date.with_timezone(&zone)
            .to_rfc3339_opts(SecondsFormat::Micros, true),
    ))
}

/// Current Unix epoch seconds, as a float compatible with Google Workflows sys.now.
/// Evaluated when encountered; existing step slots/continuations own persistence.
pub fn time_now() -> Result<AwflValue, ValueError> {
    let elapsed = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| ValueError::Type("system clock precedes Unix epoch".into()))?;
    Ok(AwflValue::Float(elapsed.as_secs_f64()))
}

/// Cryptographically random RFC 4122 version-4 UUID.
pub fn uuid_generate() -> AwflValue {
    AwflValue::String(uuid::Uuid::new_v4().to_string())
}

/// Only these Google Workflows built-ins may be supplied by the authenticated
/// server. User workflow inputs and the executable's process environment are
/// never sources for these values.
fn trusted_key(name: &str) -> Option<&'static str> {
    match name {
        "workflow.id" | "GOOGLE_CLOUD_WORKFLOW_ID" => Some("GOOGLE_CLOUD_WORKFLOW_ID"),
        "execution.id" | "GOOGLE_CLOUD_WORKFLOW_EXECUTION_ID" => {
            Some("GOOGLE_CLOUD_WORKFLOW_EXECUTION_ID")
        }
        "cloud.project_id" | "GOOGLE_CLOUD_PROJECT_ID" => Some("GOOGLE_CLOUD_PROJECT_ID"),
        "cloud.region" | "GOOGLE_CLOUD_LOCATION" => Some("GOOGLE_CLOUD_LOCATION"),
        _ => None,
    }
}

/// Resolve a compiler-recognized global from the trusted execution envelope.
/// Absence is an error; in particular, NEVER fall back to input.env or std::env.
pub fn trusted_global(
    globals: &std::collections::BTreeMap<String, String>,
    name: &str,
) -> Result<AwflValue, ValueError> {
    let key = trusted_key(name)
        .ok_or_else(|| ValueError::Type(format!("unsupported native global {name}")))?;
    globals
        .get(key)
        .cloned()
        .map(AwflValue::String)
        .ok_or_else(|| ValueError::MissingField(format!("trusted global {key}")))
}

/// Native sys.get_env supports the same trusted built-ins as DSL globals.
/// Arbitrary environment variables are never readable from the subprocess.
/// An explicit string fallback works for absent or unsupported keys.
pub fn environment_get(
    globals: &std::collections::BTreeMap<String, String>,
    name: &AwflValue,
    fallback: Option<&AwflValue>,
) -> Result<AwflValue, ValueError> {
    let AwflValue::String(name) = name else {
        return Err(ValueError::Type("sys.get_env name must be a string".into()));
    };
    if fallback.is_some_and(|value| !matches!(value, AwflValue::String(_))) {
        return Err(ValueError::Type(
            "sys.get_env default must be a string".into(),
        ));
    }
    if let Some(value) = trusted_key(name).and_then(|key| globals.get(key)) {
        return Ok(AwflValue::String(value.clone()));
    }
    match fallback {
        Some(default) => Ok(default.clone()),
        None => Err(ValueError::MissingField(format!(
            "trusted environment variable {name}"
        ))),
    }
}

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

/// Google Workflows string(number | boolean), not general JSON conversion.
pub fn to_string(value: &AwflValue) -> Result<AwflValue, ValueError> {
    let converted = match value {
        AwflValue::Integer(n) => n.to_string(),
        AwflValue::Float(n) if n.is_finite() => n.to_string(),
        AwflValue::Float(_) => return Err(ValueError::NonFiniteFloat),
        AwflValue::Bool(value) => value.to_string(),
        _ => {
            return Err(ValueError::Type(
                "string() requires an integer, double or boolean".into(),
            ));
        }
    };
    Ok(AwflValue::String(converted))
}

/// Check float boundaries before casting: Rust's f64-to-i64 cast saturates
/// and would otherwise turn overflow into a valid result.
pub fn to_int(value: &AwflValue) -> Result<AwflValue, ValueError> {
    let converted = match value {
        AwflValue::String(text) => text.parse::<i64>().map_err(|_| {
            ValueError::Type("int() requires a signed decimal integer string".into())
        })?,
        AwflValue::Float(n) if n.is_finite() => {
            let truncated = n.trunc();
            const TWO_POW_63: f64 = 9_223_372_036_854_775_808.0;
            if !(-TWO_POW_63..TWO_POW_63).contains(&truncated) {
                return Err(ValueError::Overflow);
            }
            truncated as i64
        }
        AwflValue::Float(_) => return Err(ValueError::NonFiniteFloat),
        _ => return Err(ValueError::Type("int() requires a string or double".into())),
    };
    Ok(AwflValue::Integer(converted))
}

/// Google text.to_lower operates on Unicode, not ASCII byte sequences.
/// Percent-encode UTF-8 bytes as in Google Workflows text.url_encode.
/// Only ASCII letters/digits and '.', '-', '_' remain literal; notably '~'
/// and spaces are escaped, and spaces are never converted into '+'.
pub fn text_url_encode(value: &AwflValue) -> Result<AwflValue, ValueError> {
    let AwflValue::String(source) = value else {
        return Err(ValueError::Type(
            "text.url_encode source must be a string".into(),
        ));
    };
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut encoded = String::with_capacity(source.len());
    for byte in source.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'_') {
            encoded.push(char::from(byte));
        } else {
            encoded.push('%');
            encoded.push(char::from(HEX[usize::from(byte >> 4)]));
            encoded.push(char::from(HEX[usize::from(byte & 0x0f)]));
        }
    }
    Ok(AwflValue::String(encoded))
}

pub fn text_lowercase(value: &AwflValue) -> Result<AwflValue, ValueError> {
    match value {
        AwflValue::String(text) => Ok(AwflValue::String(text.to_lowercase())),
        _ => Err(ValueError::Type("text.to_lower requires a string".into())),
    }
}

/// Workflows text.substring uses zero-based Unicode character offsets,
/// inclusive at start and exclusive at end. Negative indices count back
/// from the end, and out-of-range indices clamp rather than failing.
pub fn text_substring(
    source: &AwflValue,
    start: &AwflValue,
    end: &AwflValue,
) -> Result<AwflValue, ValueError> {
    let (AwflValue::String(source), AwflValue::Integer(start), AwflValue::Integer(end)) =
        (source, start, end)
    else {
        return Err(ValueError::Type(
            "text.substring requires string, integer, integer".into(),
        ));
    };
    let len = i64::try_from(source.chars().count()).map_err(|_| ValueError::Overflow)?;
    let clamp = |index: i64| -> usize {
        let offset = if index < 0 {
            len.saturating_add(index)
        } else {
            index
        };
        offset.clamp(0, len) as usize
    };
    let from = clamp(*start);
    let to = clamp(*end);
    if from >= to {
        return Ok(AwflValue::String(String::new()));
    }
    Ok(AwflValue::String(
        source.chars().skip(from).take(to - from).collect(),
    ))
}

/// Limit initial charset support to the two encodings used by native fixtures.
/// Unknown IANA names must fail closed, not silently use UTF-8.
fn charset(value: Option<&AwflValue>) -> Result<&'static str, ValueError> {
    let text = match value {
        None => return Ok("UTF-8"),
        Some(AwflValue::String(text)) => text,
        Some(_) => return Err(ValueError::Type("charset must be a string".into())),
    };
    match text.to_ascii_uppercase().as_str() {
        "UTF-8" | "UTF8" => Ok("UTF-8"),
        "US-ASCII" | "ASCII" => Ok("US-ASCII"),
        _ => Err(ValueError::Type(format!("unsupported charset '{text}'"))),
    }
}

pub fn text_encode(
    value: &AwflValue,
    encoding: Option<&AwflValue>,
) -> Result<AwflValue, ValueError> {
    let text = match value {
        AwflValue::String(text) => text,
        _ => return Err(ValueError::Type("text.encode requires string".into())),
    };
    let bytes = match charset(encoding)? {
        "UTF-8" => text.as_bytes().to_vec(),
        "US-ASCII" if text.is_ascii() => text.as_bytes().to_vec(),
        "US-ASCII" => {
            return Err(ValueError::Type(
                "text is not representable in US-ASCII".into(),
            ));
        }
        _ => unreachable!("validated charset"),
    };
    Ok(AwflValue::Bytes(bytes))
}

/// Workflows text.decode replaces invalid bytes with U+FFFD.
pub fn text_decode(
    value: &AwflValue,
    encoding: Option<&AwflValue>,
) -> Result<AwflValue, ValueError> {
    let bytes = match value {
        AwflValue::Bytes(bytes) => bytes,
        _ => return Err(ValueError::Type("text.decode requires bytes".into())),
    };
    let decoded = match charset(encoding)? {
        "UTF-8" => String::from_utf8_lossy(bytes).into_owned(),
        "US-ASCII" => bytes
            .iter()
            .map(|byte| {
                if byte.is_ascii() {
                    char::from(*byte)
                } else {
                    '\u{FFFD}'
                }
            })
            .collect(),
        _ => unreachable!("validated charset"),
    };
    Ok(AwflValue::String(decoded))
}

/// RFC 4648 standard Base64 alphabet, optionally without trailing padding.
pub fn base64_encode(
    value: &AwflValue,
    padding: Option<&AwflValue>,
) -> Result<AwflValue, ValueError> {
    let bytes = match value {
        AwflValue::Bytes(bytes) => bytes,
        _ => return Err(ValueError::Type("base64.encode requires bytes".into())),
    };
    let with_padding = match padding {
        None | Some(AwflValue::Bool(true)) => true,
        Some(AwflValue::Bool(false)) => false,
        Some(_) => {
            return Err(ValueError::Type(
                "base64.encode padding must be boolean".into(),
            ));
        }
    };
    let text = if with_padding {
        base64::engine::general_purpose::STANDARD.encode(bytes)
    } else {
        base64::engine::general_purpose::STANDARD_NO_PAD.encode(bytes)
    };
    Ok(AwflValue::String(text))
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

/// CEL/Google Workflows `a in b`: value membership in a list, or a string
/// key's existence in a map. Do not interpret substring containment.
pub fn contains(item: &AwflValue, collection: &AwflValue) -> Result<AwflValue, ValueError> {
    match collection {
        AwflValue::List(values) => Ok(AwflValue::Bool(values.contains(item))),
        AwflValue::Map(values) => match item {
            AwflValue::String(key) => Ok(AwflValue::Bool(values.contains_key(key))),
            _ => Err(ValueError::Type(
                "map membership requires a string key".into(),
            )),
        },
        _ => Err(ValueError::Type("membership requires list or map".into())),
    }
}

/// CEL `//` is floor division, *not* Rust's truncating signed division.
/// Return errors on zero and i64::MIN/-1 rather than panic or wrap.
pub fn floor_divide(left: &AwflValue, right: &AwflValue) -> Result<AwflValue, ValueError> {
    match (left, right) {
        (AwflValue::Integer(a), AwflValue::Integer(b)) => {
            if *b == 0 {
                return Err(ValueError::Type("division by zero".into()));
            }
            let quotient = a.checked_div(*b).ok_or(ValueError::Overflow)?;
            let remainder = a.checked_rem(*b).ok_or(ValueError::Overflow)?;
            let result = if remainder != 0 && (remainder < 0) != (*b < 0) {
                quotient.checked_sub(1).ok_or(ValueError::Overflow)?
            } else {
                quotient
            };
            Ok(AwflValue::Integer(result))
        }
        _ => Err(ValueError::Type(
            "integer floor division requires two integers".into(),
        )),
    }
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
    fn url_encoding_matches_workflows_byte_semantics() {
        assert_eq!(
            text_url_encode(&AwflValue::String("a/b/c?item=d+e f".into())).unwrap(),
            AwflValue::String("a%2Fb%2Fc%3Fitem%3Dd%2Be%20f".into())
        );
        assert_eq!(
            text_url_encode(&AwflValue::String("a~_-.é🙂".into())).unwrap(),
            AwflValue::String("a%7E_-.%C3%A9%F0%9F%99%82".into())
        );
        assert!(text_url_encode(&AwflValue::Integer(9)).is_err());
    }

    #[test]
    fn trusted_globals_are_separate_from_arbitrary_environment() {
        let globals = std::collections::BTreeMap::from([
            ("GOOGLE_CLOUD_WORKFLOW_ID".into(), "example".into()),
            (
                "GOOGLE_CLOUD_WORKFLOW_EXECUTION_ID".into(),
                "native_1234".into(),
            ),
            ("GOOGLE_CLOUD_PROJECT_ID".into(), "awfl-us".into()),
            ("GOOGLE_CLOUD_LOCATION".into(), "us-central1".into()),
        ]);
        assert_eq!(
            trusted_global(&globals, "execution.id").unwrap(),
            AwflValue::String("native_1234".into())
        );
        assert_eq!(
            trusted_global(&globals, "cloud.project_id").unwrap(),
            AwflValue::String("awfl-us".into())
        );
        assert_eq!(
            environment_get(
                &globals,
                &AwflValue::String("GOOGLE_CLOUD_LOCATION".into()),
                None
            )
            .unwrap(),
            AwflValue::String("us-central1".into())
        );
        assert_eq!(
            environment_get(
                &globals,
                &AwflValue::String("UNLISTED_ENV".into()),
                Some(&AwflValue::String("safe-default".into()))
            )
            .unwrap(),
            AwflValue::String("safe-default".into())
        );
        assert!(environment_get(&globals, &AwflValue::String("PATH".into()), None).is_err());
        assert!(trusted_global(&std::collections::BTreeMap::new(), "execution.id").is_err());
        assert!(environment_get(&globals, &AwflValue::Integer(1), None).is_err());
    }

    #[test]
    fn clock_and_uuid_are_native_independent_values() {
        for _ in 0..2 {
            match time_now().unwrap() {
                AwflValue::Float(seconds) => assert!(seconds.is_finite() && seconds > 0.0),
                other => panic!("expected float epoch seconds, got {other:?}"),
            }
        }
        let get_id = || match uuid_generate() {
            AwflValue::String(id) => id,
            other => panic!("expected UUID string, got {other:?}"),
        };
        let first = get_id();
        let second = get_id();
        assert_ne!(first, second);
        let parsed = uuid::Uuid::parse_str(&first).expect("canonical UUID");
        assert_eq!(parsed.get_version_num(), 4);
        assert_eq!(parsed.to_string(), first);
    }
    #[test]
    fn overflow_is_explicit() {
        assert!(matches!(
            add(AwflValue::Integer(i64::MAX), AwflValue::Integer(1)),
            Err(ValueError::Overflow)
        ));
    }
    #[test]
    fn conversions_check_types_bounds_and_unicode() {
        assert_eq!(
            to_string(&AwflValue::Integer(-13)).unwrap(),
            AwflValue::String("-13".into())
        );
        assert_eq!(
            to_string(&AwflValue::Bool(false)).unwrap(),
            AwflValue::String("false".into())
        );
        assert_eq!(
            to_string(&AwflValue::Float(2.5)).unwrap(),
            AwflValue::String("2.5".into())
        );
        assert!(matches!(
            to_string(&AwflValue::Null),
            Err(ValueError::Type(_))
        ));
        assert!(matches!(
            to_string(&AwflValue::String("1".into())),
            Err(ValueError::Type(_))
        ));
        assert_eq!(
            to_int(&AwflValue::String("-102".into())).unwrap(),
            AwflValue::Integer(-102)
        );
        assert_eq!(
            to_int(&AwflValue::Float(-2.9)).unwrap(),
            AwflValue::Integer(-2)
        );
        assert_eq!(
            to_int(&AwflValue::Float(2.9)).unwrap(),
            AwflValue::Integer(2)
        );
        assert_eq!(
            to_int(&AwflValue::Float(i64::MIN as f64)).unwrap(),
            AwflValue::Integer(i64::MIN)
        );
        assert!(matches!(
            to_int(&AwflValue::Float(9_223_372_036_854_775_808.0)),
            Err(ValueError::Overflow)
        ));
        assert!(to_int(&AwflValue::String("9223372036854775808".into())).is_err());
        assert!(to_int(&AwflValue::String("1.5".into())).is_err());
        assert!(to_int(&AwflValue::Bool(true)).is_err());
        assert_eq!(
            text_lowercase(&AwflValue::String("ÉCOLE İSTANBUL".into())).unwrap(),
            AwflValue::String("école i̇stanbul".into())
        );
        assert!(text_lowercase(&AwflValue::Integer(1)).is_err());
    }
    #[test]
    fn time_format_uses_microseconds_and_iana_timezone_dst() {
        let format = |value: AwflValue, zone: Option<&str>| -> String {
            let tz = zone.map(|s| AwflValue::String(s.into()));
            match time_format(&value, tz.as_ref()).unwrap() {
                AwflValue::String(text) => text,
                _ => panic!("expected text timestamp"),
            }
        };
        assert_eq!(
            format(AwflValue::Integer(1_720_788_633), None),
            "2024-07-12T12:50:33.000000Z"
        );
        assert_eq!(
            format(AwflValue::Integer(1_720_788_633), Some("GMT")),
            "2024-07-12T12:50:33.000000Z"
        );
        assert_eq!(
            format(AwflValue::Integer(1_720_788_633), Some("Europe/Paris")),
            "2024-07-12T14:50:33.000000+02:00"
        );
        assert_eq!(
            format(AwflValue::Integer(1_720_788_633), Some("America/New_York")),
            "2024-07-12T08:50:33.000000-04:00"
        );
        assert_eq!(
            format(AwflValue::Integer(0), Some("America/New_York")),
            "1969-12-31T19:00:00.000000-05:00"
        );
        assert_eq!(
            format(AwflValue::Float(-0.25), None),
            "1969-12-31T23:59:59.750000Z"
        );
        assert_eq!(
            format(AwflValue::Float(0.615087), None),
            "1970-01-01T00:00:00.615087Z"
        );
        assert_eq!(
            format(AwflValue::Integer(0), Some("")),
            "1970-01-01T00:00:00.000000Z"
        );
        for value in [
            AwflValue::Bool(true),
            AwflValue::String("42".into()),
            AwflValue::Float(f64::NAN),
            AwflValue::Float(f64::INFINITY),
            AwflValue::Float(1.0e40),
        ] {
            assert!(time_format(&value, None).is_err());
        }
        assert!(time_format(&AwflValue::Integer(0), Some(&AwflValue::Integer(3))).is_err());
        assert!(
            time_format(
                &AwflValue::Integer(0),
                Some(&AwflValue::String("Bogus/Timezone".into()))
            )
            .is_err()
        );
    }

    #[test]
    fn text_substring_clamps_negative_bounds_and_preserves_unicode() {
        let slice = |source: &str, start: i64, end: i64| {
            text_substring(
                &AwflValue::String(source.into()),
                &AwflValue::Integer(start),
                &AwflValue::Integer(end),
            )
            .unwrap()
        };
        assert_eq!(slice("hello", 2, 4), AwflValue::String("ll".into()));
        assert_eq!(slice("hello", -10, 10), AwflValue::String("hello".into()));
        assert_eq!(slice("hello", -3, -1), AwflValue::String("ll".into()));
        assert_eq!(slice("a🙂éZ", 1, 3), AwflValue::String("🙂é".into()));
        assert_eq!(slice("hello", 4, 2), AwflValue::String(String::new()));
        assert_eq!(
            slice("hello", i64::MIN, i64::MAX),
            AwflValue::String("hello".into())
        );
        assert_eq!(slice("", -1, 10), AwflValue::String(String::new()));
        assert!(
            text_substring(
                &AwflValue::Integer(1),
                &AwflValue::Integer(0),
                &AwflValue::Integer(1)
            )
            .is_err()
        );
        assert!(
            text_substring(
                &AwflValue::String("hello".into()),
                &AwflValue::Float(1.0),
                &AwflValue::Integer(3)
            )
            .is_err()
        );
        assert!(
            text_substring(
                &AwflValue::String("hello".into()),
                &AwflValue::Integer(0),
                &AwflValue::String("3".into())
            )
            .is_err()
        );
    }

    #[test]
    fn utf8_ascii_and_base64_are_explicit_and_replayable() {
        let utf8 = text_encode(&AwflValue::String("Hello 🙂".into()), None).unwrap();
        assert_eq!(utf8, AwflValue::Bytes("Hello 🙂".as_bytes().to_vec()));
        assert_eq!(
            text_decode(&utf8, None).unwrap(),
            AwflValue::String("Hello 🙂".into())
        );
        assert_eq!(
            base64_encode(&utf8, None).unwrap(),
            AwflValue::String("SGVsbG8g8J+Zgg==".into())
        );
        assert_eq!(
            base64_encode(&utf8, Some(&AwflValue::Bool(false))).unwrap(),
            AwflValue::String("SGVsbG8g8J+Zgg".into())
        );
        assert_eq!(
            text_decode(&AwflValue::Bytes(vec![0x66, 0x80, 0x6f]), None).unwrap(),
            AwflValue::String("f\u{FFFD}o".into())
        );
        let ascii = AwflValue::String("A-Z".into());
        let ascii_charset = AwflValue::String("us-ascii".into());
        assert_eq!(
            text_encode(&ascii, Some(&ascii_charset)).unwrap(),
            AwflValue::Bytes(b"A-Z".to_vec())
        );
        assert_eq!(
            text_decode(&AwflValue::Bytes(vec![65, 255]), Some(&ascii_charset)).unwrap(),
            AwflValue::String("A\u{FFFD}".into())
        );
        assert!(text_encode(&AwflValue::String("é".into()), Some(&ascii_charset)).is_err());
        assert!(text_encode(&ascii, Some(&AwflValue::String("latin-2".into()))).is_err());
        assert!(text_decode(&ascii, None).is_err());
        assert!(base64_encode(&ascii, None).is_err());
        assert!(base64_encode(&utf8, Some(&AwflValue::String("false".into()))).is_err());
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
    #[test]
    fn floor_division_rounds_toward_negative_infinity_and_checks_errors() {
        let div = |a, b| floor_divide(&AwflValue::Integer(a), &AwflValue::Integer(b));
        assert_eq!(div(5, 2).unwrap(), AwflValue::Integer(2));
        assert_eq!(div(-5, 2).unwrap(), AwflValue::Integer(-3));
        assert_eq!(div(5, -2).unwrap(), AwflValue::Integer(-3));
        assert_eq!(div(-5, -2).unwrap(), AwflValue::Integer(2));
        assert_eq!(div(0, -2).unwrap(), AwflValue::Integer(0));
        assert!(
            div(1, 0)
                .unwrap_err()
                .to_string()
                .contains("division by zero")
        );
        assert!(matches!(div(i64::MIN, -1), Err(ValueError::Overflow)));
        assert!(floor_divide(&AwflValue::Float(2.0), &AwflValue::Integer(2)).is_err());
    }

    #[test]
    fn membership_checks_list_elements_and_map_keys_without_string_matching() {
        let list = AwflValue::List(vec![
            AwflValue::Integer(1),
            AwflValue::Integer(2),
            AwflValue::List(vec![AwflValue::Integer(3)]),
        ]);
        assert_eq!(
            contains(&AwflValue::Integer(2), &list).unwrap(),
            AwflValue::Bool(true)
        );
        assert_eq!(
            contains(&AwflValue::Integer(4), &list).unwrap(),
            AwflValue::Bool(false)
        );
        assert_eq!(
            contains(&AwflValue::List(vec![AwflValue::Integer(3)]), &list).unwrap(),
            AwflValue::Bool(true)
        );
        let map = AwflValue::Map([("key".into(), AwflValue::Null)].into());
        assert_eq!(
            contains(&AwflValue::String("key".into()), &map).unwrap(),
            AwflValue::Bool(true)
        );
        assert_eq!(
            contains(&AwflValue::String("missing".into()), &map).unwrap(),
            AwflValue::Bool(false)
        );
        assert!(contains(&AwflValue::Integer(1), &map).is_err());
        assert!(
            contains(
                &AwflValue::String("abc".into()),
                &AwflValue::String("abcdef".into())
            )
            .is_err()
        );
    }
}
