# Scala 3 native Rust compiler frontend

This sbt module consumes **constructed AWFL DSL workflows** and emits Rust source
directly. The existing `awfl-us/compiler` module remains responsible for Scala
workflow-builder loading and input/output schema generation.

The intended integration boundary is
`NativeCompiler.compile(workflows, baseName, outputDir, runtimeDir)`.

## Development dependency

Until the operation registry in [dsl PR #2](https://github.com/awfl-us/dsl/pull/2)
is released, this module depends on `us.awfl:dsl_3:0.1.0-SNAPSHOT`, published
locally from that commit. Run `sbt publishLocal` in a matching DSL checkout
before building this module. The release build should use a versioned published
DSL artifact; don't publish this snapshot to production.

## Test real Scala → Rust code generation

```bash
cd ../dsl
sbt -batch publishLocal
cd ../compiler-rust/frontend
sbt -batch 'Test / runMain us.awfl.compiler.rust.FrontendSmoke'
cd ..
cargo build --manifest-path fixtures/native-smoke/Cargo.toml
printf '5\n' | fixtures/native-smoke/target/debug/awfl-generated-workflow
```

Expected JSON: `{"name":"native","score":12,"valid":true}`

**Current scope:** simple pure workflows, typed literals, input references and
selected arithmetic/boolean functions and operators. `Call` steps are normalized
to typed effects but Rust codegen refuses them until a durable server checkpoint
and effect replay protocol is implemented. Complex control flow fails at
compilation with source context. These are intentional safety gates.
