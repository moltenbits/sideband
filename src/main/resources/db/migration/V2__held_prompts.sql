-- A prompt the operator typed into a client session before its role had joined here,
-- kept verbatim for join to adopt. At most one per role: the latest such prompt.

CREATE TABLE held_prompts (
    role       TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    prompt     TEXT NOT NULL
);

PRAGMA user_version = 2;
