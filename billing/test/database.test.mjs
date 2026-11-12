import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { openBillingDatabase } from '../database.mjs';

const now = 1_800_000_000;
const digest = (value) => Buffer.alloc(32, value);

function addAccount(db, id, entitlementByte = 1) {
  db.prepare(`
    INSERT INTO accounts (id, login, password_hash, entitlement_id, created_at)
    VALUES (?, ?, ?, ?, ?)
  `).run(id, id, '$argon2id$fixture-only', Buffer.alloc(16, entitlementByte), now);
}

function openTestDatabase(t, path = ':memory:') {
  const db = openBillingDatabase(path);
  t.after(() => db.close());
  return db;
}

test('creates the Billing schema once and reopens it with constraints enabled', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'tg-ru-billing-'));
  const path = join(directory, 'billing.sqlite');
  const initial = openBillingDatabase(path);
  try {
    addAccount(initial, 'first');
  } finally {
    initial.close();
  }

  const db = openBillingDatabase(path);
  t.after(() => {
    db.close();
    rmSync(directory, { recursive: true });
  });
  assert.equal(db.prepare('PRAGMA user_version').get().user_version, 1);
  assert.equal(db.prepare('PRAGMA foreign_keys').get().foreign_keys, 1);
  assert.equal(db.prepare('PRAGMA secure_delete').get().secure_delete, 1);
  assert.equal(db.prepare('SELECT login FROM accounts').get().login, 'first');
  assert.throws(() => addAccount(db, 'second'), /UNIQUE constraint failed/);
});

test('rejects an existing Gateway database and an unknown Billing schema version', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'tg-ru-billing-'));
  t.after(() => rmSync(directory, { recursive: true }));
  const gatewayPath = join(directory, 'gateway.sqlite');
  const gateway = new DatabaseSync(gatewayPath);
  try {
    gateway.exec('CREATE TABLE devices (id TEXT); INSERT INTO devices VALUES (\'approved\');');
    assert.throws(() => openBillingDatabase(gatewayPath), /not an empty Billing database/);
    assert.equal(gateway.prepare('SELECT id FROM devices').get().id, 'approved');
    assert.equal(gateway.prepare('PRAGMA user_version').get().user_version, 0);
    assert.equal(gateway.prepare('PRAGMA application_id').get().application_id, 0);
  } finally {
    gateway.close();
  }

  const billingPath = join(directory, 'billing.sqlite');
  const billing = openBillingDatabase(billingPath);
  try {
    billing.exec('PRAGMA user_version = 99');
  } finally {
    billing.close();
  }
  assert.throws(() => openBillingDatabase(billingPath), /Unsupported Billing database or schema version/);
});

test('rejects invalid logins, cleartext password and token values, and excess session lifetime', (t) => {
  const db = openTestDatabase(t);
  assert.throws(() => addAccount(db, 'invalid login'), /CHECK constraint failed/);
  assert.throws(() => addAccount(db, 'abc\0hidden'), /CHECK constraint failed/);
  assert.throws(() => db.prepare(`
    INSERT INTO accounts (id, login, password_hash, entitlement_id, created_at)
    VALUES ('clear', 'clear', ?, ?, ?)
  `).run('fixture-cleartext', Buffer.alloc(16), now), /CHECK constraint failed/);
  addAccount(db, 'first');

  const session = db.prepare(`
    INSERT INTO sessions (id, account_id, token_hash, created_at, expires_at, user_agent_short)
    VALUES ('session', 'first', ?, ?, ?, 'Browser')
  `);
  assert.throws(() => session.run('fixture-token', now, now + 60), /cannot store TEXT value in BLOB/);
  assert.throws(() => session.run(digest(1), now, now + 1_209_601), /CHECK constraint failed/);
  assert.equal(session.run(digest(1), now, now + 1_209_600).changes, 1);
});

test('limits active devices per account on insert and reactivation', (t) => {
  const db = openTestDatabase(t);
  addAccount(db, 'first');
  addAccount(db, 'second', 2);
  const link = db.prepare(`
    INSERT INTO device_links (account_id, device_key_hash, label, linked_at)
    VALUES (?, ?, 'Phone', ?)
  `);
  const setRevokedAt = db.prepare(`
    UPDATE device_links SET revoked_at = ? WHERE account_id = 'first' AND device_key_hash = ?
  `);
  for (const byte of [1, 2, 3]) link.run('first', digest(byte), now);
  link.run('second', digest(5), now);
  assert.throws(() => link.run('first', digest(4), now), /device_limit/);
  assert.throws(() => db.exec("UPDATE device_links SET account_id = 'first' WHERE account_id = 'second'"), /device_limit/);
  setRevokedAt.run(now, digest(1));
  link.run('first', digest(4), now);
  assert.throws(() => setRevokedAt.run(null, digest(1)), /device_limit/);
  setRevokedAt.run(now, digest(2));
  assert.equal(setRevokedAt.run(null, digest(1)).changes, 1);
});

test('deduplicates provider transactions and forbids a subscription using another account’s payment', (t) => {
  const db = openTestDatabase(t);
  addAccount(db, 'first');
  addAccount(db, 'second', 2);
  const insertPayment = db.prepare(`
    INSERT INTO payments (id, account_id, provider, provider_tx_id, amount_kop, currency, status, created_at)
    VALUES (?, ?, 'platega', ?, 19900, 'RUB', 'paid', ?)
  `);
  insertPayment.run('payment', 'first', 'provider-tx', now);
  assert.throws(() => insertPayment.run('other', 'second', 'provider-tx', now), /UNIQUE constraint failed/);

  const subscribe = db.prepare(`
    INSERT INTO subscriptions (account_id, paid_until, source, payment_id)
    VALUES (?, ?, 'payment', 'payment')
  `);
  assert.throws(() => subscribe.run('second', now + 100), /FOREIGN KEY constraint failed/);
  assert.equal(subscribe.run('first', now + 100).changes, 1);
});

test('account deletion removes dependent data and retains the unlinked payment', (t) => {
  const db = openTestDatabase(t);
  addAccount(db, 'first');
  db.prepare('INSERT INTO sessions VALUES (?, ?, ?, ?, ?, ?)')
    .run('session', 'first', digest(1), now, now + 100, 'Browser');
  db.prepare('INSERT INTO recovery_tokens VALUES (?, ?, ?, NULL)').run(digest(2), 'first', now + 1800);
  db.prepare('INSERT INTO device_links VALUES (?, ?, ?, ?, NULL)').run('first', digest(3), 'Phone', now);
  db.prepare('INSERT INTO grants VALUES (?, ?, ?, ?, NULL, ?, ?)')
    .run('grant', 'first', 30, 'gift', 'owner', now);
  db.prepare('INSERT INTO subscriptions VALUES (?, ?, ?, NULL, ?)').run('first', now + 100, 'grant', 'grant');
  db.prepare('INSERT INTO payments VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
    .run('payment', 'first', 'platega', 'provider-tx', 19900, 'RUB', 'paid', now, now, digest(4));
  db.prepare('INSERT INTO reconcile_cases VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)')
    .run('case', 'first', 19900, now, 'provider-tx', 'new');

  assert.equal(db.prepare('DELETE FROM accounts WHERE id = ?').run('first').changes, 1);
  for (const table of ['accounts', 'sessions', 'recovery_tokens', 'device_links', 'subscriptions', 'grants', 'reconcile_cases']) {
    assert.equal(db.prepare(`SELECT count(*) AS total FROM ${table}`).get().total, 0, table);
  }
  const payment = db.prepare('SELECT account_id, amount_kop FROM payments').get();
  assert.equal(payment.account_id, null);
  assert.equal(payment.amount_kop, 19900);
  assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);
});

test('protects current audit entries from update, replacement, and deletion and allows retention cleanup', (t) => {
  const db = openTestDatabase(t);
  db.exec(`
    INSERT INTO audit_log VALUES ('recent', 'owner', 'grant', 'target', unixepoch());
    INSERT INTO audit_log VALUES ('old', 'owner', 'grant', 'target', unixepoch('now', '-13 months'));
  `);
  assert.throws(() => db.exec("UPDATE audit_log SET action = 'edit'"), /audit_log_immutable/);
  assert.throws(() => db.exec("INSERT OR REPLACE INTO audit_log VALUES ('recent', 'owner', 'edit', 'target', unixepoch())"), /audit_log_immutable/);
  assert.throws(() => db.exec('DELETE FROM audit_log'), /audit_log_immutable/);
  assert.equal(db.prepare("DELETE FROM audit_log WHERE at <= unixepoch('now', '-12 months', 'floor')").run().changes, 1);
  assert.equal(db.prepare('SELECT id FROM audit_log').get().id, 'recent');
});
