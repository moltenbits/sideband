-- Which entries a role's host accepted for the session that held the role at the time.
-- The writer records a push the host took, so a later pending read can say the entry
-- is also on its way into that conversation. Nothing here says the model read it; the
-- journal, through the role's own acks and replies, is the only record of that.

CREATE TABLE deliveries (
    id         INTEGER PRIMARY KEY,
    seq        INTEGER NOT NULL,
    role       TEXT    NOT NULL,
    session_id TEXT    NOT NULL,
    pushed_at  TEXT    NOT NULL,
    UNIQUE (seq, role)
);

PRAGMA user_version = 3;
