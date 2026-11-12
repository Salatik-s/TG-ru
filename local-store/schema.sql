CREATE TABLE accounts (
  id TEXT PRIMARY KEY,
  login TEXT NOT NULL UNIQUE,
  password_salt BLOB NOT NULL CHECK(length(password_salt) = 16),
  password_hash BLOB NOT NULL CHECK(length(password_hash) = 32),
  recovery_hash BLOB NOT NULL UNIQUE CHECK(length(recovery_hash) = 32),
  created_at INTEGER NOT NULL,
  trial_started_at INTEGER,
  paid_until INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE TABLE sessions (
  token_hash BLOB PRIMARY KEY CHECK(length(token_hash) = 32),
  account_id TEXT REFERENCES accounts(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL
) STRICT;

CREATE TABLE mock_orders (
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  request_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('paid', 'canceled')),
  paid_until INTEGER NOT NULL,
  PRIMARY KEY(account_id, request_id)
) STRICT;

CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  public_key BLOB NOT NULL UNIQUE,
  name TEXT NOT NULL,
  activated_at INTEGER NOT NULL,
  revoked_at INTEGER
) STRICT;

CREATE INDEX active_devices ON devices(account_id) WHERE revoked_at IS NULL;

CREATE TABLE challenges (
  token_hash BLOB PRIMARY KEY CHECK(length(token_hash) = 32),
  action TEXT NOT NULL CHECK(action IN ('activate', 'renew')),
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  nonce TEXT NOT NULL UNIQUE,
  expires_at INTEGER NOT NULL
) STRICT;
