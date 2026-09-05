# One bounded Codex–Claude handshake

Use only when the human has authorized this exact test. Read the parent task's
ownership restrictions and retain its authorization entry H, the startup
watermark, cutoff, received IDs, and sent IDs in the experiment record. Read
entries only through the shared executable. Prior spike diagnostics may be
ignored only when explicitly waived by the human.

For Codex, the entire permitted sequence is:

1. Once its single listener is confirmed running from N, append one
   non-actionable `status` to `claude`: `listener armed from offset N`.
2. Accept exactly one Claude `request` whose `caused_by` is H and body is
   `ping: reply with one line confirming receipt, then stop.` Confirm H is
   the human-authorized test root through the native startup scan. Reply once
   to `claude` using `--type reply --reply-to <Claude-request-id>` with one
   line confirming receipt. Save the returned ID; never resend it.
3. Append exactly one request to `claude`, `--caused-by H`, with the same ping
   body. This request is separately initiated by the human's test instruction,
   not a delegation of Claude's ping. Save its ID and return control; the
   existing listener delivers its answer.
4. When Claude's non-actionable reply names that request in `reply_to`, present
   it as Claude-authored context. Append one final `reply` to `human:james`,
   `--reply-to H`, summarizing what actually passed, and show the result here.
   Stop the listener and record the outcome. Do not send another peer message.

Caps: **one Codex request, two Codex replies** total, including the final human
summary. All use native defaults for `expects_reply`. No branching or retries.
The armed status is not a request. A duplicate ID is ignored, not another
handshake step. A peer's armed status is informational. Ignore entries that do
not address Codex, including Claude's human-only final summary.

If an unexpected addressed entry arrives, append at most one non-actionable
status to `human:james` describing it, then stop. Do not answer its request,
begin another handshake or exceed the caps. An append with uncertain outcome
must not be retried. A failure or the observation cutoff ends the experiment,
not the durable pending request; report what was not proved. Human interruption
may always stop or revise the test.

The listener has a maximum one-hour observation window and eight delivered
batches, with each wait at most 3600 seconds. Rearming a timed-out transport
wait is silent process work, not retrying a sent request. The parent must not
stay in a wait loop. If its wake fails, logs and the next human turn are the
observation path; do not claim the idle model can report its own failure.
