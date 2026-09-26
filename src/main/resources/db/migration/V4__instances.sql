-- Instances (REQUIREMENTS.md 9.5a). A session record and a delivery belong to a participant,
-- `claude` or `claude:fable`, rather than a role; the bare role names already in these
-- columns are the unnamed instances, so the rows keep their meaning. A record names its
-- host process, the id and start time, when the host gives one. A held prompt belongs to
-- the session it was typed into, since several sessions of a role may be waiting to join;
-- the two clients' session identifiers are separate namespaces, so the key is both.

ALTER TABLE sessions RENAME COLUMN role TO participant;
ALTER TABLE sessions ADD COLUMN host_pid INTEGER;
ALTER TABLE sessions ADD COLUMN host_started_at TEXT;

ALTER TABLE deliveries RENAME COLUMN role TO participant;

CREATE TABLE held_prompts_by_session (
    id         INTEGER PRIMARY KEY,
    role       TEXT NOT NULL,
    session_id TEXT NOT NULL,
    prompt     TEXT NOT NULL,
    UNIQUE (role, session_id)
);
INSERT INTO held_prompts_by_session (role, session_id, prompt) SELECT role, session_id, prompt FROM held_prompts;
DROP TABLE held_prompts;
ALTER TABLE held_prompts_by_session RENAME TO held_prompts;

PRAGMA user_version = 4;
