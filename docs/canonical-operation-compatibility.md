# Native source-emission compatibility after the canonical operation migration

Measured Nov 12, 2026, from the **migrated** awfl-us/workflows PR #8
and workflow-utils PR #4, using compiler-rust main after PR #13.
This complements (and does not overwrite) the pre-migration
original-graph snapshot in `constructed-ast-compatibility.md`.

## Baseline versus migrated source

| Measurement | Original workflow source | Name-only migrated workflow source |
| --- | ---: | ---: |
| Workflow definitions | 66 | 66 |
| Rust source emitted | 2 | 3 |
| Source emission blocked | 64 | 63 |
| Class-loading errors | 0 | 0 |
| Unknown CEL/Call identifiers | Many legacy names | **0** |

**Important:** 3 emitted Rust sources is not 3 executable workflows.
The audit did not build Cargo executables, exercise external Calls,
verify live Google behavior, or test recovery and server persistence.

## First blocking issue per workflow

| Category | Definitions |
| --- | ---: |
| Trusted execution globals | 21 |
| Step/result symbol collisions | 18 |
| Parallel / ParallelFor lowering | 14 |
| Missing CEL runtime semantics | 9 |
| Raw CEL/reference | 1 |
| Rust source emitted | 3 |

These counts describe the **first** reported blocker only. Fixing one
blocker can expose later errors in the same workflow.
The three workflows emitting Rust source are: `LoadConversationHistory`, `helpers-ToolDefs`, `tools-Tasks`.

## Next native compiler/runtime milestones

1. Bind authenticated execution/project/region globals in server-owned context.
   Do not synthesize trusted values from untrusted workflow input.
2. Fix step/error/result symbol collision handling privately in the Rust compiler
   while preserving the public Scala DSL AST and existing workflow structure.
3. Add bounded, durable Parallel and ParallelFor fork/join scheduling with retry and recovery.
4. Persist nondeterministic CEL values (sys.now and uuid.generate) by occurrence
   so resumed workflows do not recompute values. Crash/replay tests are required.
5. Lower remaining safe raw expression/reference cases without broadly
   accepting arbitrary textual CEL. Verify actual Cargo build and execution after emission.

## Reproduction and provenance

The actual migrated 52 Scala workflow sources compiled successfully
against locally published versions of the released or pending packages:
DSL 0.3.5, compiler-yaml 0.3.2, workflow-utils 0.3.5, and compiler 0.3.2.
The scan was invoked with an absolute path to those compiled class files:

```bash
cd compiler-rust
AWFL_WORKFLOWS_ROOT=/path/to/migrated/workflows ./scripts/audit-real-workflows.sh target/canonical-constructed-ast.json
```

Exact revision inputs for this measured scan:

| Repository | Revision |
| --- | --- |
| `compiler-yaml` | `9100343f5199` |
| `workflow-utils` | `88e07a126dc8` |
| `compiler` | `2855d006c9a0` |
| `workflows` | `86c0c0e4be87` |
| `compiler-rust` | `dda0f6ba1e6b` |

The complete per-node JSON is intentionally kept in ignored target output
rather than committed. No operation values or credentials are exposed here.

## Detailed per-definition first error

| Workflow | Result | First blocker |
| --- | --- | --- |
| `BusinessAnalysis` | Raw CEL/reference | workflow=BusinessAnalysis node=0.0.0.except.0 step='cacheReportRead_switchRead'.case[0].result: unsupported raw CEL/reference 'cacheReportReadResult_4d4b51f7';… |
| `LoadConversationHistory` | Rust source emitted | — |
| `Summaries-All` | Trusted execution globals | node=0.3: global cloud.region requires trusted server bindings; not available in pure runner |
| `Summaries-Convo` | Step/result symbol collisions | workflow=Summaries-Convo node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62f80… |
| `Summaries-Session` | Step/result symbol collisions | workflow=Summaries-Session node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62f… |
| `Summaries-Week` | Step/result symbol collisions | workflow=Summaries-Week node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62f800… |
| `Summaries-Term` | Step/result symbol collisions | workflow=Summaries-Term node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62f800… |
| `Summaries-Backfill` | Trusted execution globals | node=0.1.each.0: global execution.id requires trusted server bindings; not available in pure runner |
| `Summaries` | Trusted execution globals | node=0.1: global execution.id requires trusted server bindings; not available in pure runner |
| `assistant-ExtractTopics-All` | Trusted execution globals | node=0.3: global cloud.region requires trusted server bindings; not available in pure runner |
| `assistant-ExtractTopics-Convo` | Step/result symbol collisions | workflow=assistant-ExtractTopics-Convo node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKa… |
| `assistant-ExtractTopics-Session` | Step/result symbol collisions | workflow=assistant-ExtractTopics-Session node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_Seg… |
| `assistant-ExtractTopics-Week` | Step/result symbol collisions | workflow=assistant-ExtractTopics-Week node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKal… |
| `assistant-ExtractTopics-Term` | Step/result symbol collisions | workflow=assistant-ExtractTopics-Term node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKal… |
| `assistant-ExtractTopics-Backfill` | Trusted execution globals | node=0.1.each.0: global execution.id requires trusted server bindings; not available in pure runner |
| `assistant-ExtractTopics` | Trusted execution globals | node=0.1: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-Crawler` | Parallel / ParallelFor lowering | workflow=codebase-Crawler node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `codebase-Crawler-tools` | Trusted execution globals | node=0.0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-Crawler-prompts` | Missing CEL runtime semantics | node=0.0.1.each.0: function sys.now lacks tested native semantics |
| `codebase-ProjectManager` | Parallel / ParallelFor lowering | workflow=codebase-ProjectManager node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret Y… |
| `codebase-ProjectManager-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-ProjectManager-prompts` | Missing CEL runtime semantics | node=0.0.1.each.0: function sys.now lacks tested native semantics |
| `codebase-awfl_cli-CliDevManager` | Parallel / ParallelFor lowering | workflow=codebase-awfl_cli-CliDevManager node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently int… |
| `codebase-awfl_cli-CliDevManager-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_cli-CliDevManager-prompts` | Missing CEL runtime semantics | node=0.0.1.each.0: function sys.now lacks tested native semantics |
| `codebase-awfl_cli-CliManager` | Parallel / ParallelFor lowering | workflow=codebase-awfl_cli-CliManager node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interp… |
| `codebase-awfl_cli-CliManager-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_cli-CliManager-prompts` | Missing CEL runtime semantics | node=0.0.1.each.0: function sys.now lacks tested native semantics |
| `codebase-awfl_web-Sessions` | Parallel / ParallelFor lowering | workflow=codebase-awfl_web-Sessions node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpre… |
| `codebase-awfl_web-Sessions-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_web-Sessions-prompts` | Step/result symbol collisions | workflow=codebase-awfl_web-Sessions-prompts node=0.0.0.0.0.1.3 step='preloadFile_block': colliding step result symbol: preloadFile_blockError |
| `codebase-awfl_web-Webmaster` | Parallel / ParallelFor lowering | workflow=codebase-awfl_web-Webmaster node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpr… |
| `codebase-awfl_web-Webmaster-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_web-Webmaster-prompts` | Missing CEL runtime semantics | node=0.0.1.each.0: function sys.now lacks tested native semantics |
| `codebase-jobs-ContextAgent` | Parallel / ParallelFor lowering | workflow=codebase-jobs-ContextAgent node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpre… |
| `codebase-jobs-ContextAgent-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-jobs-ContextAgent-prompts` | Missing CEL runtime semantics | node=0.0.1.each.0: function sys.now lacks tested native semantics |
| `BuildManager` | Parallel / ParallelFor lowering | workflow=BuildManager node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `BuildManager-tools` | Trusted execution globals | node=0.0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `BuildManager-prompts` | Step/result symbol collisions | workflow=BuildManager-prompts node=0.0.0.0.0.1.3 step='preloadFile_block': colliding step result symbol: preloadFile_blockError |
| `Executor` | Parallel / ParallelFor lowering | workflow=Executor node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `Executor-tools` | Trusted execution globals | node=0.0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `Executor-prompts` | Step/result symbol collisions | workflow=Executor-prompts node=0.0.0.0.0.1.3 step='preloadFile_block': colliding step result symbol: preloadFile_blockError |
| `PlainToCodeHook` | Step/result symbol collisions | workflow=PlainToCodeHook node=0.3.0.0 step='buildParams': colliding step result symbol: buildParamsError |
| `PlainifyWriteHook` | Step/result symbol collisions | workflow=PlainifyWriteHook node=0.3.0.0 step='buildParams': colliding step result symbol: buildParamsError |
| `Sutradhara` | Parallel / ParallelFor lowering | workflow=Sutradhara node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `Sutradhara-tools` | Trusted execution globals | node=0.0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `Sutradhara-prompts` | Step/result symbol collisions | workflow=Sutradhara-prompts node=0.0.0.0.0.1.3 step='preloadFile_block': colliding step result symbol: preloadFile_blockError |
| `ToolBuilder` | Parallel / ParallelFor lowering | workflow=ToolBuilder node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `ToolBuilder-tools` | Trusted execution globals | node=0.0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `ToolBuilder-prompts` | Step/result symbol collisions | workflow=ToolBuilder-prompts node=0.0.0.0.0.1.3 step='preloadFile_block': colliding step result symbol: preloadFile_blockError |
| `WorkflowBuilder` | Parallel / ParallelFor lowering | workflow=WorkflowBuilder node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `WorkflowBuilder-tools` | Trusted execution globals | node=0.0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `WorkflowBuilder-prompts` | Step/result symbol collisions | workflow=WorkflowBuilder-prompts node=0.0.0.0.0.1.3 step='preloadFile_block': colliding step result symbol: preloadFile_blockError |
| `context-ContextCollapser-SegKala` | Step/result symbol collisions | workflow=context-ContextCollapser-SegKala node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_Se… |
| `evals-SweBenchLiteEval` | Parallel / ParallelFor lowering | workflow=evals-SweBenchLiteEval node=0.1 step='runExperiments': ParallelFor requires native lowering before Rust emission; cannot silently interpret YAML |
| `evals-experiments-SweBenchLite` | Step/result symbol collisions | workflow=evals-experiments-SweBenchLite node=0.1.try.0.try.1.0.0 step='buildParams': colliding step result symbol: buildParamsError |
| `helpers-Chain` | Trusted execution globals | node=0.1.each.0: global execution.id requires trusted server bindings; not available in pure runner |
| `helpers-Tasks` | Missing CEL runtime semantics | node=0.2.1: function sys.now lacks tested native semantics |
| `helpers-ToolDefs` | Rust source emitted | — |
| `helpers-ToolDispatcher` | Missing CEL runtime semantics | node=0.0.try.2.each.1.2: function sys.now lacks tested native semantics |
| `helpers-links-SaveReflection` | Trusted execution globals | node=0.0.case0.0: global execution.id requires trusted server bindings; not available in pure runner |
| `tools-Agent` | Trusted execution globals | node=0.0: global cloud.region requires trusted server bindings; not available in pure runner |
| `tools-CliTools` | Missing CEL runtime semantics | node=0.0.try.2.try.2.0: function sys.now lacks tested native semantics |
| `tools-SpawnCrawlers` | Parallel / ParallelFor lowering | workflow=tools-SpawnCrawlers node=0.0 step='spawn': ParallelFor requires native lowering before Rust emission; cannot silently interpret YAML |
| `tools-Tasks` | Rust source emitted | — |
