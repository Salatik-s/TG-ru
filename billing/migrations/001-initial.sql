-- All timestamps are Unix seconds. Hashes are stored as binary digests.
CREATE TABLE accounts (
    id TEXT PRIMARY KEY,
    login TEXT NOT NULL UNIQUE
        CHECK (length(login) BETWEEN 3 AND 24 AND instr(login, char(0)) = 0
               AND login NOT GLOB '*[^A-Za-z0-9_]*'),
    password_hash TEXT NOT NULL CHECK (password_hash GLOB '$argon2id$?*'),
    email_enc BLOB,
    entitlement_id BLOB NOT NULL UNIQUE CHECK (length(entitlement_id) = 16),
    created_at INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'blocked')),
    deleted_at INTEGER
) STRICT;

CREATE TABLE sessions (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    token_hash BLOB NOT NULL UNIQUE CHECK (length(token_hash) = 32),
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at BETWEEN created_at + 1 AND created_at + 1209600),
    user_agent_short TEXT NOT NULL CHECK (length(user_agent_short) <= 100)
) STRICT;

CREATE INDEX sessions_account ON sessions(account_id);
CREATE INDEX sessions_expiry ON sessions(expires_at);

CREATE TABLE recovery_tokens (
    token_hash BLOB PRIMARY KEY CHECK (length(token_hash) = 32),
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL,
    used_at INTEGER
) STRICT;

CREATE INDEX recovery_tokens_account ON recovery_tokens(account_id);
CREATE INDEX recovery_tokens_expiry ON recovery_tokens(expires_at);

CREATE TABLE device_links (
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_key_hash BLOB NOT NULL CHECK (length(device_key_hash) = 32),
    label TEXT NOT NULL,
    linked_at INTEGER NOT NULL,
    revoked_at INTEGER,
    PRIMARY KEY (account_id, device_key_hash)
) STRICT;

CREATE INDEX active_device_links ON device_links(account_id) WHERE revoked_at IS NULL;

CREATE TRIGGER device_limit_on_insert
BEFORE INSERT ON device_links
WHEN NEW.revoked_at IS NULL
  AND (SELECT count(*) FROM device_links
       WHERE account_id = NEW.account_id AND revoked_at IS NULL
         AND device_key_hash <> NEW.device_key_hash) >= 3
BEGIN
    SELECT RAISE(ABORT, 'device_limit');
END;

CREATE TRIGGER device_limit_on_update
BEFORE UPDATE OF account_id, revoked_at ON device_links
WHEN NEW.revoked_at IS NULL AND (OLD.revoked_at IS NOT NULL OR NEW.account_id <> OLD.account_id)
  AND (SELECT count(*) FROM device_links
       WHERE account_id = NEW.account_id AND revoked_at IS NULL) >= 3
BEGIN
    SELECT RAISE(ABORT, 'device_limit');
END;

CREATE TABLE payments (
    id TEXT PRIMARY KEY,
    account_id TEXT REFERENCES accounts(id) ON DELETE SET NULL,
    provider TEXT NOT NULL CHECK (provider = 'platega'),
    provider_tx_id TEXT UNIQUE,
    amount_kop INTEGER NOT NULL CHECK (amount_kop > 0),
    currency TEXT NOT NULL CHECK (currency = 'RUB'),
    status TEXT NOT NULL CHECK (status IN ('created', 'pending', 'paid', 'failed', 'canceled', 'refunded', 'disputed')),
    created_at INTEGER NOT NULL,
    paid_at INTEGER,
    raw_status_hash BLOB CHECK (length(raw_status_hash) = 32),
    UNIQUE (account_id, id)
) STRICT;

CREATE INDEX payments_status_created ON payments(status, created_at);

CREATE TABLE grants (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    days INTEGER NOT NULL CHECK (days BETWEEN 1 AND 365),
    reason_code TEXT NOT NULL CHECK (reason_code IN ('payment_not_credited', 'compensation', 'gift', 'test')),
    note TEXT,
    admin_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (account_id, id)
) STRICT;

CREATE TABLE subscriptions (
    account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    paid_until INTEGER NOT NULL,
    source TEXT NOT NULL,
    payment_id TEXT,
    grant_id TEXT,
    CHECK ((source = 'payment' AND payment_id IS NOT NULL AND grant_id IS NULL)
        OR (source = 'grant' AND payment_id IS NULL AND grant_id IS NOT NULL)),
    FOREIGN KEY (account_id, payment_id) REFERENCES payments(account_id, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (account_id, grant_id) REFERENCES grants(account_id, id) DEFERRABLE INITIALLY DEFERRED
) STRICT;

CREATE TABLE reconcile_cases (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    claimed_amount INTEGER NOT NULL CHECK (claimed_amount > 0),
    claimed_time INTEGER NOT NULL,
    provider_tx_id TEXT,
    status TEXT NOT NULL CHECK (status IN ('new', 'checking', 'closed')),
    resolution TEXT,
    admin_id TEXT
) STRICT;

CREATE INDEX reconcile_cases_account ON reconcile_cases(account_id);

CREATE TABLE audit_log (
    id TEXT PRIMARY KEY,
    admin_id TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT NOT NULL,
    at INTEGER NOT NULL
) STRICT;

CREATE INDEX audit_log_retention ON audit_log(at);

CREATE TRIGGER audit_log_no_update
BEFORE UPDATE ON audit_log
BEGIN
    SELECT RAISE(ABORT, 'audit_log_immutable');
END;

-- Only retention maintenance may remove rows beyond the retention period.
CREATE TRIGGER audit_log_retention_guard
BEFORE DELETE ON audit_log
WHEN OLD.at > unixepoch('now', '-12 months', 'floor')
BEGIN
    SELECT RAISE(ABORT, 'audit_log_immutable');
END;
