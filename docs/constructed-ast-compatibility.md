# Constructed-AST compatibility snapshot

Generated from **unchanged** compiled AWFL workflow classes, using
`scripts/audit-real-workflows.sh` and `scripts/render_compatibility_report.py`.
The scanner walks expanded helper-built DSL graphs and attempts CFG/Rust
source emission. It **does not** compile Cargo binaries, exercise effects,
or prove Google Workflows behavioral parity.

- Workflow definitions: **66**
- Rust source emitted: **2**
- Source emission blocked: **64**
- Class-loading errors: **0**

## First blocker by workflow

| Category | Definitions |
| --- | ---: |
| Unsupported raw CEL/reference | 27 |
| Connector name not migrated | 24 |
| CEL function name not migrated | 11 |
| Rust source emitted | 2 |
| Control-flow lowering missing | 2 |

These are *first* failures: removing a blocker can uncover other unsupported
operations in the same graph. Do not treat counts as the total set of failures.

The original DSL's raw `sys.now()` reference is particularly common.
Native clock values must not be recomputed after continuation replay.

## Legacy operation names in instantiated graphs

| Kind | Name | Occurrences |
| --- | --- | ---: |
| call | `googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run` | 194 |
| function | `string` | 101 |
| function | `text.to_lower` | 42 |
| function | `get_type` | 38 |
| call | `googleapis.storage.v1.objects.get` | 14 |
| function | `int` | 3 |
| call | `events.create_callback_endpoint` | 3 |
| call | `events.await_callback` | 3 |
| function | `base64.encode` | 1 |

These counts include operations instantiated by helpers; replacing a name in
one helper source can fix many occurrences. Only change application Scala
source to replace CEL function and connector names with the existing
`CelFunctions` / `CallOperations` generic constants. Do not restructure
workflow logic, introduce typed public AST nodes, or change helper APIs.

## Priority and safety boundaries

1. Complete compiler-private syntactic lowering (e.g. `FlatMap`).
2. Change only the source-level operation-name values where needed;
   preserve the same constructed workflow shape in both backends.
3. Implement each missing function and server adapter with separately
   tested semantics, including nondeterministic time/UUID outcomes.
4. Add durable fork/join and child/callback execution before treating
   ProjectManager as runnable on native.
5. Verify actual Cargo builds, mock-effect resumptions, and Google-vs-Rust
   differential behavior; source emission is only the first gate.

## Per-definition result

| Source module | Definition | Status | First issue |
| --- | --- | --- | --- |
| `us.awfl.workflows.BusinessAnalysis` | `BusinessAnalysis` | Unsupported raw CEL/reference | workflow=BusinessAnalysis node=0.0.0.except.0 step='cacheReportRead_switchRead'.case[0].result: unsupported raw CEL/reference 'cacheReportReadResult_4d4b51f7'; use valid DSL v… |
| `us.awfl.workflows.LoadConversationHistory` | `LoadConversationHistory` | Rust source emitted | — |
| `us.awfl.workflows.Summaries` | `Summaries-All` | Connector name not migrated | workflow=Summaries-All node=0.0 step='Convo_run': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use CallOperations consta… |
| `us.awfl.workflows.Summaries` | `Summaries-Convo` | CEL function name not migrated | workflow=Summaries-Convo node=0.0.0.try.0.0 step='lock_acquire'.args.body.id: unknown native CEL function 'string'; use CelFunctions constants |
| `us.awfl.workflows.Summaries` | `Summaries-Session` | CEL function name not migrated | workflow=Summaries-Session node=0.0.0.try.0.0 step='lock_acquire'.args.body.id: unknown native CEL function 'string'; use CelFunctions constants |
| `us.awfl.workflows.Summaries` | `Summaries-Week` | CEL function name not migrated | workflow=Summaries-Week node=0.0.0.try.0.0 step='lock_acquire'.args.body.id: unknown native CEL function 'string'; use CelFunctions constants |
| `us.awfl.workflows.Summaries` | `Summaries-Term` | CEL function name not migrated | workflow=Summaries-Term node=0.0.0.try.0.0 step='lock_acquire'.args.body.id: unknown native CEL function 'string'; use CelFunctions constants |
| `us.awfl.workflows.Summaries` | `Summaries-Backfill` | Connector name not migrated | workflow=Summaries-Backfill node=0.1.each.0 step='All_Backfill_run': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use Ca… |
| `us.awfl.workflows.Summaries` | `Summaries` | Connector name not migrated | workflow=Summaries node=0.1 step='All_Latest_run': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use CallOperations const… |
| `us.awfl.workflows.assistant.ExtractTopics` | `assistant-ExtractTopics-All` | Connector name not migrated | workflow=assistant-ExtractTopics-All node=0.0 step='Convo_run': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use CallOpe… |
| `us.awfl.workflows.assistant.ExtractTopics` | `assistant-ExtractTopics-Convo` | CEL function name not migrated | workflow=assistant-ExtractTopics-Convo node=0.0.0.try.0.0 step='lock_acquire'.args.body.id: unknown native CEL function 'string'; use CelFunctions constants |
| `us.awfl.workflows.assistant.ExtractTopics` | `assistant-ExtractTopics-Session` | CEL function name not migrated | workflow=assistant-ExtractTopics-Session node=0.0.0.try.0.0 step='lock_acquire'.args.body.id: unknown native CEL function 'string'; use CelFunctions constants |
| `us.awfl.workflows.assistant.ExtractTopics` | `assistant-ExtractTopics-Week` | CEL function name not migrated | workflow=assistant-ExtractTopics-Week node=0.0.0.try.0.0 step='lock_acquire'.args.body.id: unknown native CEL function 'string'; use CelFunctions constants |
| `us.awfl.workflows.assistant.ExtractTopics` | `assistant-ExtractTopics-Term` | CEL function name not migrated | workflow=assistant-ExtractTopics-Term node=0.0.0.try.0.0 step='lock_acquire'.args.body.id: unknown native CEL function 'string'; use CelFunctions constants |
| `us.awfl.workflows.assistant.ExtractTopics` | `assistant-ExtractTopics-Backfill` | Connector name not migrated | workflow=assistant-ExtractTopics-Backfill node=0.1.each.0 step='All_Backfill_run': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.execution… |
| `us.awfl.workflows.assistant.ExtractTopics` | `assistant-ExtractTopics` | Connector name not migrated | workflow=assistant-ExtractTopics node=0.1 step='All_Latest_run': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use CallOp… |
| `us.awfl.workflows.codebase.Crawler` | `codebase-Crawler` | Unsupported raw CEL/reference | workflow=codebase-Crawler node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid DSL values … |
| `us.awfl.workflows.codebase.Crawler` | `codebase-Crawler-tools` | Connector name not migrated | workflow=codebase-Crawler-tools node=0.0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use… |
| `us.awfl.workflows.codebase.Crawler` | `codebase-Crawler-prompts` | Unsupported raw CEL/reference | workflow=codebase-Crawler-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use valid DSL va… |
| `us.awfl.workflows.codebase.ProjectManager` | `codebase-ProjectManager` | Unsupported raw CEL/reference | workflow=codebase-ProjectManager node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid DSL … |
| `us.awfl.workflows.codebase.ProjectManager` | `codebase-ProjectManager-tools` | Connector name not migrated | workflow=codebase-ProjectManager-tools node=0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'… |
| `us.awfl.workflows.codebase.ProjectManager` | `codebase-ProjectManager-prompts` | Unsupported raw CEL/reference | workflow=codebase-ProjectManager-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use valid… |
| `us.awfl.workflows.codebase.awfl_cli.CliDevManager` | `codebase-awfl_cli-CliDevManager` | Unsupported raw CEL/reference | workflow=codebase-awfl_cli-CliDevManager node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use va… |
| `us.awfl.workflows.codebase.awfl_cli.CliDevManager` | `codebase-awfl_cli-CliDevManager-tools` | Connector name not migrated | workflow=codebase-awfl_cli-CliDevManager-tools node=0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executi… |
| `us.awfl.workflows.codebase.awfl_cli.CliDevManager` | `codebase-awfl_cli-CliDevManager-prompts` | Unsupported raw CEL/reference | workflow=codebase-awfl_cli-CliDevManager-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; u… |
| `us.awfl.workflows.codebase.awfl_cli.CliManager` | `codebase-awfl_cli-CliManager` | Unsupported raw CEL/reference | workflow=codebase-awfl_cli-CliManager node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid… |
| `us.awfl.workflows.codebase.awfl_cli.CliManager` | `codebase-awfl_cli-CliManager-tools` | Connector name not migrated | workflow=codebase-awfl_cli-CliManager-tools node=0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions… |
| `us.awfl.workflows.codebase.awfl_cli.CliManager` | `codebase-awfl_cli-CliManager-prompts` | Unsupported raw CEL/reference | workflow=codebase-awfl_cli-CliManager-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use … |
| `us.awfl.workflows.codebase.awfl_web.Sessions` | `codebase-awfl_web-Sessions` | Unsupported raw CEL/reference | workflow=codebase-awfl_web-Sessions node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid D… |
| `us.awfl.workflows.codebase.awfl_web.Sessions` | `codebase-awfl_web-Sessions-tools` | Connector name not migrated | workflow=codebase-awfl_web-Sessions-tools node=0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.r… |
| `us.awfl.workflows.codebase.awfl_web.Sessions` | `codebase-awfl_web-Sessions-prompts` | Unsupported raw CEL/reference | workflow=codebase-awfl_web-Sessions-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use va… |
| `us.awfl.workflows.codebase.awfl_web.Webmaster` | `codebase-awfl_web-Webmaster` | Unsupported raw CEL/reference | workflow=codebase-awfl_web-Webmaster node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid … |
| `us.awfl.workflows.codebase.awfl_web.Webmaster` | `codebase-awfl_web-Webmaster-tools` | Connector name not migrated | workflow=codebase-awfl_web-Webmaster-tools node=0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.… |
| `us.awfl.workflows.codebase.awfl_web.Webmaster` | `codebase-awfl_web-Webmaster-prompts` | Unsupported raw CEL/reference | workflow=codebase-awfl_web-Webmaster-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use v… |
| `us.awfl.workflows.codebase.jobs.ContextAgent` | `codebase-jobs-ContextAgent` | Unsupported raw CEL/reference | workflow=codebase-jobs-ContextAgent node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid D… |
| `us.awfl.workflows.codebase.jobs.ContextAgent` | `codebase-jobs-ContextAgent-tools` | Connector name not migrated | workflow=codebase-jobs-ContextAgent-tools node=0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.r… |
| `us.awfl.workflows.codebase.jobs.ContextAgent` | `codebase-jobs-ContextAgent-prompts` | Unsupported raw CEL/reference | workflow=codebase-jobs-ContextAgent-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use va… |
| `us.awfl.workflows.codebase.workflows.BuildManager` | `BuildManager` | Unsupported raw CEL/reference | workflow=BuildManager node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid DSL values or e… |
| `us.awfl.workflows.codebase.workflows.BuildManager` | `BuildManager-tools` | Connector name not migrated | workflow=BuildManager-tools node=0.0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use Cal… |
| `us.awfl.workflows.codebase.workflows.BuildManager` | `BuildManager-prompts` | Unsupported raw CEL/reference | workflow=BuildManager-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use valid DSL values… |
| `us.awfl.workflows.codebase.workflows.Executor` | `Executor` | Unsupported raw CEL/reference | workflow=Executor node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid DSL values or expre… |
| `us.awfl.workflows.codebase.workflows.Executor` | `Executor-tools` | Connector name not migrated | workflow=Executor-tools node=0.0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use CallOpe… |
| `us.awfl.workflows.codebase.workflows.Executor` | `Executor-prompts` | Unsupported raw CEL/reference | workflow=Executor-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use valid DSL values or … |
| `us.awfl.workflows.codebase.workflows.PlainToCodeHook` | `PlainToCodeHook` | Connector name not migrated | workflow=PlainToCodeHook node=0.0.0.1.0 step='readPlain_run': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use CallOpera… |
| `us.awfl.workflows.codebase.workflows.PlainifyWriteHook` | `PlainifyWriteHook` | Connector name not migrated | workflow=PlainifyWriteHook node=0.0.0.1.0 step='readRaw_run': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use CallOpera… |
| `us.awfl.workflows.codebase.workflows.Sutradhara` | `Sutradhara` | Unsupported raw CEL/reference | workflow=Sutradhara node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid DSL values or exp… |
| `us.awfl.workflows.codebase.workflows.Sutradhara` | `Sutradhara-tools` | Connector name not migrated | workflow=Sutradhara-tools node=0.0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use CallO… |
| `us.awfl.workflows.codebase.workflows.Sutradhara` | `Sutradhara-prompts` | Unsupported raw CEL/reference | workflow=Sutradhara-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use valid DSL values o… |
| `us.awfl.workflows.codebase.workflows.ToolBuilder` | `ToolBuilder` | Unsupported raw CEL/reference | workflow=ToolBuilder node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid DSL values or ex… |
| `us.awfl.workflows.codebase.workflows.ToolBuilder` | `ToolBuilder-tools` | Connector name not migrated | workflow=ToolBuilder-tools node=0.0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use Call… |
| `us.awfl.workflows.codebase.workflows.ToolBuilder` | `ToolBuilder-prompts` | Unsupported raw CEL/reference | workflow=ToolBuilder-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use valid DSL values … |
| `us.awfl.workflows.codebase.workflows.WorkflowBuilder` | `WorkflowBuilder` | Unsupported raw CEL/reference | workflow=WorkflowBuilder node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid DSL values o… |
| `us.awfl.workflows.codebase.workflows.WorkflowBuilder` | `WorkflowBuilder-tools` | Connector name not migrated | workflow=WorkflowBuilder-tools node=0.0.0.0.0.0.try.2 step='toolDefs': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use … |
| `us.awfl.workflows.codebase.workflows.WorkflowBuilder` | `WorkflowBuilder-prompts` | Unsupported raw CEL/reference | workflow=WorkflowBuilder-prompts node=0.0.0.0.0.0.each.0 step='buildPromptList_switch'.case[0].result.create_time: unsupported raw CEL/reference 'sys.now()'; use valid DSL val… |
| `us.awfl.workflows.context.ContextCollapser` | `context-ContextCollapser-SegKala` | CEL function name not migrated | workflow=context-ContextCollapser-SegKala node=0.0.0.try.0.0 step='lock_acquire'.args.body.id: unknown native CEL function 'string'; use CelFunctions constants |
| `us.awfl.workflows.evals.SweBenchLiteEval` | `evals-SweBenchLiteEval` | Control-flow lowering missing | workflow=evals-SweBenchLiteEval node=0.1 step='runExperiments': ParallelFor requires native lowering before Rust emission; cannot silently interpret YAML |
| `us.awfl.workflows.evals.experiments.SweBenchLite` | `evals-experiments-SweBenchLite` | Connector name not migrated | workflow=evals-experiments-SweBenchLite node=0.0.0.1.0 step='writeFile_run': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'… |
| `us.awfl.workflows.helpers.Chain` | `helpers-Chain` | Connector name not migrated | workflow=helpers-Chain node=0.0 step='Run': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use CallOperations constants |
| `us.awfl.workflows.helpers.Tasks` | `helpers-Tasks` | CEL function name not migrated | workflow=helpers-Tasks node=0.0.1 step='current_content'.case[0].condition: unknown native CEL function 'get_type'; use CelFunctions constants |
| `us.awfl.workflows.helpers.ToolDefs` | `helpers-ToolDefs` | Rust source emitted | — |
| `us.awfl.workflows.helpers.ToolDispatcher` | `helpers-ToolDispatcher` | Unsupported raw CEL/reference | workflow=helpers-ToolDispatcher node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid DSL v… |
| `us.awfl.workflows.helpers.links.SaveReflection` | `helpers-links-SaveReflection` | Connector name not migrated | workflow=helpers-links-SaveReflection node=0.0.case0.0 step='callAgent': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; us… |
| `us.awfl.workflows.tools.Agent` | `tools-Agent` | Connector name not migrated | workflow=tools-Agent node=0.0 step='run_agent_workflow': unknown native Call 'googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run'; use CallOperations… |
| `us.awfl.workflows.tools.CliTools` | `tools-CliTools` | Unsupported raw CEL/reference | workflow=tools-CliTools node=0.0.try.0.try.0 step='registerExecForSessionCreate'.args.body.contents.created: unsupported raw CEL/reference 'sys.now()'; use valid DSL values or… |
| `us.awfl.workflows.tools.SpawnCrawlers` | `tools-SpawnCrawlers` | Control-flow lowering missing | workflow=tools-SpawnCrawlers node=0.0 step='spawn': ParallelFor requires native lowering before Rust emission; cannot silently interpret YAML |
| `us.awfl.workflows.tools.Tasks` | `tools-Tasks` | CEL function name not migrated | workflow=tools-Tasks node=0.0 step='select_task_tool'.case[0].condition: unknown native CEL function 'string'; use CelFunctions constants |

The full node-by-node JSON is intentionally kept in the ignored build
output directory (`target/constructed-ast-compatibility.json`) rather than
committed; it can be regenerated from the current application bytecode.
