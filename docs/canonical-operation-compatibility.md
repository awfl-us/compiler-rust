# Native source-emission compatibility after the canonical operation migration

> **Historical migration snapshot, not the current compiler result.**
> The **4/66** migrated-source figure below predates later compiler work.
> After merged [compiler-rust #18](https://github.com/awfl-us/compiler-rust/pull/18)
> (`a285f66`) and merged
> [workflow-utils #8](https://github.com/awfl-us/workflow-utils/pull/8),
> the aligned November 12 `workflows/main` audit is **65/66 source emissions,
> 1 blocked (`initEnvResult_*` lexical scope), 0 class-loading errors**.
> Original JSON schema placeholders are string templates, not runtime
> variable references. See [the current roadmap](current-state-and-roadmap.md)
> for the current state and next steps. Source emission remains distinct from
> compiled binaries and Google/runtime equivalence.

**Measured:** November 12, 2026, from merged workflows PR #8
and workflow-utils PR #4, using compiler-rust main **after PR #14**.
All results come from instantiated Scala DSL graphs, not a search of source strings.
The original-source baseline is retained separately in constructed-ast-compatibility.md.

## Original baseline versus current migrated source

| Measurement | Original source | Migrated source (historical compiler snapshot) |
| --- | ---: | ---: |
| Workflow definitions | 66 | 66 |
| Rust source emitted | 2 | 4 |
| Source emission blocked | 64 | 62 |
| Class-loading errors | 0 | 0 |
| Unknown CEL/Call identifiers | Legacy literal names | **0** |

**This is source emission only.** It does not mean that 4 of 66
workflows execute correctly in a compiled Rust binary or support live effects.
The scan did not execute Cargo binaries, dispatch external operations,
verify Google Workflows behavior, or exercise the server checkpoint path.

Four definitions emitted Rust: LoadConversationHistory, helpers-Tasks,
helpers-ToolDefs, tools-Tasks. Native time.now() and uuid.generate()
are ordinary compiled Rust expressions since compiler-rust PR #14;
within-step evaluations may differ. Existing materialized step-result
slots and server checkpoints provide persistence, without special
clock/UUID journaling.

## First blocker per workflow

| First blocker | Definitions |
| --- | ---: |
| Trusted execution globals | 29 |
| Step/result symbol collisions | 18 |
| Parallel / ParallelFor lowering | 14 |
| Raw CEL/reference | 1 |
| Rust source emitted | 4 |

These are **first blocking issues**, not an exhaustive list of all
unsupported features in each workflow. Removing one blocker can reveal
another. Zero unknown operation IDs does not imply all calls or CEL
overloads have runtime implementations.

## Recommended native milestones

1. Fix compiler-local step/error/result symbol scoping collisions.
   Preserve the original DSL AST and workflow structures.
2. Provide server-authenticated workflow/execution/project/region values
   as trusted runtime bindings; never derive these from untrusted inputs.
3. Add bounded durable Parallel and ParallelFor fork/join scheduling
   with isolated branch state, retry, cancellation and recovery tests.
4. Support the remaining raw reference safely, without adding a
   broad runtime CEL text interpreter.
5. Compile and **run** representative emitted workflows through Cargo,
   then verify actual server Call dispatch, checkpoint/crash/resume and
   Google differential behavior. These are distinct from source emission.

## Reproduction and revision provenance

The prior migration/build validated 52 Scala source files using
DSL 0.3.5, compiler-yaml 0.3.2, workflow-utils 0.3.5,
and compiler 0.3.2. This rerun used the resulting Scala class files
and the latest compiler-rust source after PR #14.

    AWFL_WORKFLOWS_ROOT=/path/to/compiled-migrated-workflows ./scripts/audit-real-workflows.sh target/canonical-constructed-ast-after-clock.json

| Repo | Revision used for measured scan |
| --- | --- |
| compiler-rust | d380351bddb6 (merged PR #14, plus this audit documentation) |
| workflows | 86c0c0e4be87 (merged as 486879c, PR #8) |
| workflow-utils | 88e07a126dc8 (PR #4) |
| compiler-yaml | 9100343f5199 (PR #2) |
| compiler | 2855d006c9a0 |

The four additional HTTP CallOperations substitutions in workflow-utils
PR #5 (merged as 7e19455) use the same literal wire values and do not change the measured AST.
The complete per-node JSON is in ignored target output (not published).

## Per-workflow first blocker

| Workflow definition | Result | First reason (abbreviated) |
| --- | --- | --- |
| `BusinessAnalysis` | Raw CEL/reference | workflow=BusinessAnalysis node=0.0.0.except.0 step='cacheReportRead_switchRead'.case[0].result: unsupported raw CEL/reference 'cacheReportReadResult_4d4b51f7'; use valid ... |
| `LoadConversationHistory` | Rust source emitted | — |
| `Summaries-All` | Trusted execution globals | node=0.3: global cloud.region requires trusted server bindings; not available in pure runner |
| `Summaries-Convo` | Step/result symbol collisions | workflow=Summaries-Convo node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62f800c6 |
| `Summaries-Session` | Step/result symbol collisions | workflow=Summaries-Session node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62f800c6 |
| `Summaries-Week` | Step/result symbol collisions | workflow=Summaries-Week node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62f800c6 |
| `Summaries-Term` | Step/result symbol collisions | workflow=Summaries-Term node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62f800c6 |
| `Summaries-Backfill` | Trusted execution globals | node=0.1.each.0: global execution.id requires trusted server bindings; not available in pure runner |
| `Summaries` | Trusted execution globals | node=0.1: global execution.id requires trusted server bindings; not available in pure runner |
| `assistant-ExtractTopics-All` | Trusted execution globals | node=0.3: global cloud.region requires trusted server bindings; not available in pure runner |
| `assistant-ExtractTopics-Convo` | Step/result symbol collisions | workflow=assistant-ExtractTopics-Convo node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62... |
| `assistant-ExtractTopics-Session` | Step/result symbol collisions | workflow=assistant-ExtractTopics-Session node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_... |
| `assistant-ExtractTopics-Week` | Step/result symbol collisions | workflow=assistant-ExtractTopics-Week node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62f... |
| `assistant-ExtractTopics-Term` | Step/result symbol collisions | workflow=assistant-ExtractTopics-Term node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult_62f... |
| `assistant-ExtractTopics-Backfill` | Trusted execution globals | node=0.1.each.0: global execution.id requires trusted server bindings; not available in pure runner |
| `assistant-ExtractTopics` | Trusted execution globals | node=0.1: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-Crawler` | Parallel / ParallelFor lowering | workflow=codebase-Crawler node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `codebase-Crawler-tools` | Trusted execution globals | node=0.0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-Crawler-prompts` | Trusted execution globals | node=0.0.0.0.1.try.0: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-ProjectManager` | Parallel / ParallelFor lowering | workflow=codebase-ProjectManager node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `codebase-ProjectManager-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-ProjectManager-prompts` | Trusted execution globals | node=0.0.0.0.1.try.0: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_cli-CliDevManager` | Parallel / ParallelFor lowering | workflow=codebase-awfl_cli-CliDevManager node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `codebase-awfl_cli-CliDevManager-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_cli-CliDevManager-prompts` | Trusted execution globals | node=0.0.0.0.1.try.0: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_cli-CliManager` | Parallel / ParallelFor lowering | workflow=codebase-awfl_cli-CliManager node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `codebase-awfl_cli-CliManager-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_cli-CliManager-prompts` | Trusted execution globals | node=0.0.0.0.1.try.0: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_web-Sessions` | Parallel / ParallelFor lowering | workflow=codebase-awfl_web-Sessions node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `codebase-awfl_web-Sessions-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_web-Sessions-prompts` | Step/result symbol collisions | workflow=codebase-awfl_web-Sessions-prompts node=0.0.0.0.0.1.3 step='preloadFile_block': colliding step result symbol: preloadFile_blockError |
| `codebase-awfl_web-Webmaster` | Parallel / ParallelFor lowering | workflow=codebase-awfl_web-Webmaster node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `codebase-awfl_web-Webmaster-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-awfl_web-Webmaster-prompts` | Trusted execution globals | node=0.0.0.0.1.try.0: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-jobs-ContextAgent` | Parallel / ParallelFor lowering | workflow=codebase-jobs-ContextAgent node=0.0.try.6.case0.0 step='loadContext': Parallel requires native lowering before Rust emission; cannot silently interpret YAML |
| `codebase-jobs-ContextAgent-tools` | Trusted execution globals | node=0.0.0.0.0.try.2: global execution.id requires trusted server bindings; not available in pure runner |
| `codebase-jobs-ContextAgent-prompts` | Trusted execution globals | node=0.0.0.0.1.try.0: global execution.id requires trusted server bindings; not available in pure runner |
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
| `context-ContextCollapser-SegKala` | Step/result symbol collisions | workflow=context-ContextCollapser-SegKala node=0.0.1.case0.0.0.1.try.0 step='messages_components_SegKala': colliding step result symbol: messages_components_SegKalaResult... |
| `evals-SweBenchLiteEval` | Parallel / ParallelFor lowering | workflow=evals-SweBenchLiteEval node=0.1 step='runExperiments': ParallelFor requires native lowering before Rust emission; cannot silently interpret YAML |
| `evals-experiments-SweBenchLite` | Step/result symbol collisions | workflow=evals-experiments-SweBenchLite node=0.1.try.0.try.1.0.0 step='buildParams': colliding step result symbol: buildParamsError |
| `helpers-Chain` | Trusted execution globals | node=0.1.each.0: global execution.id requires trusted server bindings; not available in pure runner |
| `helpers-Tasks` | Rust source emitted | — |
| `helpers-ToolDefs` | Rust source emitted | — |
| `helpers-ToolDispatcher` | Trusted execution globals | node=0.0.try.2.each.1.1: global execution.id requires trusted server bindings; not available in pure runner |
| `helpers-links-SaveReflection` | Trusted execution globals | node=0.0.case0.0: global execution.id requires trusted server bindings; not available in pure runner |
| `tools-Agent` | Trusted execution globals | node=0.0: global cloud.region requires trusted server bindings; not available in pure runner |
| `tools-CliTools` | Trusted execution globals | node=0.0.try.1.case0.0.except.0.case0.0: global execution.id requires trusted server bindings; not available in pure runner |
| `tools-SpawnCrawlers` | Parallel / ParallelFor lowering | workflow=tools-SpawnCrawlers node=0.0 step='spawn': ParallelFor requires native lowering before Rust emission; cannot silently interpret YAML |
| `tools-Tasks` | Rust source emitted | — |
