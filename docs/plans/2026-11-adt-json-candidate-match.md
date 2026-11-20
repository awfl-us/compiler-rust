# Native Rust conformance for DSL `wip-old` ADT Match

The Scala source fixtures reproduce original active `workflow-utils/wip-old` patterns, not an invented API:
- `Locks.buildSessionKey`: `case SegKala(sessionId, _, _)`, `SessionKala(sessionId, _)`, and global fallback for week/term
- `TopicContextYoj.encodeKalaVal`: typed `case seg: SegKala` projection
- `Prakriya.read`: contains only a **commented** candidate `Match` implementation, not an active native usage site.

The original `dsl/wip-old` algorithm constructs candidate case-class JSON values using field resolvers (protecting missing fields with `Try`) and selects the first whose `json.encode_to_string` equals the input. `IsAdt` is just an empty marker. Nothing in the current Rust compiler automatically encodes an ADT subtype discriminator.

## Running the isolated native gate

The compiler-rust project currently depends on published `dsl:0.3.5`, which lacks `Match`; these test files are staged on `test/native-json-candidate-match` until a versioned DSL artifact is published. No current `build.sbt` was changed. First, on `dsl/feat/reconcile-adt-match`:

```bash
sbt -batch test publishLocal
```

Then, from `compiler-rust/frontend`:

```bash
sbt -batch 'set dependencyOverrides += "us.awfl" %% "dsl" % "0.1.0-SNAPSHOT"' \
  'Test/runMain us.awfl.compiler.rust.AdtMatchNativeSmoke' \
  'Test/runMain us.awfl.compiler.rust.AdtLiteralNativeSmoke'
```

Finally, from `compiler-rust`:

```bash
python3 scripts/test_adt_match.py
```

This builds the generated **real Rust binaries** and checks 31 live native assertions: 8 positive Kala cases, 8 malformed-input failures (rather than the original silent `{}`), 3 source-order/wildcard cases, 3 effect bypasses, 1 selected suspension, 1 injected continuation/resume, and 7 literal field-value cases. No actual HTTP call is made.

## Release safety and remaining gaps

- Distinct Kala record field sets work without a subtype discriminator. Raw primitive literals such as `RoleCreated("admin", id)` versus `RoleCreated("user", id)` are preserved in the JSON candidates.
- If separate variants serialize to identical full JSON objects, the first case can win incorrectly. The DSL does **not** currently enforce disjoint subtypes or add a serialized discriminator. Address this representation requirement before advertising general ADT matching.
- Guards and arbitrary nested patterns are out of scope. A literal plus a bound field returned **directly** from the RHS compiles, but using that binder inside a complex RHS `.cel` expression has an outstanding Scala 3 quote-owner diagnostic. Do not claim full Scala pattern parity yet.
- The native workflow execution target remains PostgreSQL memory-mode Rust. Durable journaling and Google Workflows are not introduced.
