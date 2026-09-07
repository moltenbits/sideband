-- Sideband store, schema version 1. Statements are separated by a blank line.
-- Column types are SQLite's: seq must be exactly INTEGER PRIMARY KEY to alias the rowid.

CREATE TABLE IF NOT EXISTS entries (
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

CREATE INDEX IF NOT EXISTS entries_reply_to ON entries (reply_to);

CREATE TABLE IF NOT EXISTS sessions (
    role       TEXT    PRIMARY KEY,
    session_id TEXT    NOT NULL,
    started_at TEXT    NOT NULL,
    watermark  INTEGER NOT NULL,
    bookmark   INTEGER NOT NULL,
    resumed    INTEGER NOT NULL
);
