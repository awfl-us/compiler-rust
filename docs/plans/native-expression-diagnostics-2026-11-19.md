# Native expression diagnostics — November 19, 2026

## Motivation

Native Rust failures in detached `Summaries-Week` previously reported
`integer floor division requires two integers`, without identifying the
DSL expression.

## Implemented

- `ExpressionEmitter` attaches the existing `StateMachineEmitter.sourceNode`
  and operation name to fallible CEL expressions, including field/index,
  arithmetic/comparison, boolean checks, and stdlib functions.
- Failed binary operations additionally include **operand types only**, never
  input contents. Capture type labels before consuming values, without cloning
  operands on the successful path.
- `ValueError::Context` wraps the original `ValueError`, retaining native
  DSL Try/Catch recognition via `downcast_ref::<ValueError>()`.
- Example: `node=0.0 operation=divide_int left_type=Float right_type=Integer:
  integer floor division requires two integers`.
- No change to the integer division semantics or runtime handling of effects,
  persistence, and callbacks. Uncaught native process stderr currently displays
  Rust's Debug representation, which also includes the node and type details.

## Verification

- `cargo test --manifest-path runtime/Cargo.toml`.
- `sbt -batch 'Test / runMain us.awfl.compiler.rust.CelOperatorsSmoke'
  'Test / runMain us.awfl.compiler.rust.TryRaiseSmoke'` in `frontend/`.
- Build the `fixtures/native-try-raise/runtime-divide` and `catch-divide`
  Cargo packages and run `python3 scripts/check-expression-diagnostics.py`.
  The script checks a real uncaught type error, caught type error, valid
  integer division, and absence of operand values.
- Full ProjectManager native closure compile is an additional compatibility gate.

## Further work

Carry optional Scala source file/line metadata through normalized IR, expose
structured diagnostic fields in execution records, and improve uncaught native
process error formatting. Current scope is **DSL node + operation + operand
types**, not exact source lines or tracing successful steps.
