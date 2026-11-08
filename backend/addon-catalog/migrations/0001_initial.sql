CREATE TABLE IF NOT EXISTS addons (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS catalog_meta (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    revision INTEGER NOT NULL
);

INSERT OR IGNORE INTO catalog_meta (singleton, revision) VALUES (1, 1);

CREATE TABLE IF NOT EXISTS devices (
    token_hash TEXT PRIMARY KEY NOT NULL,
    created_at INTEGER NOT NULL,
    revoked_at INTEGER
);

CREATE INDEX IF NOT EXISTS devices_active_idx ON devices(revoked_at);
