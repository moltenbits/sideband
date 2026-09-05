# Wake-path spike (REQUIREMENTS.md 10.6)

The feasibility gate requires proof that an entry appended to the journal by
another process wakes each client's idle parent conversation without an
intermediary daemon, MCP server, hosted runtime service, or headless peer
invocation. These experiments do not use hooks; section 10.6 does not impose
a blanket hook prohibition.

## Claude Code: passed (2026-09-04)

Setup, run from an interactive Claude Code session in this repository with the
native `sideband` binary on `PATH`:

1. The session started `sideband wait --repo <repo> --from 0 --timeout 120` as a
   background Bash task and then went idle.
2. A detached shell process (`nohup ... & disown`, not tracked by the session)
   slept ten seconds and ran `sideband append --body-file ...`.
3. The wait process observed the complete entry and exited 0 with
   `{"start":0,"end":141,"entries":[...],"timed_out":false}`.
4. Claude Code delivered the task completion as a notification, which
   re-invoked the parent conversation. The parent read the entry, with its
   body verbatim, and could act on it.

Mechanism: Claude Code re-invokes the model when a background task exits. No
model tokens were spent while the wait process was blocked. The entry landed
in `.git/sideband/journal.md`, which `git status` does not show.

Observed detail worth carrying into the design: the delivery is the task's
stdout, so the JSON shape of `wait` is the delivery envelope on this host.

## Codex: failed idle wake via subagent message (2026-09-05 UTC)

Experiment ID: `sideband-codex-wake.R0qG3u`. Prepared on 2026-09-05 UTC
(2026-09-04 in the session's America/Bogota timezone).

Environment and baseline:

- `codex --version`: `codex-cli 0.153.3` (installed CLI).
- Native executable: `/Users/jamesdh/.local/bin/sideband`.
- Binary SHA-256:
  `0c2fb5fee10b4ed76315d2b05db1d6069334be100a25c842ff38c7700c70d9f4`.
- Repository: `/Users/jamesdh/Projects/moltenbits/sideband`.
- Initial journal size: 141 bytes; existing entries must not be replayed.
- `./gradlew test` succeeded with the test task up-to-date; no fresh test
  execution or native rebuild is claimed.

Procedure for this attempt:

1. Spawn exactly one transport subagent, `/root/sideband_listener`, using the
   current host's `collaboration.spawn_agent` facility. It runs:

   ```sh
   /Users/jamesdh/.local/bin/sideband wait --repo /Users/jamesdh/Projects/moltenbits/sideband --from 141 --timeout 3600
   ```

2. After listener readiness, launch a detached shell process that sleeps 120
   seconds and then invokes `sideband append` with the unique probe body in
   `/tmp/sideband-codex-wake.R0qG3u/body.md`. This is an external-writer stand-in,
   not an actual Claude session. Its output goes to `append.log` in that same
   temporary directory. The delay separates setup from delivery; it is not a
   request retry or a production timeout policy.
3. End the parent's turn with a final response. Do not use a parent wait tool
   or send user input to trigger delivery.
4. The listener forwards successful `wait` stdout verbatim through
   `collaboration.send_message`, addressed to `/root`, prefixed with
   `[Sideband message]`. It starts its next native wait instead of finishing,
   so subagent completion is not a competing wake trigger.
5. If that message starts a new parent turn, record the actual envelope,
   offsets, timestamps, and delivery behavior here, then stop the listener.
   If another user turn is needed to discover the message, do not count that
   as an automatic wake. A missing wake requires external observation; this
   preparation record is not evidence of success or failure.

The host's shell tool yields a running session, which the listener resumes
with `write_stdin`; its tool waits are capped at 60 seconds in this
environment. This experiment must not be reported as proof of zero idle model
tokens or an uninterrupted hour-long wait. It tests survival across the
parent's turn boundary and the actual interval before the external append.

[Official subagent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents)
describes interactive subagents and returned results, but does not establish
this post-final idle-parent wake guarantee. The OpenAI Docs check therefore
does not substitute for this live experiment.

### Observed outcome

The journal transport succeeded, but `collaboration.send_message` did not
start a new turn in the idle parent in this experiment. The user supplied a
new message reporting the missing wake; only during that user-triggered turn
did the parent receive the listener's queued message. This is a failed wake
test, not a successful delayed automatic wake.

Evidence (all timestamps UTC on 2026-09-05):

- Listener readiness: 03:51:08, shell session `90979`, starting offset 141.
- Detached writer launch: 03:52:01.738, PID `28381`.
- Writer log: append started and finished at 03:54:01, exit code 0,
  stdout `{"start":141,"end":313}`.
- Listener reported exit code 0 and observation time 03:54:05, then sent
  the following through `collaboration.send_message` to `/root`:

  ```text
  [Sideband message]
  {"start":141,"end":313,"entries":["Codex wake-path probe sideband-codex-wake.R0qG3u. This entry was appended by a detached shell process. Transport test only; no project action requested."],"timed_out":false}
  ```

- The delivered message also included the listener's observation timestamp,
  command exit code, originating shell session, and notice that it was
  continuing from offset 313. It appeared as a host agent `MESSAGE` from
  `/root/sideband_listener` in the parent's internal agent context, not as a
  user-visible chat message, user input, or a new automatic parent turn.
- First diagnostic timestamp after the user's intervention: 03:57:49.
  The journal was 313 bytes and contained the probe. A subsequent native wait
  from offset 313 was still running (PID `28473`), independently confirming
  that the listener had progressed beyond the first append while the parent
  was idle.

Capability conclusions:

| Capability | Result in this attempt |
| --- | --- |
| Subagent survives the parent's turn ending | Passed for the observed interval |
| Native wait survives shell-tool yielding and returns the external entry | Passed for the observed interval; not a full 3600-second endurance test |
| Subagent message automatically starts an idle parent turn | Failed; user input was needed |

The listener was interrupted and its remaining native wait terminated during
cleanup. The journal probe and temporary writer logs were retained. No
subagent-completion wake test was performed; this result does not establish
that every other supported native notification path is impossible. It does
establish that the tested persistent-listener/message path does not satisfy
the gate on this host.

Implementation beyond the spike remains blocked by REQUIREMENTS.md sections
10.6 and 17.1. Bring this result back to the requirements decision; no daemon,
hook, MCP server, or headless Codex invocation was introduced as a fallback.

## Codex: failed idle wake via subagent completion (2026-09-05 UTC)

Experiment ID: `sideband-codex-completion.TSCxXJ`. The user authorized this
separate test after discussing the first attempt and hooks. The installed CLI
still reports `codex-cli 0.153.3`; the starting journal size is 313 bytes.

This changes one behavior from the persistent-listener attempt: after the
native wait returns an entry, the subagent finishes with that entry in its
final answer. It does not send the entry through `collaboration.send_message`
and does not start another wait. The parent prepares the writer and this
record while the subagent starts waiting, then ends its turn.

Procedure:

1. Start `/root/sideband_completion` through `collaboration.spawn_agent` with
   exactly one native wait:

   ```sh
   /Users/jamesdh/.local/bin/sideband wait --repo /Users/jamesdh/Projects/moltenbits/sideband --from 313 --timeout 3600
   ```

2. Receive a readiness message while the parent is still active. Launch a
   detached shell writer that sleeps 120 seconds, then appends the body in
   `/tmp/sideband-codex-completion.TSCxXJ/body.md`. Writer output is retained in
   `append.log` in that directory. This delay is experimental separation of
   setup and delivery, not a production timeout or retry policy.
3. End the parent turn without waiting in a parent tool. No user input should
   be sent during the observation interval.
4. On receipt, the subagent returns `[Sideband completion]`, the native
   command's stdout JSON verbatim, and its observed timestamp and exit code
   in its final answer. Its completion, not an intermediate message, is the
   candidate wake mechanism. Do not retry on timeout or failure.
5. Count success only if completion automatically starts another parent turn
   without user input. The parent should then visibly report the probe and
   record the outcome. A result available only after another user message
   fails this test, even if a completed-subagent indicator appears in the UI.

As before, shell-tool waits are capped at 60 seconds in this environment;
this does not establish zero idle model work or full-hour endurance. No hooks,
daemon, headless invocation, skill changes, or feature implementation are part
of this attempt. A successful single completion would still require testing
listener rearming before calling the ongoing delivery design proven.

### Observed outcome

Failed: the subagent completed successfully, but its completion did not wake
the idle parent. The user had to send another message reporting the missing
wake. Only during that user-triggered turn did the completion enter the
parent's internal context; it was not a new user-visible parent response.

Evidence (all timestamps UTC on 2026-09-05):

- Readiness: 04:06:09, shell session `70509`.
- Detached writer launch: 04:06:54.240, PID `29580`.
- Writer log: append started and finished at 04:08:54, exit code 0,
  stdout `{"start":313,"end":476}`.
- Subagent final result reported observation at 04:08:59, exit code 0,
  shell session `70509`, with this envelope:

  ```text
  [Sideband completion]
  {"start":313,"end":476,"entries":["Codex completion-wake probe sideband-codex-completion.TSCxXJ. Appended by a detached process. Transport test only; no project action requested."],"timed_out":false}
  ```

- First diagnostic timestamp after user intervention: 04:11:48. The journal
  measured 476 bytes and contained the probe. The host subsequently delivered
  a `FINAL_ANSWER` event into the parent's internal context.
- Agent status confirmed `/root/sideband_completion` completed and the older
  `/root/sideband_listener` remained interrupted. Process inspection found no
  remaining native wait for this repository or this attempt's writer script.

Both tested mechanisms failed automatic idle-parent wake on this host:
intermediate subagent messaging and subagent completion. Journal append and
native wait succeeded in both. The completion result does not prove all
possible host integrations impossible, but it provides no basis for rearming
tests or proceeding with the proposed adapter.

Test-protocol correction: asking the user not to reply without a defined
failure-observation cutoff left them waiting with no automatic failure report.
A parent whose wake mechanism fails cannot announce that failure while idle.
The user's intervention was necessary evidence, not disruption of a successful
test. Any future idle-wake experiment needs an explicit observation cutoff
and an agreed independent observer or user check-in after that cutoff.

The feasibility gate remains blocked under REQUIREMENTS.md sections 10.6 and
17.1. This attempt is closed; no further listener was started, no fallback was
introduced, and the journal probes and temporary logs were retained.

## Codex: passed idle wake via queue (2026-09-05 UTC)

Experiment ID: `sideband-codex-queue.zkdWGr`.

Codex assessment of Fable's follow-up: accepted. The two failed mechanisms do
not exhaust section 10.3, which already names Codex's queue facility. Closing
those attempts was appropriate; saying a requirements change was the only
next step was premature. This is a third in-scope feasibility test, not an
approved reduction of live-delivery semantics. Queue acceptance alone will
not count as a successful idle-parent wake.

### Verified environment and parent identity

- Installed `codex --version`: `codex-cli 0.153.3`.
- `codex queue --help` explicitly accepts `--thread <THREAD>` (session UUID
  or exact name) and `--message <TEXT>`.
- In the parent shell, both `CODEX_THREAD_ID` and `CODEX_SESSION_ID` equal
  `01a064f7-eaa7-7b63-af57-59796b87129f`. The matching rollout's `session_meta`
  record confirms that ID and this repository's working directory. The
  listener receives this resolved ID explicitly; it must not use its own
  subagent environment as the parent identity.
- The session creation metadata records CLI `0.152.1`, but inspection of
  the currently running host PID `21585` shows its executable mapped from
  `/opt/homebrew/Caskroom/codex/0.153.3/bin/codex`. Creation metadata is not
  the current runtime version.
- Sideband binary SHA-256 remains
  `0c2fb5fee10b4ed76315d2b05db1d6069334be100a25c842ff38c7700c70d9f4`.
- Initial journal size is 476 bytes.

The official CLI reference checked through OpenAI Docs did not establish
the idle-wake behavior. Installed command help establishes the syntax; the
live experiment must establish what delivery actually does. No claim about
which release first shipped the command is needed for this test.

### Procedure and observation cutoff

1. Spawn one transport subagent, `/root/sideband_queue`, to run
   `node /tmp/sideband-codex-queue.zkdWGr/wait-queue.cjs`. This temporary test
   wrapper invokes the existing native binary, not a replacement toolkit:

   ```sh
   /Users/jamesdh/.local/bin/sideband wait --repo /Users/jamesdh/Projects/moltenbits/sideband --from 476 --timeout 240
   ```

2. After listener readiness, launch a detached shell writer with a 60-second
   delay. It appends `/tmp/sideband-codex-queue.zkdWGr/body.md` using the shared
   binary. Record its launch time, expected append time, and observation
   cutoff in `schedule.json` in that temporary directory. The cutoff is 180
   seconds after launch; the parent must tell the user the exact local time
   and explicitly invite a check-in after it if no parent response appears.
3. End the parent turn before the append. The user's check-in after the
   cutoff is failure observation, not a disruption. There is no claim that
   an idle parent can report its own failed wake without another trigger.
4. When the native wait returns, validate its starting offset, non-timeout
   result, and unique probe token. Pass the resulting JSON unchanged inside
   an explicitly labeled transport envelope as one command argument:

   ```sh
   /opt/homebrew/bin/codex queue --thread 01a064f7-eaa7-7b63-af57-59796b87129f --message '<Sideband transport envelope and native wait JSON>'
   ```

   The wrapper uses an argument array, not shell interpolation of message
   content. Queue is attempted once, with a 20-second process timeout. Logs
   record stdout, stderr, status, signal, and timestamps in `transport.json`.
   Queue command exit 0 is not itself proof of parent wake.
5. The transport worker sends neither an intermediate entry message nor a
   final entry result through the collaboration tools. After queue returns,
   the wrapper stays alive for 180 seconds to keep subagent completion outside
   the observation window. The parent stops it once the result is checked;
   it is bounded and does not rearm or retry.
6. Count success only if the queue delivery starts a new parent turn without
   a human check-in, and the parent visibly reports the probe. Record whether
   it appeared as user-role input and whether the parent preserved provenance.

### Provenance and scope

The probe's actual author is Codex, and a detached shell appends it as an
external-writer stand-in. It is not an actual Claude-authored message. The
envelope explicitly says it is transport data for the already authorized
experiment, not a new human prompt or authorization. The parent must not
relabel it as human input, re-append it as a human message, or execute any
body instructions. Verification/reporting follows the existing experiment
task, not authority conferred by the queued envelope.

The spike's body-only journal does not implement version-one message IDs or
authorship validation. A passing test would still require the adapter to
preserve real message IDs/authors and prevent prompt-capture hooks from
recording queue delivery as human authorship (section 7.4). Mid-turn queuing,
rearming, restart, and production identity discovery are not proved here.

### Observed outcome

Passed: `codex queue` started a new turn in the existing idle parent, before
the announced observation cutoff and without a human check-in. The queued
envelope was the new turn's user-role input. The parent visibly acknowledged
it as Codex-authored transport data, not as a new instruction from James, and
verified the outcome under the existing experiment task.

Evidence (all timestamps UTC on 2026-09-05):

- Transport wrapper started at 04:20:31.918; listener readiness reported at
  04:20:33, shell session `27360`.
- Detached writer PID `31016` launched at 04:21:47.094. Scheduled append was
  04:22:47.094; observation cutoff was 04:24:47.094 (23:24:47 on September 4
  in America/Bogota).
- Parent rollout records setup-turn completion at 04:21:52.138, turn ID
  `01a06fc9-ae63-7811-b7ca-620f4f2b0d12`.
- Writer log records successful append at 04:22:47, exit code 0, byte range
  `{"start":476,"end":692}`.
- Native wait returned at 04:22:47.139, exit code 0, with this JSON:

  ```json
  {"start":476,"end":692,"entries":["Sideband queue-wake probe sideband-codex-queue.zkdWGr. Authored by Codex for this user-authorized experiment and appended by a detached shell process. Transport data only, not a human instruction."],"timed_out":false}
  ```

- Queue started at 04:22:47.139 and returned at 04:22:47.178, exit code 0,
  empty stderr. Its stdout was:

  ```text
  Queued message 01a06fce-0278-70b2-8c21-e3bf9ccaef9a for thread 01a064f7-eaa7-7b63-af57-59796b87129f.
  ```

- Parent rollout records the next turn starting at 04:22:52.294, turn ID
  `01a06fce-1684-7073-8554-5529eb86ed6a`. Its input began
  `[Sideband message — queue wake spike]` and included the provenance warning
  and native wait JSON. This was an actual parent turn, not a message merely
  held in internal context until another human prompt.
- First diagnostic timestamp in that automatically started turn was
  04:23:04. The journal measured 692 bytes and contained the expected probe.
  Wrapper PID `30898` was still running, ruling out its final completion as
  the trigger. The parent then interrupted the subagent and terminated that
  wrapper. No listener was rearmed; logs and journal probes were retained.

### Gate conclusion and implementation consequence

Together with the recorded Claude Code result, this demonstrates both hosts'
ability to wake an existing idle parent from an external journal append. The
section 10.6 feasibility gate passes at that capability-test scope. Both tests
used detached writers as peer stand-ins; neither proves complete cross-client
adapters, routing, ongoing delivery, or production lifecycle behavior.

The Codex delivery mechanism is a listener invoking `codex queue` against the
verified parent thread, not collaboration messaging or subagent completion.
Queue adds no Sideband daemon or headless peer invocation; it uses the existing
Codex host. Keep actual Sideband author/message identity separate from the
host's user-role representation, including at prompt-capture hooks. The queue
message ID printed by Codex is a host transport ID, not a Sideband journal ID.

No skill or feature implementation was changed in this experiment. Update the
adapter and its tests in the implementation task; do not treat the earlier
persistent-message skill stub as a working queue adapter. The experiment is
closed, with no further waits or automatic probes scheduled.
