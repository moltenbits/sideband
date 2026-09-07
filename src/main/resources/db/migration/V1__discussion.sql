-- The discussion: every entry ever written, and each role's session record.
-- seq must be exactly INTEGER PRIMARY KEY so it aliases SQLite's rowid.

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

CREATE TABLE sessions (
    role       TEXT    PRIMARY KEY,
    session_id TEXT    NOT NULL,
    started_at TEXT    NOT NULL,
    watermark  INTEGER NOT NULL,
    bookmark   INTEGER NOT NULL,
    resumed    INTEGER NOT NULL
);

-- The version the executable checks before every unit of work, so an up-to-date
-- database costs one pragma and never a Flyway run. Each migration sets it to its own number.
PRAGMA user_version = 1;
