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
}
