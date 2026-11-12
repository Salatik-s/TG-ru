import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const sessionSeconds = 20 * 60;
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
const token = () => randomBytes(32).toString('base64url');

export function installOwnerSchema(db) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS store_owner (
      id INTEGER PRIMARY KEY CHECK(id = 1),
      login TEXT NOT NULL CHECK(length(login) = 22),
      password_salt BLOB NOT NULL CHECK(length(password_salt) = 16),
      password_hash BLOB NOT NULL CHECK(length(password_hash) = 32),
      totp_key BLOB NOT NULL CHECK(length(totp_key) = 20),
      last_step INTEGER NOT NULL DEFAULT -1
    ) STRICT;
    CREATE TABLE IF NOT EXISTS store_owner_audit (
      id INTEGER PRIMARY KEY,
      account_id TEXT NOT NULL REFERENCES accounts(id),
      action TEXT NOT NULL CHECK(action IN ('create', 'grant', 'revoke')),
      plan TEXT CHECK(plan IN ('start', 'pro')),
      days INTEGER,
      access_until INTEGER NOT NULL,
      acted_at INTEGER NOT NULL
    ) STRICT;
  `);
}

export async function provisionOwner(db, hash, replace = false) {
  const login = 'owner_' + randomBytes(8).toString('hex');
  const password = token();
  const salt = randomBytes(16);
  const key = randomBytes(20);
  const digest = await hash(password, salt);
  if (replace) {
    const result = db.prepare('UPDATE store_owner SET login=?, password_salt=?, password_hash=?, totp_key=?, last_step=-1 WHERE id=1')
      .run(login, salt, digest, key);
    if (!result.changes) throw new Error('Owner has not been initialized.');
  } else {
    const result = db.prepare('INSERT OR IGNORE INTO store_owner (id, login, password_salt, password_hash, totp_key) VALUES (1,?,?,?,?)')
      .run(login, salt, digest, key);
    if (!result.changes) throw new Error('Owner is already initialized.');
  }
  // The 20-byte key is exactly 32 Base32 characters, without padding.
  const bits = Array.from(key, byte => byte.toString(2).padStart(8, '0')).join('');
  const totp = bits.match(/.{5}/g).map(group => alphabet[parseInt(group, 2)]).join('');
  return { login, password, totp };
}

export function totpCode(key, step) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hmac = createHmac('sha1', key).update(counter).digest();
  const offset = hmac.at(-1) & 15;
  return String((hmac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

export function createOwnerAuth(db, now, hash, StoreError) {
  const credentials = db.prepare('SELECT * FROM store_owner WHERE id=1');
  const saltQuery = db.prepare('SELECT password_salt FROM store_owner WHERE id=1');
  const consumeStep = db.prepare('UPDATE store_owner SET last_step=? WHERE id=1 AND last_step<? AND password_salt=?');
  let current;
  let attempts = 0;
  let resetAt = 0;

  function session(rawToken) {
    if (!current || !tokenPattern.test(rawToken ?? '') || current.expiresAt <= now()) return null;
    if (!timingSafeEqual(Buffer.from(current.rawToken), Buffer.from(rawToken))) return null;
    const fresh = saltQuery.get();
    return fresh && timingSafeEqual(current.salt, fresh.password_salt) ? current : null;
  }

  async function login(data) {
    const owner = credentials.get();
    if (!owner) throw new StoreError(503, 'owner_unavailable');
    const password = data.password ?? '';
    if (data.login !== owner.login || !/^\d{6}$/.test(data.otp ?? '') || Buffer.byteLength(password) > 256) {
      throw new StoreError(401, 'owner_credentials');
    }
    if (now() >= resetAt) {
      attempts = 0;
      resetAt = now() + 300;
    }
    if (attempts >= 8) throw new StoreError(429, 'too_many_attempts', resetAt - now());
    attempts += 1;
    const computed = await hash(password, owner.password_salt);
    if (!timingSafeEqual(computed, owner.password_hash)) throw new StoreError(401, 'owner_credentials');
    const step = Math.floor(now() / 30);
    const otp = Buffer.from(data.otp);
    const matched = [step, step - 1, step + 1].find((candidate) =>
      candidate >= 0 && candidate > owner.last_step && timingSafeEqual(otp, Buffer.from(totpCode(owner.totp_key, candidate))));
    if (matched === undefined || !consumeStep.run(matched, matched, owner.password_salt).changes) {
      throw new StoreError(401, 'owner_credentials');
    }
    attempts = 0;
    current = { rawToken: token(), csrf: token(), expiresAt: now() + sessionSeconds,
      duration: sessionSeconds, salt: owner.password_salt };
    return current;
  }

  function authorize(rawToken, csrf) {
    const active = session(rawToken);
    if (!active) throw new StoreError(403, 'owner_required');
    if (!tokenPattern.test(csrf ?? '') || !timingSafeEqual(Buffer.from(active.csrf), Buffer.from(csrf))) {
      throw new StoreError(403, 'csrf_invalid');
    }
    return active;
  }

  return {
    session,
    login,
    authorize,
    rotate: (active) => { active.csrf = token(); },
    logout: () => { current = undefined; },
  };
}
