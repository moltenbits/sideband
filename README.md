# sideband

Lets Claude Code and Codex work together on one task, in their own sessions,
with the human in the loop. The agents talk through an append-only journal
in `~/.sideband`, one for every project on the machine with each project's
discussion kept apart, and an agent in one project can reach one in another:
one delegates, the other answers, and each is woken in its own conversation when something arrives. One
native executable owns the protocol; each client gets a thin skill that tells
it when to call the executable and what to do with the result.

Nothing leaves the machine. There is no daemon, no server, no MCP bridge, and
no interpreter: `sideband` is a Micronaut application compiled to a GraalVM
native image, and everything a client runs is a subcommand of it.

- [1. Install](#1-install)
  - [1.1 Sideband](#11-sideband)
  - [1.2 Claude Code](#12-claude-code)
  - [1.3 Codex](#13-codex)
  - [1.4 Notifications](#14-notifications)
- [2. Use](#2-use)
  - [2.1 Claude Code](#21-claude-code)
  - [2.2 Codex](#22-codex)
  - [2.3 Talk](#23-talk)
  - [2.4 Several sessions of one client](#24-several-sessions-of-one-client)
  - [2.5 Other projects](#25-other-projects)
- [3. How it works](#3-how-it-works)
  - [3.1 The journal](#31-the-journal)
  - [3.2 Claude Code](#32-claude-code)
  - [3.3 Codex](#33-codex)
  - [3.4 What the hooks record](#34-what-the-hooks-record)
- [4. Development](#4-development)
- [5. License](#5-license)

## 1. Install

### 1.1 Sideband

From the moltenbits Homebrew tap, on Apple silicon or on Linux (x86_64 or arm64):

```bash
brew tap moltenbits/tap
brew install sideband
```

Or from source, which needs a GraalVM JDK with `native-image` and
[just](https://github.com/casey/just):

```bash
just install          # builds the native executable and puts it on PATH
```

Set up each project. `init` creates the private state directory and its
database the first time, registers the project under its directory's name,
installs both skills, registers the hooks, and prints what to do next;
rerunning it is safe. A project is a Git repository, all its worktrees
included, or a plain directory:

```bash
cd <your project>
sideband init
```

Another project already called that, or a directory name with nothing usable
in it, needs a name of its own, which then stays the project's name:

```bash
sideband init as api-two
```

Check the result at any time. `doctor` reports the database, every
instance's session, and every client item, and says where anything still needs your
attention:

```bash
sideband doctor
```

### 1.2 Claude Code

Let Claude Code accept pushes from Sideband, or Claude falls back to
listening. In Claude Code this is `/config`, "Messages from your other
sessions"; the same setting in `~/.claude/settings.json` is:

```json
{ "crossSessionInbound": "accept" }
```

Nothing else is needed: `init` installed the skill under
`~/.claude/skills/sideband` and the hooks in the repository's
`.claude/settings.local.json`, your own settings for the repository, so
teammates who do not use Sideband do not run them.

### 1.3 Codex

Trust the hooks that `init` registered in the repository's
`.codex/hooks.json`, and again whenever a later `init` reports one as added
or updated:

```text
/hooks
```

Then `$sideband` joins and records this thread as Codex's delivery
address (section 2.2); after a later trust or a clear, an ordinary prompt
refreshes it. The skill is already installed under
`~/.agents/skills/sideband`.

### 1.4 Notifications

Optional. If a hook in either client raises a desktop notification when a
turn ends, for example [growlrrr](https://github.com/moltenbits/growlrrr)'s
`grrr hook notify`, Sideband will make it ring at the end of every exchange
between the two agents, because a delivered envelope starts a turn exactly
as a typed prompt does. growlrrr can consult a gate before it rings, and
`sideband hook notify` is that gate: it reads the hook payload and exits 0
to let the notification through or 1 to hold it. Generate the hooks with
the gate on them and merge the output into each client's settings as
growlrrr's own instructions say:

```bash
grrr init --format claude --appId Sideband --gate "sideband hook notify"
grrr init --format codex  --appId Sideband --gate "sideband hook notify"
```

The gate decides from the journal. The client you last typed into is done
when no request to a client is still waiting for an answer: while Claude
waits on Codex's review, neither turn end rings, and a follow-up question
you type meanwhile changes nothing; when Codex's reply wakes Claude and
Claude's turn then ends with nothing open, it rings once, and clicking the
notification lands in Claude's terminal. An agent's request is closed by an
answer or by that agent's next request to the same client, so an abandoned
one never holds a notification back. What an agent says to you in its own
terminal is recorded for every turn (section 3.4), so it is not a reason to
ring: an agent that stops to ask you something while the other client's work
is still open does not ring for it yet (#27). Permission prompts always ring, the idle reminder that
repeats a turn end never does, and on the dismiss hook a delivered envelope
never clears a notification you have not seen.

Used alone, either client behaves as it did before: a session that has not
joined Sideband, a repository without it, or an event the gate does not
know all pass straight through to the notifier.

## 2. Use

### 2.1 Claude Code

In the Claude Code session working on the project, join:

```text
/sideband
```

### 2.2 Codex

In the Codex session working on the same project, join:

```text
$sideband
```

### 2.3 Talk

Once both have joined, hand out work from either session by starting a
prompt with the agent's name. The prompt is journaled as your words, the
agent it names is woken in its own session, and from there the two delegate
to each other and reply on their own; `sideband log` shows the discussion,
newest first, and `sideband log --after 0` all of it from the start.

Give Claude the task and make Codex the reviewer, one commit at a time:

```text
@claude fix #42, the upload retry loop. Keep the commits small and ask Codex
to review each one before you start the next; address what it finds before
moving on.
```

Give Codex the lead on a job for both of them, reviewing each other as they go:

```text
@codex split the settings migration with Claude: you take the reader, Claude
takes the writer. Agree the interface first, then review each other's
commits as you both work along and tell me when it is done.
```

Everything you type on either side keeps being journaled, and so does
everything each agent says back to you, so a later prompt can redirect
either agent, `@all` can speak to both at once, and either agent can read in
`sideband log` what you discussed with the other.

### 2.4 Several sessions of one client

More than one Claude Code or Codex session can take part in the same
discussion, say one Claude running Opus and another running Fable. Every
session has a name: the first of each client joins as usual and takes the
client's own, `claude` or `codex`; every further one joins under a name of
its own:

```text
/sideband as fable
```

That session is now `fable`, with its own bookmark and its own pending work,
and `@fable` at the start of a prompt reaches it. `@claude` still reaches
only the session called `claude`, and `@all` reaches every session that has
joined in the project. An agent's reply goes back to the session that asked.
A name is a lowercase letter followed by up to 31 lowercase letters, digits,
and hyphens; `all` and `operator` are taken. The name says nothing about the
client: a Codex session can be `fable` as well.

Joining under a name another session holds takes the name over, whichever
client that session is, and the join says which session it replaced. Claude
Code tells Sideband which process every session runs in, so a second Claude
Code terminal that has not joined is never folded into an instance whose
client is still running elsewhere: its prompts wait for its own `/sideband`
or `/sideband as <name>`, and a cleared conversation keeps its instance. Codex offers no such signal. While
one Codex instance is joined, Codex behaves as it always has: a cleared or
second Codex terminal takes the instance over with its first prompt, so join
a second Codex under a name before typing into it. With two or more Codex
instances joined, a cleared conversation is told which instances it could
be, and `$sideband` or `$sideband as <name>` settles it.

### 2.5 Other projects

Names are per project, so `astra` in `sideband` and `astra` in `listenly`
are two sessions. Within a project you address a session by its name;
another project's needs the project's name first:

```text
listenly/@opus what does the export endpoint return for an empty account?
```

An agent does the same with `sideband append --to listenly/opus`, and the
answer comes back to whoever asked, in whichever project. Each project's
`sideband log` shows its own discussion, exchanges with other projects
included, and nothing else. A project name nobody registered goes nowhere:
your prompt is still recorded where you typed it, and the agent is told the
route failed and to say so rather than act on it.

## 3. How it works

- **Agents delegate to each other and reply.** Claude asks Codex to review
  a change, Codex asks Claude to explain a design, either reports status.
  Requests, replies, and status notes are all journal entries of one shape,
  and a reply is correlated with the request it answers.
- **Every actionable chain traces to the human.** A human and an agent write
  the same kind of entry, a request. An agent's actionable request must link,
  through the requests and replies before it, to something the human asked;
  the executable refuses one that does not. Within that, one agent may direct
  the other's work for as long as the human's request stands.
- **Delivery wakes the idle recipient** in its existing conversation, without
  any model tokens spent waiting. Each client is reached the way its host
  allows, described in 3.2 and 3.3.
- **The human is a participant, not a transport.** Every prompt typed into
  either client is journaled verbatim and attributed to the human, and what
  each agent says back to the human in its own terminal is journaled by the
  client's hooks (3.4), so the journal holds the whole discussion without
  any agent having to record it. Normally
  each agent is spoken to in its own session; `@codex` at the start of a
  prompt typed into Claude Code routes it to Codex anyway, `@claude` does the
  reverse, `@fable` reaches a session by its name (2.4), `listenly/@opus`
  one in another project (2.5), and `@all` every session that has joined in
  the project.
- **Nothing is lost, and nothing is tracked outside the journal.** A request
  stays listed for its recipient until the journal holds that recipient's
  acknowledgement or reply, and listed for its sender until a reply exists.
  Requests that arrived while a client was away are confirmed with the human
  before any action. The only thing kept beside the journal is each
  instance's session record: how far it has read, which conversation and, for
  Claude Code, which process it is, and for Codex the thread to push into.
  Whoever joins under a name last holds it, from either client.

### 3.1 The journal

`~/.sideband/journal.db` is one SQLite database holding every registered
project, every entry, and every session's record. An entry is its metadata
plus the verbatim body, with each participant named `<project>/<name>`, and
`sideband log` prints the current project's discussion as Markdown for a
person to read, naming its own sessions by name and another project's as
`listenly/@opus`:

```markdown
## Operator → codex (via claude)

- position: 12
- id: …
- created: 2026-09-07T18:07:52-05:00
- type: request (expects a reply)
- to: sideband/codex

@codex review the locking behavior.

---
```

`sideband log` prints the newest 20 entries, newest first, and ends with the
command for the page before them, `sideband log --before <position>`;
`--limit <n>` sets the page size. `--after <position>` reads forward instead,
oldest first, so `sideband log --after 0` prints the whole discussion.
`sideband log --id <id>` prints one entry, and `sideband log --trace <id>`
prints an entry and the entries it came from, both from the whole journal, following `caused_by`,
otherwise `reply_to`, back to one with neither: for a request one agent sent
the other, that is the prompt of yours it started from, and everything said
between.

Entries are immutable and inserted in transactions that SQLite serializes
across processes, so two clients writing at once never interleave. Positions
are the addressing scheme: each entry gets the next sequence number, a session
records the last position when it joins as its watermark, and everything
after it is live.

Earlier versions kept a journal per repository, under `.git/sideband`, or in
a `.sideband` directory of a plain directory; run from your home directory,
that was `~/.sideband/sideband.db`, which is why the journal is called
`journal.db`. Nothing reads those old journals, and they can be deleted.

### 3.2 Claude Code

`init` installs the skill under `~/.claude/skills/sideband` and registers
the hooks in the repository's `.claude/settings.local.json`: the prompt hook,
the session-start hook, and the capture hook on `Stop` and `SessionEnd`
(3.4). The hooks keep each
instance's record on the conversation you are in, and the writer reaches that
conversation through the registry Claude Code keeps: Claude Code registers
every session in `~/.claude/sessions/<pid>.json` with its working directory
and an inbox socket, the channel its own cross-session messaging uses. The
writer considers the registered sessions working in the recipient's project,
worktrees included, and posts the envelope to one socket as one
newline-terminated frame. The recipient is the session the instance's record
names, by session id or, right after a clear, by process; an entry for a name
nobody has joined under waits in the journal until someone does. A socket
that refuses the connection belongs to a session that has ended, and the
entry then waits in the journal.

Claude Code introduces everything on that socket to the model as a message
from another Claude session; no frame can change that. The frame does carry
the shape Claude Code's own cross-session messaging uses, a
`<cross-session-message>` tag whose `from-name` Claude Code parses into the
message's origin, so the envelope arrives named for its author as the
recipient's project sees it, `codex`, `listenly/@opus`, or the operator,
rather than an anonymous session.

One setting is yours to make (section 1.2), and without it Claude falls
back to listening. A pushed envelope reaches Claude Code from a process that
is not the session's own child, and a session run with bypass permissions
holds such a message for your approval unless `crossSessionInbound` is
`accept` in your user settings, `~/.claude/settings.json` (or `/config`,
"Messages from your other sessions"). Claude Code lets a repository's
`.claude/settings.json` and `.claude/settings.local.json` only tighten that
value, so `init` does not write it; `doctor` reports whether pushes will be
delivered, held, or refused, names the file that decided, and says where
accept must go. Managed settings and `--settings`, which it cannot read, take
the place of your user file as the base; a repository's tightening still
applies over them.

The socket is used whenever it can be, and the executable falls back to a
listener when it cannot. Each time an entry for Claude is appended, the
executable checks your settings: if they accept, it posts the frame; if not,
it posts nothing and reports that Claude's listener delivers. The Claude
skill makes the same check when it activates, through `sideband doctor`, and
starts a listener only in the second case: one persistent Monitor on
`sideband pending --wait --stream`, a native process that blocks on the
journal and prints one line per batch of new entries; the host turns each
line into a notification, and Claude then reads the entries with
`sideband pending`. Nothing is configured for this beyond your settings, and
changing them takes effect the next time `/sideband` runs.

### 3.3 Codex

`init` installs the skill under `~/.agents/skills/sideband` and registers
the hooks in the repository's `.codex/hooks.json`: the prompt hook, the
session-start hook, and the capture hook on `Stop`, `SessionEnd`, and
`Interrupt` (3.4). Codex runs a hook only
once you have trusted it through `/hooks` (section 1.3), and again whenever
`init` adds or changes a definition: Codex binds trust to each definition's
hash and skips an untrusted definition, while an unchanged, already trusted
one keeps running. Codex warns at startup when hooks need review, but
`doctor` checks registration, not host trust, so a skipped hook looks
installed to it. It lists every hook under its file with what it runs; a
hook running another command, such as a launcher script, is `custom`, and
only a `missing` or `broken` one needs `init`. `init` points every Sideband
hook in both files at itself, so when one needs it `doctor` also says how many
custom ones it would replace or remove; your other hooks are left alone. The trust entries land in your Codex `config.toml`.

Codex has no session registry. It records its thread id when it joins, and
the writer pushes the envelope into that thread with `codex queue`. That
address follows you: `/clear` in Codex starts a new thread and leaves the old
one loaded, where a queued envelope would run unseen, so the hooks move the
instance to the new thread: the session-start hook when Codex runs it for the
clear, and the prompt hook whenever a prompt you type comes from a thread
other than the recorded one. Only your own input moves it; a delivered
envelope never does. That holds while one Codex instance is joined; with
several, the hooks cannot tell which one a new thread continues, move none,
and tell the new conversation to join again (section 2.4). One window remains. Codex runs both hooks only when you
submit your first prompt in the new thread, not at the clear itself, and in
the tested Codex 0.153.4 TUI setup Sideband has no supported way to identify
the thread on screen during that window. So **after `/clear` in Codex, type
one prompt before expecting delivery**. An entry pushed in between can be
handled by the old thread and its reply recorded in the Sideband discussion
without appearing in the new conversation. `pending` lists unanswered work
and unread incoming updates; it does not replay Codex's completed replies.

A queued envelope waits for the turn Codex is running to end, so a review
that lands mid-implementation surfaces when that turn finishes. `pending`
inside the turn shows it sooner, and then marks it `pushed_at`, so the
envelope that follows is recognized as the same entry rather than a new one.

### 3.4 What the hooks record

The journal holds the discussion as it appeared in each terminal, and the
hooks write all of it, so no agent has to remember to:

- **What you type**, recorded by the prompt hook before the model sees it.
  Slash commands, `!` shell commands, and a subagent's prompts are not
  your words and are left out.
- **What each agent says to you**, recorded by the capture hook from the
  client's own transcript: its final answers and anything it says before
  going on to do more work, in order, one entry for each stretch of a turn
  read at once (usually the whole turn), linked to the prompt that started
  the turn. Reasoning, tool calls, and tool output are left out. In
  `sideband log` such an entry is headed `Claude → Operator`.

The capture hook runs when a turn ends, before your next prompt is recorded
(so a turn you interrupted lands before the prompt that followed it), when
Codex is interrupted, and when the session ends. It reads the transcript from
where it last stopped, so a message is recorded once however often the hook
fires. Only a session that has joined is recorded, from the turn it joined
in, or the turn whose prompt the join took up; what was said before that
stays out.

Both transcript formats are undocumented, and what is read from them was
measured on Claude Code 2.1.296 and Codex 0.162.1. Some words can arrive late
or not at all:

- A final answer the host has not written within two seconds of the turn's
  end is recorded by the next capture in that session, and is lost if none
  follows, as when the session is killed. The same holds for a Claude Code
  turn you interrupt, which runs no turn-end hook.
- When a turn ends with its transcript unreadable and nothing of that turn
  recorded yet, the hook records the final answer the host hands it instead,
  and nothing more of that turn, ever. A turn already partly recorded waits
  for the transcript instead.

REQUIREMENTS.md section 14.16a has the full contract, including how a
transcript copied or rewritten elsewhere is read again without recording
twice. An agent asking you a question no longer makes the notifier ring for
it (section 1.4); see #27.

## 4. Development

```bash
just test             # Spock suite on the JVM
just check            # tests plus the native build, the pre-commit gate
just run doctor       # run any command on the JVM without a native build
```

## 5. License

[MIT](LICENSE).
