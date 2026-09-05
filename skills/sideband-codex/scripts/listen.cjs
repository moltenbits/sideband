const { parseArgs } = require('node:util');
const { run } = require('./process.cjs');

const PREAMBLE = '[Sideband message]\n';

async function listen(options, command = run, emit = () => {}, now = Date.now) {
  const { repo, thread, from, timeout, duration, maxDeliveries, sideband, codex, signal } = options;
  for (const [name, value, min, max] of [
    ['from', from, 0, Number.MAX_SAFE_INTEGER], ['timeout', timeout, 1, 3600],
    ['duration', duration, 1, 3600], ['maxDeliveries', maxDeliveries, 1, 100],
  ]) {
    if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`Invalid ${name}`);
  }
  if (![repo, thread, sideband, codex].every(v => typeof v === 'string' && v.length)) {
    throw new Error('Explicit repo, parent thread and executable paths are required');
  }
  let offset = from, deliveries = 0;
  const deadline = now() + duration * 1000;
  const event = (name, extra = {}) => emit({ timestamp: new Date().toISOString(), event: name, offset, ...extra });
  const queue = async text => {
    const result = await command(codex, ['queue', '--thread', thread, '--message', PREAMBLE + text], { signal });
    event(result.code === 0 ? 'queued' : 'queue-failed', result);
    return result.code === 0;
  };
  const finish = reason => { event('stopped', { reason, deliveries }); return { reason, offset, deliveries }; };
  while (!signal?.aborted) {
    const remaining = deadline - now();
    if (remaining <= 0) {
      await queue('[Sideband transport notice]\nListener observation cutoff reached; listener stopped. Pending requests are not resolved or retried.');
      return finish('cutoff');
    }
    const waitSeconds = Math.min(timeout, Math.max(1, Math.ceil(remaining / 1000)));
    event('waiting', { timeout: waitSeconds });
    const result = await command(sideband, ['wait', '--repo', repo, '--from', String(offset),
      '--timeout', String(waitSeconds)], { timeout: waitSeconds * 1000 + 10000, signal });
    if (signal?.aborted) return finish('cancelled');
    if (result.code === 6) continue; // Re-arm transport, never retry a request or wake the model.
    let batch;
    try {
      if (result.code !== 0) throw new Error(result.stderr || `sideband wait exited ${result.code}`);
      batch = JSON.parse(result.stdout);
      if (batch.start !== offset || !Number.isSafeInteger(batch.end) || batch.end <= offset ||
          batch.timed_out !== false || !Array.isArray(batch.entries) || !Array.isArray(batch.diagnostics)) {
        throw new Error('Invalid wait envelope or nonprogressing offset');
      }
      if (!batch.entries.every(e => e?.metadata && typeof e.metadata.id === 'string' &&
          typeof e.metadata.from === 'string' && Array.isArray(e.metadata.to) && typeof e.body === 'string')) {
        throw new Error('Invalid entry in wait envelope');
      }
    } catch (error) {
      await queue('[Sideband transport error]\n' + error.message);
      return finish('error');
    }
    const addressed = batch.entries.some(e => e.metadata.to.includes('codex') && e.metadata.from !== 'codex');
    if (addressed || batch.diagnostics.length) {
      // Filtering decides whether to wake, not how to rewrite the native envelope.
      if (!await queue(result.stdout)) return finish('queue-failed');
      deliveries++;
    }
    offset = batch.end;
    event('advanced', { entryIds: batch.entries.map(e => e.metadata.id) });
    if (deliveries >= maxDeliveries) return finish('delivery-cap');
  }
  return finish('cancelled');
}

if (require.main === module) {
  const controller = new AbortController();
  process.once('SIGTERM', () => controller.abort());
  process.once('SIGINT', () => controller.abort());
  (async () => {
    const { values } = parseArgs({ options: Object.fromEntries(
      ['repo', 'thread', 'from', 'timeout', 'duration', 'max-deliveries', 'sideband', 'codex']
        .map(key => [key, { type: 'string' }])) });
    const result = await listen({ repo: values.repo, thread: values.thread, from: Number(values.from),
      timeout: Number(values.timeout ?? 3600), duration: Number(values.duration),
      maxDeliveries: Number(values['max-deliveries']), sideband: values.sideband ?? 'sideband',
      codex: values.codex ?? 'codex', signal: controller.signal }, run,
    event => process.stdout.write(JSON.stringify(event) + '\n'));
    if (['error', 'queue-failed'].includes(result.reason)) process.exitCode = 1;
  })().catch(error => { process.stderr.write(error.message + '\n'); process.exitCode = 2; });
}

module.exports = { listen };
