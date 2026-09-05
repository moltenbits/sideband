const { parseArgs } = require('node:util');
const { run } = require('./process.cjs');

async function capture(payload, options, command = run) {
  const { repo, thread, human, sideband = 'sideband' } = options;
  if (![repo, thread, human].every(v => typeof v === 'string' && v.length)) {
    throw new Error('Capture requires explicit active repo, parent thread and human identity');
  }
  if (!payload || typeof payload !== 'object') throw new Error('Invalid hook input');
  if (payload.hook_event_name !== 'UserPromptSubmit' || payload.session_id !== thread) return null;
  if (typeof payload.prompt !== 'string') throw new Error('Hook prompt must be a string');
  if (payload.prompt.startsWith('[Sideband message]')) return null;
  const result = await command(sideband, ['capture-human', '--repo', repo, '--via', 'codex', '--human', human],
    { input: payload.prompt });
  if (result.code !== 0) throw new Error(result.stderr || `capture-human exited ${result.code}`);
  const entry = JSON.parse(result.stdout);
  if (typeof entry.metadata?.id !== 'string' || entry.metadata.from !== `human:${human}` || entry.metadata.via !== 'codex') {
    throw new Error('Invalid capture result; do not retry because the append may have succeeded');
  }
  return { hookSpecificOutput: { hookEventName: 'UserPromptSubmit',
    additionalContext: `Sideband captured this human prompt as ${JSON.stringify(entry.metadata.id)}. Do not capture it again.` } };
}

if (require.main === module) {
  (async () => {
    const { values } = parseArgs({ options: Object.fromEntries(
      ['repo', 'thread', 'human', 'sideband'].map(key => [key, { type: 'string' }])) });
    let input = '';
    process.stdin.setEncoding('utf8');
    for await (const chunk of process.stdin) input += chunk;
    const result = await capture(JSON.parse(input), values);
    if (result) process.stdout.write(JSON.stringify(result) + '\n');
  })().catch(error => {
    process.stderr.write(`Sideband capture failed; stop and inspect, do not retry automatically: ${error.message}\n`);
    process.exitCode = 2; // UserPromptSubmit blocks this prompt, rather than losing its capture silently.
  });
}

module.exports = { capture };
