#!/usr/bin/env node
// Smoke-test three unchanged Scala ProjectManager workflow variants as real
// native Rust executables. External effects and downstream helper workflows
// are deterministically mocked; this does not simulate live GCP credentials,
// remote effect durability, or full production workflow release resolution.
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const generatedDir = process.env.AWFL_PROJECTMANAGER_GENERATED_DIR;
const targetDir = process.env.AWFL_PROJECTMANAGER_TARGET_DIR;
const serverNativeDir = resolve(process.env.AWFL_SERVER_NATIVE_DIR || '../server/workflows/native');
if (!generatedDir || !targetDir) {
  throw new Error('Set AWFL_PROJECTMANAGER_GENERATED_DIR and AWFL_PROJECTMANAGER_TARGET_DIR');
}
const { createNativeLocalRunner } = await import(pathToFileURL(join(serverNativeDir, 'localWorkflows.js')).href);
const { buildNativeTrustedGlobals, runNativeArtifact } = await import(pathToFileURL(join(serverNativeDir, 'runner.js')).href);
const { tagNativeValue } = await import(pathToFileURL(join(serverNativeDir, 'executionStore.js')).href);
const workflows = ['codebase-ProjectManager', 'codebase-ProjectManager-tools', 'codebase-ProjectManager-prompts'];
const artifacts = {};
for (const name of workflows) {
  const manifest = JSON.parse(await readFile(join(generatedDir, name, 'manifest.json')));
  const toml = await readFile(join(generatedDir, name, 'Cargo.toml'), 'utf8');
  const packageName = toml.match(/^name\s*=\s*"([^"]+)"/m)?.[1];
  assert.ok(packageName, 'generated crate name missing');
  const executable = join(targetDir, packageName);
  const sha256 = createHash('sha256').update(await readFile(executable)).digest('hex');
  artifacts[name] = { executable, sha256, manifest, allowedOperations: [...new Set(manifest.effects.map(effect => effect.operation))] };
}
const env = { BASE_URL: 'https://jobs.example.invalid', userId: 'synthetic-user', projectId: 'synthetic-tenant', sessionId: 'synthetic-session', model: 'gpt-synthetic', callingWorkflowExec: '', background: false, workdir: '/workspace' };
const input = { query: 'Project Manager native integration smoke', fund: 100, spent: 0, usage: null, task: null, callback: null, toolChoice: null, sideCall: false, env };
const identity = { userId: env.userId, projectId: env.projectId };
const globals = (name, executionId) => buildNativeTrustedGlobals({state: {workflowName: name}, executionId, gcpProject: 'awfl-us', region: 'us-central1'});
const childResponses = {
  'helpers-ToolDefs': { defs: [] },
  'tools-CliTools': { encoded: JSON.stringify({exitCode: 0, output: 'synthetic command output'}) },
  'helpers-Tasks': { tasks: [] },
};
const helpers = {};
for (const name of workflows.slice(1)) {
  const artifact = artifacts[name];
  const calls = [];
  const handlers = {
    'http.get': async ({args}) => {
      calls.push(args.url);
      return {code: 200, body: args.url.includes('/agents/session/') ? {agentId: 'default'} : {tools: []}};
    },
    'storage.object_get': async ({args}) => { calls.push(args.object); return '# synthetic agent.sh\n'; },
    'log.write': async () => null,
  };
  const releases = {
    active: async workflow => {
      assert.ok(Object.hasOwn(childResponses, workflow), `unexpected helper ${workflow}`);
      calls.push('workflow.run:' + workflow);
      return {protocolVersion: 2, sha256: 'a'.repeat(64), allowedOperations: [], trustedGlobalsVersion: 1};
    },
    materialize: async () => ({executable: '/synthetic/child', sha256: 'a'.repeat(64), cleanup: async () => {}}),
  };
  const runner = createNativeLocalRunner({
    releases, handlers, rootExecutionId: 'native_' + (name.endsWith('-tools') ? '1' : '2').repeat(32),
    identity, gcpProject: 'awfl-us', region: 'us-central1',
    runArtifact: args => args.executable === '/synthetic/child'
      ? Promise.resolve({protocol_version: 2, status: 'completed', result: childResponses[calls.at(-1).slice('workflow.run:'.length)]})
      : runNativeArtifact(args),
  });
  try {
    helpers[name] = await runner.run({ workflowName: name, release: artifact, artifact, input, executionId: 'native_' + 'b'.repeat(32), globals: globals(name, 'native_' + 'b'.repeat(32)) });
  } finally { await runner.close(); }
}
const toolNames = helpers['codebase-ProjectManager-tools'].tools.map(t => t.function.name);
assert.equal(toolNames.length, 11);
for (const name of ['READ_FILE', 'RUN_COMMAND', 'CREATE_TASK']) assert.ok(toolNames.includes(name));
const prompts = helpers['codebase-ProjectManager-prompts'].prompts;
assert.equal(prompts.length, 10);
assert.ok(prompts.some(p => p.content.includes('Original funds allocated for the current task: $100\rSpent so far: $0')));

const main = artifacts['codebase-ProjectManager'];
const executionId = 'native_' + 'c'.repeat(32);
const envelope = {protocol_version: 2, input, trusted_globals: globals('codebase-ProjectManager', executionId)};
const effects = [];
let continuation = null;
let completed = null;
for (let i = 0; i < 100; i++) {
  const response = await runNativeArtifact({executable: main.executable, sha256: main.sha256, input: continuation ? {...envelope, continuation} : envelope});
  assert.notEqual(response.status, 'failed', `native main failed: ${JSON.stringify(response.error)}`);
  if (response.status === 'completed') {completed = response.result; break;}
  assert.equal(response.status, 'suspended');
  const {effect} = response;
  effects.push(effect.operation === 'http.post' ? effect.args.url : effect.operation);
  let value;
  if (effect.operation === 'native.parallel') {
    const results = [];
    for (const lane of effect.args.lanes) {
      let branch = {next_step: Number(lane.entry.slice(3)), slots: lane.slots, frames: []};
      let finished = false;
      for (let j = 0; j < 10; j++) {
        const result = await runNativeArtifact({executable: main.executable, sha256: main.sha256, input: {...envelope, continuation: branch}});
        if (result.status === 'completed') {results.push(result.result); finished = true; break;}
        assert.equal(result.status, 'suspended');
        assert.equal(result.effect.operation, 'workflow.run');
        const childName = result.effect.args.workflow_id.split('${WORKFLOW_ENV}')[0];
        assert.ok(Object.hasOwn(helpers, childName), `unexpected parallel child ${childName}`);
        branch = {...result.continuation, slots: {...result.continuation.slots, [result.effect.result_slot]: tagNativeValue(helpers[childName])}};
      }
      assert.ok(finished, 'fork lane did not complete');
    }
    assert.equal(results.length, 2);
    value = results;
  } else if (effect.operation === 'workflow.run') {
    const childName = effect.args.workflow_id.split('${WORKFLOW_ENV}')[0];
    assert.ok(['helpers-ToolDispatcher', 'Summaries', 'helpers-Chain', 'context-ContextCollapser-SegKala'].includes(childName), `unexpected workflow ${childName}`);
    value = {results: []};
  } else if (['http.post', 'http.get', 'http.patch'].includes(effect.operation)) {
    value = {code: 200, body: {
      ok: true, acquired: true, status: 'Running', created: 1700000000,
      yoj: [{role: 'user', content: input.query}], segments: [{end: 1700000000}],
      message: {role: 'assistant', content: 'Synthetic answer', tool_calls: []},
      total_cost: 0, usage: {prompt_tokens: 0, completion_tokens: 0, total_tokens: 0},
    }};
  } else if (effect.operation === 'log.write') value = null;
  else throw new Error(`unexpected native effect ${effect.operation}`);
  continuation = {...response.continuation, slots: {...response.continuation.slots, [effect.result_slot]: tagNativeValue(value)}};
}
assert.ok(completed, 'main workflow exceeded transition budget');
assert.equal(completed.message.content, 'Synthetic answer');
assert.equal(completed.total_cost, 0);
assert.ok(effects.includes('native.parallel'));
assert.ok(effects.includes('https://jobs.example.invalid/llm/chat'));
assert.ok(effects.includes('workflow.run'));
console.log('PROJECTMANAGER_NATIVE_SMOKE_PASS', JSON.stringify({binaryCount: workflows.length, toolCount: toolNames.length, promptCount: prompts.length, transitions: effects.length, effects}));
