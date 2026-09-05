#!/bin/sh
# Claude Code UserPromptSubmit hook for Sideband (NOT installed automatically).
#
# Reads the hook payload on stdin, and when Sideband is active for this session
# in this repository, journals the prompt verbatim through `sideband capture-human`
# before the model sees it. This makes human capture and directive routing
# deterministic (REQUIREMENTS.md 7.1). It never captures a delivered Sideband
# envelope (7.4) and never blocks the prompt: any failure is reported on stderr
# and the hook exits 0.
#
# Install by adding to ~/.claude/settings.json (or the project's .claude/settings.json):
#   {"hooks":{"UserPromptSubmit":[{"hooks":[{"type":"command",
#     "command":"$HOME/.claude/skills/sideband/hooks/capture-human.sh"}]}]}}
set -u
payload=$(cat)
command -v sideband >/dev/null 2>&1 || { echo "sideband: executable not on PATH; prompt not captured" >&2; exit 0; }
exec python3 - "$payload" <<'PY'
import json, os, subprocess, sys, tempfile

payload = json.loads(sys.argv[1])
prompt = payload.get("prompt") or ""
cwd = payload.get("cwd") or os.getcwd()
session = payload.get("session_id") or ""

if not prompt.strip():
    sys.exit(0)
if prompt.lstrip().startswith("[Sideband message]"):
    sys.exit(0)  # a delivered envelope, already journaled; never a human message

# Active only when this session owns Claude's cursor in this repository.
try:
    common = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                            cwd=cwd, capture_output=True, text=True, check=True).stdout.strip()
    with open(os.path.join(common, "sideband", "cursors", "claude.json")) as f:
        current = (json.load(f).get("session") or {}).get("id")
except Exception:
    sys.exit(0)
if not session or current != session:
    sys.exit(0)

with tempfile.NamedTemporaryFile("w", delete=False, suffix=".md", dir=os.path.join(common, "sideband")) as body:
    body.write(prompt)
    path = body.name
try:
    result = subprocess.run(["sideband", "capture-human", "--repo", cwd, "--via", "claude", "--body-file", path],
                            capture_output=True, text=True)
    if result.returncode != 0:
        sys.stderr.write("sideband: capture failed (exit %d): %s\n" % (result.returncode, result.stderr.strip()))
finally:
    try:
        os.unlink(path)
    except OSError:
        pass
sys.exit(0)
PY
