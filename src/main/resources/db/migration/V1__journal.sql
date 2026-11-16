-- The journal in the home directory (#32): every project's discussion, the projects
-- themselves, and each instance's session record. A participant is <project>/<name> or the
-- operator, so entries, session records, and deliveries are scoped by the identifiers they
-- hold. The journals earlier versions kept in each repository are never read, so this schema
-- starts here.

-- Every registered project: the name its agents are addressed by and where it is, each unique.
CREATE TABLE projects (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    path TEXT NOT NULL UNIQUE,
    root TEXT NOT NULL
);

-- Every entry ever written. seq must be exactly INTEGER PRIMARY KEY so it aliases SQLite's rowid.
CREATE TABLE entries (
    seq           INTEGER PRIMARY KEY,
    id            TEXT    NOT NULL UNIQUE,
    created_at    TEXT    NOT NULL,
    sender        TEXT    NOT NULL,
    via           TEXT,
    recipients    TEXT    NOT NULL,
    type          TEXT    NOT NULL,
    route         TEXT    NOT NULL,
    reply_to      TEXT,
    caused_by     TEXT,
    expects_reply INTEGER NOT NULL,
    live          TEXT    NOT NULL,
    backlog       TEXT    NOT NULL,
    body          TEXT    NOT NULL
);

CREATE INDEX entries_reply_to ON entries (reply_to);

-- Which projects each entry involves: every project its author, via, and recipients belong
-- to, derived from those columns as the entry is inserted, so a project's log pages by an index.
CREATE TABLE entry_projects (
    id      INTEGER PRIMARY KEY,
    project TEXT    NOT NULL,
    seq     INTEGER NOT NULL,
    UNIQUE (project, seq)
);

-- Each instance's record: the client it runs in, its host session and process, and how far
-- into the journal it has read.
CREATE TABLE sessions (
    participant     TEXT    PRIMARY KEY,
    client          TEXT    NOT NULL,
    session_id      TEXT    NOT NULL,
    started_at      TEXT    NOT NULL,
    watermark       INTEGER NOT NULL,
    bookmark        INTEGER NOT NULL,
    resumed         INTEGER NOT NULL,
    host_pid        INTEGER,
    host_started_at TEXT
);

-- Which entries an instance's host accepted for the session that held it at the time. The
-- writer records a push the host took, so a later pending read can say the entry is also on
-- its way into that conversation. Nothing here says the model read it; the journal, through
-- the instance's own acks and replies, is the only record of that.
CREATE TABLE deliveries (
    id          INTEGER PRIMARY KEY,
    seq         INTEGER NOT NULL,
    participant TEXT    NOT NULL,
    session_id  TEXT    NOT NULL,
    pushed_at   TEXT    NOT NULL,
    UNIQUE (seq, participant)
);

-- A prompt the operator typed into a client session before it had joined in the project,
-- kept verbatim for join to adopt, with the turn it was typed in, so the adopted entry becomes
-- that turn's cause. At most one per project, client, and session: the latest such prompt.
-- The two clients' session identifiers are separate namespaces, and a host session may work
-- in more than one project, so the key is all three.
CREATE TABLE held_prompts (
    id         INTEGER PRIMARY KEY,
    project    TEXT NOT NULL,
    role       TEXT NOT NULL,
    session_id TEXT NOT NULL,
    prompt     TEXT NOT NULL,
    turn_id    TEXT,
    UNIQUE (project, role, session_id)
);

-- What the hooks have recorded of each client session's own words in a project
-- (REQUIREMENTS.md 14.16a). A session's cursor is where reading its transcript stopped, at a
-- line end, and inside which turn; its version advances with every change to the cursor or to
-- any of the session's turns, so a capture that read the state commits only if nothing changed
-- since. A turn's row is its receipt: the prompt that started it, how its words were recorded,
-- and whether its final message was. Receipts are kept, so a late or replayed hook finds the
-- turn as it was left. Each message recorded from a transcript is kept by the identity its host
-- gave it, which survives the transcript being copied or rewritten, so reading a turn again
-- records only what was not recorded before.
CREATE TABLE capture_cursors (
    id         INTEGER PRIMARY KEY,
    project    TEXT NOT NULL,
    role       TEXT NOT NULL,
    session_id TEXT NOT NULL,
    transcript TEXT,
    position   INTEGER,
    turn_id    TEXT,
    version    INTEGER NOT NULL,
    UNIQUE (project, role, session_id)
);

CREATE TABLE captured_turns (
    id              INTEGER PRIMARY KEY,
    project         TEXT NOT NULL,
    role            TEXT NOT NULL,
    session_id      TEXT NOT NULL,
    turn_id         TEXT NOT NULL,
    prompt_entry_id TEXT,
    mode            TEXT NOT NULL,
    final_captured  INTEGER NOT NULL,
    UNIQUE (project, role, session_id, turn_id)
);

CREATE TABLE captured_messages (
    id         INTEGER PRIMARY KEY,
    project    TEXT NOT NULL,
    role       TEXT NOT NULL,
    session_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    UNIQUE (project, role, session_id, message_id)
);

-- The version the executable checks before every unit of work, so an up-to-date database
-- costs one pragma and never a Flyway run. Each migration sets it to its own number.
PRAGMA user_version = 1;
