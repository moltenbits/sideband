-- What the hooks have recorded of each client session's own words (REQUIREMENTS.md 14.16a).
-- A session's cursor is where reading its transcript stopped, at a line end, and inside
-- which turn; its version advances with every change to the cursor or to any of the
-- session's turns, so a capture that read the state commits only if nothing changed since.
-- A turn's row is its receipt: the prompt that started it, how its words were recorded,
-- and whether its final message was. Receipts are kept, so a late or replayed hook finds
-- the turn as it was left. Each message recorded from a transcript is kept by the
-- identity its host gave it, which survives the transcript being copied or rewritten, so
-- reading a turn again records only what was not recorded before.

-- A prompt held for a session's join keeps the turn it was typed in, so the adopted entry
-- becomes that turn's cause.

ALTER TABLE held_prompts ADD COLUMN turn_id TEXT;

CREATE TABLE capture_cursors (
    id         INTEGER PRIMARY KEY,
    role       TEXT NOT NULL,
    session_id TEXT NOT NULL,
    transcript TEXT,
    position   INTEGER,
    turn_id    TEXT,
    version    INTEGER NOT NULL,
    UNIQUE (role, session_id)
);

CREATE TABLE captured_turns (
    id              INTEGER PRIMARY KEY,
    role            TEXT NOT NULL,
    session_id      TEXT NOT NULL,
    turn_id         TEXT NOT NULL,
    prompt_entry_id TEXT,
    mode            TEXT NOT NULL,
    final_captured  INTEGER NOT NULL,
    UNIQUE (role, session_id, turn_id)
);

CREATE TABLE captured_messages (
    id         INTEGER PRIMARY KEY,
    role       TEXT NOT NULL,
    session_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    UNIQUE (role, session_id, message_id)
);

PRAGMA user_version = 5;
