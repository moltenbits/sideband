#!/bin/sh
# Claude Code UserPromptSubmit hook for Sideband.
#
# Journals every prompt the human types, verbatim, through `sideband
# capture-human` before the model sees it, when this session owns the Claude
# cursor in this repository. This makes human capture and directive routing
# deterministic (REQUIREMENTS.md 7.1). A delivered `[Sideband message]`
# envelope, a slash command, and a `!` shell command are never captured (7.4).
#
# Capture never blocks the prompt: any failure is reported on stderr and the
# hook exits 0. `sideband init` installs this file under ~/.claude/skills and
# registers it in the repository's .claude/settings.json.
set -u
payload=$(cat)
command -v sideband >/dev/null 2>&1 || { echo "sideband: executable not on PATH" >&2; exit 0; }
exec python3 - "$payload" <<'PY'
import json, os, subprocess, sys, tempfile

payload = json.loads(sys.argv[1])
prompt = payload.get("prompt") or ""
cwd = payload.get("cwd") or os.getcwd()
session = payload.get("session_id") or ""

def run(*args):
    result = subprocess.run(["sideband", *args], cwd=cwd, capture_output=True, text=True)
    return result.returncode, result.stdout, result.stderr

# Capture: only when this session owns the Claude cursor in this repository.
if not prompt.strip() or prompt.lstrip().startswith("[Sideband message]") or prompt.lstrip().startswith("/") or prompt.lstrip().startswith("!"):
    sys.exit(0)
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
    code, out, err = run("capture-human", "--body-file", path)
    if code != 0:
        sys.stderr.write("sideband: capture failed (exit %d): %s\n" % (code, err.strip()))
    else:
        pushes = json.loads(out).get("pushes", [])
        routed = [p for p in pushes if p["outcome"] != "listener-delivers"]
        if routed:
            print(json.dumps({"hookSpecificOutput": {"hookEventName": "UserPromptSubmit",
                  "additionalContext": "Sideband journaled this prompt and delivered it: " + ", ".join("%s=%s" % (p["role"], p["outcome"]) for p in routed) + ". Do not capture or route it again."}}))
        else:
            print(json.dumps({"hookSpecificOutput": {"hookEventName": "UserPromptSubmit",
                  "additionalContext": "Sideband journaled this prompt. Do not capture it again."}}))
finally:
    try:
        os.unlink(path)
    except OSError:
        pass
sys.exit(0)
PY
