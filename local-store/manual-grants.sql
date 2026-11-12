ALTER TABLE accounts ADD COLUMN manual_until INTEGER NOT NULL DEFAULT 0 CHECK(manual_until >= 0);

CREATE TABLE manual_grant_audit (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  actor TEXT NOT NULL DEFAULT 'owner-console' CHECK(actor = 'owner-console'),
  reason TEXT NOT NULL CHECK(reason IN ('payment_not_credited', 'compensation', 'gift', 'test')),
  days INTEGER NOT NULL CHECK(days BETWEEN 1 AND 365),
  granted_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
) STRICT;

CREATE INDEX manual_grant_audit_time ON manual_grant_audit(granted_at);

CREATE TRIGGER manual_grant_no_replace BEFORE INSERT ON manual_grant_audit
WHEN EXISTS (SELECT 1 FROM manual_grant_audit WHERE id=NEW.id)
BEGIN
  SELECT RAISE(ABORT, 'manual_grant_audit_is_append_only');
END;

CREATE TRIGGER manual_grant_no_update BEFORE UPDATE ON manual_grant_audit
BEGIN
  SELECT RAISE(ABORT, 'manual_grant_audit_is_append_only');
END;

CREATE TRIGGER manual_grant_no_delete BEFORE DELETE ON manual_grant_audit
BEGIN
  SELECT RAISE(ABORT, 'manual_grant_audit_is_append_only');
END;
