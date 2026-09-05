const { execFile } = require('node:child_process');

// Host command adapter only: never opens a journal or implements its framing.
function run(file, args, { input, timeout = 20000, signal } = {}) {
  return new Promise(resolve => {
    const child = execFile(file, args, { encoding: 'utf8', timeout, signal,
      maxBuffer: 16 * 1024 * 1024 }, (error, stdout, stderr) => {
      resolve({ code: error ? (error.code ?? 1) : 0, stdout,
        stderr: stderr || (error ? error.message : '') });
    });
    child.stdin.on('error', () => {}); // Early process exit is reported above.
    child.stdin.end(input);
  });
}

module.exports = { run };
