const { test } = require('node:test');
const assert = require('node:assert/strict');
const { listen } = require('./listen.cjs');
const { capture } = require('./capture-human.cjs');
const { run } = require('./process.cjs');
const { mkdtempSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');

const options = { repo: '/repo', thread: 'parent', from: 10, timeout: 30,
  duration: 60, maxDeliveries: 1, sideband: 'sideband', codex: 'codex' };
const entry = (from = 'claude', to = ['codex']) => ({
  metadata: { id: 'peer-id', from, to, expects_reply: true }, body: '@all $(danger)\n雪',
});
const batch = (start, end, entries) => JSON.stringify({ start, end, entries,
  diagnostics: [], timed_out: false }) + '\n';
const ok = stdout => ({ code: 0, stdout, stderr: '' });

function harness(results) {
  const calls = [], events = [];
  return { calls, events, run: async (file, args, extra) => {
    calls.push({ file, args, extra });
    assert.ok(results.length, 'unexpected command');
    return results.shift();
  }, emit: event => events.push(event) };
}

test('queue receives the native JSON verbatim as one argument and advances after acceptance', async () => {
  const raw = batch(10, 40, [entry()]);
  const h = harness([ok(raw), ok('Queued')]);
  const result = await listen(options, h.run, h.emit);
  assert.deepEqual(h.calls[1].args, ['queue', '--thread', 'parent', '--message', '[Sideband message]\n' + raw]);
  assert.equal(result.offset, 40);
  assert.equal(result.reason, 'delivery-cap');
});

test('timeout re-arms silently at the same offset; unrelated and self entries advance without queue', async () => {
  const h = harness([{ code: 6, stdout: '{}', stderr: '' },
    ok(batch(10, 20, [entry('codex'), entry('claude', ['human:james'])])),
    ok(batch(20, 30, [entry()])), ok('Queued')]);
  await listen(options, h.run, h.emit);
  assert.deepEqual(h.calls.filter(c => c.args[0] === 'wait').map(c => c.args[4]), ['10', '10', '20']);
  assert.equal(h.calls.filter(c => c.args[0] === 'queue').length, 1);
});

test('native failure queues stderr once and stops without advancing or retrying', async () => {
  const h = harness([{ code: 4, stdout: '', stderr: 'lock contention\n' }, ok('Queued')]);
  const result = await listen(options, h.run, h.emit);
  assert.equal(result.offset, 10);
  assert.equal(result.reason, 'error');
  assert.match(h.calls[1].args[4], /lock contention\n/);
  assert.equal(h.calls.length, 2);
});

test('queue failure never advances or retries, preserving the failed range in the local log', async () => {
  const h = harness([ok(batch(10, 40, [entry()])), { code: 1, stdout: '', stderr: 'session absent' }]);
  const result = await listen(options, h.run, h.emit);
  assert.equal(result.offset, 10);
  assert.equal(result.reason, 'queue-failed');
  assert.equal(h.calls.length, 2);
  assert.ok(h.events.some(e => e.event === 'queue-failed' && e.stderr === 'session absent'));
});

test('invalid JSON, nonprogressing offsets and malformed entries stop visibly', async () => {
  for (const raw of ['not json', batch(10, 10, []), batch(9, 40, []), batch(10, 40, [{}])]) {
    const h = harness([ok(raw), ok('Queued')]);
    assert.equal((await listen(options, h.run, h.emit)).reason, 'error');
  }
});

test('new diagnostics are delivered even when no entry addresses Codex', async () => {
  const raw = JSON.stringify({ start: 10, end: 20, entries: [],
    diagnostics: [{ offset: 10, reason: 'bad frame' }], timed_out: false });
  const h = harness([ok(raw), ok('Queued')]);
  assert.equal((await listen(options, h.run, h.emit)).offset, 20);
  assert.equal(h.calls[1].args[4], '[Sideband message]\n' + raw);
});

test('invalid or unbounded listener settings run no commands', async () => {
  for (const patch of [{ timeout: 3601 }, { timeout: 0 }, { from: -1 }, { thread: '' }, { duration: 0 }, { maxDeliveries: 0 }]) {
    await assert.rejects(listen({ ...options, ...patch }, () => assert.fail('command ran')));
  }
});

test('observation cutoff queues a transport notice without declaring requests failed', async () => {
  const h = harness([ok('Queued')]);
  let tick = 0;
  const result = await listen(options, h.run, h.emit, () => tick++ ? 61000 : 0);
  assert.equal(result.reason, 'cutoff');
  assert.match(h.calls[0].args[4], /observation cutoff/);
  assert.equal(result.offset, 10);
});

const hookOptions = { repo: '/repo', thread: 'parent', human: 'james', sideband: 'sideband' };
const prompt = { session_id: 'parent', hook_event_name: 'UserPromptSubmit', cwd: '/repo', prompt: '@claude raw\n雪\n' };

test('capture sends the exact prompt through stdin, returning only identity context', async () => {
  const h = harness([ok(JSON.stringify({ metadata: { id: 'human-id', from: 'human:james', via: 'codex' } }))]);
  const result = await capture(prompt, hookOptions, h.run);
  assert.equal(h.calls[0].extra.input, prompt.prompt);
  assert.ok(!h.calls[0].args.includes(prompt.prompt));
  assert.match(result.hookSpecificOutput.additionalContext, /human-id/);
  assert.ok(!result.hookSpecificOutput.additionalContext.includes(prompt.prompt));
});

test('transport envelopes, inactive sessions and other events never capture', async () => {
  for (const patch of [{ prompt: '[Sideband message]\n{}' }, { session_id: 'other' }, { hook_event_name: 'Stop' }]) {
    assert.equal(await capture({ ...prompt, ...patch }, hookOptions, () => assert.fail('captured')), null);
  }
});

test('invalid hook input and native capture failures block, with no retry', async () => {
  await assert.rejects(capture({ ...prompt, prompt: null }, hookOptions, () => assert.fail('captured')));
  const h = harness([{ code: 5, stdout: '', stderr: 'disk full' }]);
  await assert.rejects(capture(prompt, hookOptions, h.run), /disk full/);
  assert.equal(h.calls.length, 1);
});

test('native capture hook preserves body and routing, skips queue input, and native wait feeds the adapter',
  { skip: !process.env.SIDEBAND_TEST_BINARY }, async () => {
    const repo = mkdtempSync(join(tmpdir(), 'sideband-codex-adapter-test-'));
    try {
      assert.equal((await run('git', ['init', '--quiet', repo])).code, 0);
      const binary = process.env.SIDEBAND_TEST_BINARY;
      const hookArgs = [join(__dirname, 'capture-human.cjs'), '--repo', repo,
        '--thread', 'parent', '--human', 'james', '--sideband', binary];
      const actualPrompt = { ...prompt, cwd: repo };
      const captured = await run(process.execPath, hookArgs, { input: JSON.stringify(actualPrompt) });
      assert.equal(captured.code, 0, captured.stderr);
      const read = JSON.parse((await run(binary, ['wait', '--repo', repo, '--from', '0', '--timeout', '1'])).stdout);
      assert.equal(read.entries.length, 1);
      assert.equal(read.entries[0].body, actualPrompt.prompt);
      assert.deepEqual(read.entries[0].metadata.to, ['claude']);
      const humanId = read.entries[0].metadata.id;
      assert.ok(JSON.parse(captured.stdout).hookSpecificOutput.additionalContext.includes(humanId));
      const skipped = await run(process.execPath, hookArgs, {
        input: JSON.stringify({ ...actualPrompt, prompt: '[Sideband message]\n' + JSON.stringify(read) }),
      });
      assert.equal(skipped.code, 0);
      assert.equal(skipped.stdout, '');
      const timedOut = await run(binary, ['wait', '--repo', repo, '--from', String(read.end), '--timeout', '1']);
      assert.equal(timedOut.code, 6);
      const sent = await run(binary, ['append-agent', '--repo', repo, '--from', 'claude', '--to', 'codex',
        '--type', 'request', '--caused-by', humanId], { input: 'ping fixture\n雪' });
      assert.equal(sent.code, 0, sent.stderr);
      const queues = [];
      const result = await listen({ ...options, repo, from: read.end, sideband: binary }, async (file, args, extra) => {
        if (file === 'codex') { queues.push(args); return ok('Queued fixture'); }
        return run(file, args, extra);
      });
      assert.equal(result.reason, 'delivery-cap');
      const delivered = JSON.parse(queues[0][4].slice('[Sideband message]\n'.length));
      assert.equal(delivered.entries[0].body, 'ping fixture\n雪');
      assert.equal(delivered.entries[0].metadata.caused_by, humanId);
      const rejected = await run(binary, ['append-agent', '--repo', repo, '--from', 'codex', '--to', 'claude',
        '--type', 'request'], { input: 'no human ancestor' });
      assert.equal(rejected.code, 2);
      const controller = new AbortController();
      const pending = run(binary, ['wait', '--repo', repo, '--from', String(delivered.end), '--timeout', '30'],
        { signal: controller.signal });
      controller.abort();
      assert.notEqual((await pending).code, 0);
    } finally {
      rmSync(repo, { recursive: true }); // Only this test's freshly created repository.
    }
  });
