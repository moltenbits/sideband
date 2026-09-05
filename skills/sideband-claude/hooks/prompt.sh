#!/bin/sh
# Claude Code UserPromptSubmit hook for Sideband.
#
# Two jobs, both before the model sees the prompt:
#
# 1. `/sideband help|status|pending` are answered directly from the executable
#    and the prompt is blocked, so they behave like real commands: the output
#    appears in the terminal and no model turn happens.
# 2. Every other prompt is journaled verbatim through `sideband capture-human`
#    when this session owns the Claude cursor in this repository, which makes
#    human capture and directive routing deterministic (REQUIREMENTS.md 7.1).
#    A delivered `[Sideband message]` envelope is never captured (7.4).
#
# Capture never blocks the prompt: any failure is reported on stderr and the
# hook exits 0. Install in .claude/settings.json:
#   {"hooks":{"UserPromptSubmit":[{"hooks":[{"type":"command",
#     "command":"\"$CLAUDE_PROJECT_DIR\"/skills/sideband-claude/hooks/prompt.sh"}]}]}}
set -u
payload=$(cat)
command -v sideband >/dev/null 2>&1 || { echo "sideband: executable not on PATH" >&2; exit 0; }
exec python3 - "$payload" <<'PY'
import json, os, re, subprocess, sys, tempfile

payload = json.loads(sys.argv[1])
prompt = payload.get("prompt") or ""
cwd = payload.get("cwd") or os.getcwd()
session = payload.get("session_id") or ""

def run(*args):
    result = subprocess.run(["sideband", *args], cwd=cwd, capture_output=True, text=True)
    return result.returncode, result.stdout, result.stderr

def block(text):
    print(json.dumps({"decision": "block", "reason": text}))
    sys.exit(0)

def status_text():
    code, out, err = run("doctor")
    if code != 0:
        return "sideband doctor failed (exit %d): %s" % (code, err.strip())
    d = json.loads(out)
    lines = [d["version"],
             "state: %s%s" % (d["state_directory"], "" if d["initialized"] else " (not initialized; run `sideband init`)")]
    if d.get("config"):
        lines.append("human: %s (%s)" % (d["config"]["human"]["id"], d["config"]["human"]["display_name"]))
    if d.get("journal"):
        j = d["journal"]
        lines.append("journal: %d entries, %d bytes, %d diagnostics%s" % (j["entries"], j["bytes"], j["diagnostics"], ", incomplete tail" if j["incomplete_tail"] else ""))
    for role, r in d.get("roles", {}).items():
        if r["session_id"] is None:
            lines.append("%-6s no session | backlog %d, outgoing %d" % (role, r["backlog"], r["outgoing"]))
        else:
            lines.append("%-6s session %s (%s) | backlog %d, live %d, outgoing %d" % (
                role, r["session_id"][:8], "live" if r["session_live"] else "dead", r["backlog"], r["live"], r["outgoing"]))
    for s in d.get("skills", []):
        lines.append("skill %-6s %s" % (s["client"], s["state"]))
    if d.get("lock_owner_pid"):
        lines.append("lock held by pid %s" % d["lock_owner_pid"])
    return "\n".join(lines)

def pending_text():
    code, out, err = run("pending")
    if code != 0:
        return "sideband pending failed (exit %d): %s" % (code, err.strip())
    p = json.loads(out)
    lines = []
    for label in ("backlog", "live"):
        for e in p.get(label, []):
            m = e["metadata"]
            kind = "actionable" if m["expects_reply"] else "info"
            preview = e["body"].strip().splitlines()[0][:70] if e["body"].strip() else ""
            lines.append("%-7s %s %-12s %-8s %-10s %s" % (label, m["id"][:8], m["from"], m["type"], kind, preview))
    for rid, o in p.get("outgoing", {}).items():
        lines.append("outgoing %s %s, %d replies" % (rid[:8], o["state"], len(o["reply_ids"])))
    return "\n".join(lines) if lines else "Nothing pending for this client."

command = re.match(r"^\s*/sideband\s+(help|status|pending)\s*$", prompt)
if command:
    which = command.group(1)
    if which == "help":
        code, out, err = run("--help")
        block("/sideband            activate this session\n/sideband help       this text\n/sideband status     sessions, pending counts, journal health\n/sideband pending    open entries and unanswered requests\n/sideband off        stop the listener\n/sideband <message>  journal and route a message\n\n" + out.strip())
    if which == "status":
        block(status_text())
    if which == "pending":
        block(pending_text())

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
