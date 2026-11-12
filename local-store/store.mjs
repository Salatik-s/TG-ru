import {
  argon2,
  createHash,
  createHmac,
  createPublicKey,
  randomBytes,
  randomUUID,
  timingSafeEqual,
  verify,
} from 'node:crypto';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { promisify } from 'node:util';

const APP_ID = 0x54474c53; // TGLS
const PRICE_RUB = 199;
const DAY = 86_400;
const TRIAL_SECONDS = 18_000;
const PAID_SECONDS = 30 * DAY;
const LEASE_SECONDS = 75;
const SESSION_SECONDS = 14 * DAY;
const CHALLENGE_SECONDS = 600;
export const MANUAL_GRANT_REASONS = Object.freeze(['payment_not_credited', 'compensation', 'gift', 'test']);
const TOKEN_PATTERN = /^[a-zA-Z0-9_-]{43}$/;
const DEVICE_ID_PATTERN = /^[a-f0-9]{64}$/;
const PROOF_PREFIX = 'tg-ru-store-local\n';
const derive = promisify(argon2);

const digest = (value) => createHash('sha256').update(value).digest();
const token = () => randomBytes(32).toString('base64url');

export class StoreError extends Error {
  constructor(status, code, retryAfter) {
    super(code);
    this.status = status;
    this.code = code;
    if (retryAfter) this.retryAfter = retryAfter;
  }
}

function require(condition, status, code) {
  if (!condition) throw new StoreError(status, code);
}

function localDatabase(path) {
  const db = new DatabaseSync(path, { enableForeignKeyConstraints: true });
  try {
    db.exec('PRAGMA busy_timeout=5000; PRAGMA secure_delete=ON; BEGIN IMMEDIATE');
    const { application_id } = db.prepare('PRAGMA application_id').get();
    const { user_version } = db.prepare('PRAGMA user_version').get();
    if (!application_id && !user_version) {
      const occupied = db.prepare("SELECT 1 FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' LIMIT 1").get();
      if (occupied) throw new Error('Expected an empty local Store database');
      db.exec(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'));
      db.exec(`PRAGMA application_id=${APP_ID}; PRAGMA user_version=1`);
    } else if (application_id !== APP_ID || user_version < 1 || user_version > 2) {
      throw new Error('Unsupported local Store database');
    }
    if (user_version < 2) {
      db.exec(readFileSync(new URL('./manual-grants.sql', import.meta.url), 'utf8'));
      db.exec('PRAGMA user_version=2');
    }
    // An additive table lets the prior runtime continue reading the database during a deployment rollback.
    db.exec(`CREATE TABLE IF NOT EXISTS pro_access (
      account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
      expires_at INTEGER NOT NULL CHECK(expires_at > 0)
    ) STRICT`);
    db.exec('COMMIT');
    return db;
  } catch (error) {
    db.close();
    throw error;
  }
}

function loginName(value) {
  return typeof value === 'string' && /^[a-zA-Z0-9_]{3,24}$/.test(value)
    ? value.toLowerCase() : null;
}

function acceptablePassword(value, login) {
  if (typeof value !== 'string' || [...value].length < 12 || Buffer.byteLength(value) > 256) return false;
  const lower = value.toLowerCase();
  return !/^(.)\1+$/s.test(value) && !/^(?:password|qwerty|123456|letmein|admin)(?:[0-9!@#._-]+)?$/.test(lower)
    && lower !== login;
}

function base64(value, maxBytes) {
  require(typeof value === 'string' && value.length <= 4 * Math.ceil(maxBytes / 3)
    && /^(?:[a-zA-Z0-9+/]{4})*(?:[a-zA-Z0-9+/]{2}==|[a-zA-Z0-9+/]{3}=)?$/.test(value),
  400, 'bad_request');
  const bytes = Buffer.from(value, 'base64');
  require(bytes.length > 0 && bytes.length <= maxBytes && bytes.toString('base64') === value, 400, 'bad_request');
  return bytes;
}

function keyFromDer(der) {
  let key;
  try {
    key = createPublicKey({ key: der, format: 'der', type: 'spki' });
  } catch {
    throw new StoreError(400, 'invalid_public_key');
  }
  require(key.asymmetricKeyType === 'ec'
    && key.asymmetricKeyDetails.namedCurve === 'prime256v1'
    && key.export({ format: 'der', type: 'spki' }).equals(der), 400, 'invalid_public_key');
  return key;
}

function validProof(key, nonce, signature) {
  return verify('sha256', Buffer.from(PROOF_PREFIX + nonce), key, signature);
}

function passwordHash(password, nonce) {
  return derive('argon2id', {
    message: Buffer.from(password),
    nonce,
    parallelism: 1,
    tagLength: 32,
    memory: 65_536,
    passes: 3,
  });
}

function expiry(account) {
  const trialUntil = account.trial_started_at === null ? null : account.trial_started_at + TRIAL_SECONDS;
  return { trialUntil, accessUntil: Math.max(trialUntil ?? 0, account.paid_until, account.manual_until) };
}

function proExpiresAt(db, accountId) {
  return db.prepare('SELECT expires_at FROM pro_access WHERE account_id=?').get(accountId)?.expires_at ?? 0;
}

function startExpiresAt(account) {
  return Math.max(account.paid_until, account.manual_until);
}

function deviceLimit(account, proUntil, time) {
  return proUntil > time ? 4 : startExpiresAt(account) > time ? 3 : 2;
}

function transaction(db, callback) {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = callback();
    db.exec('COMMIT');
    return result;
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}

/** Invoked by the filesystem-authorized owner console. There is no HTTP action for this. */
export function grantStoreAccess({
  database,
  login: input,
  days,
  reason,
  plan = 'start',
  onlyIfInactive = false,
  now = () => Math.floor(Date.now() / 1000),
}) {
  const login = loginName(input);
  require(login, 400, 'invalid_login');
  require(Number.isInteger(days) && days >= 1 && days <= 365, 400, 'invalid_duration');
  require(MANUAL_GRANT_REASONS.includes(reason), 400, 'invalid_reason');
  require(plan === 'start' || plan === 'pro' && days === 90, 400, 'invalid_plan');
  const db = localDatabase(database);
  try {
    return transaction(db, () => {
      const account = db.prepare('SELECT * FROM accounts WHERE login=?').get(login);
      require(account, 404, 'account_not_found');
      const time = now();
      const accessUntil = expiry(account).accessUntil;
      if (onlyIfInactive && accessUntil > time) return { skipped: true, access_until: accessUntil };
      const { total } = db.prepare('SELECT count(*) AS total FROM manual_grant_audit WHERE granted_at>?')
        .get(time - DAY);
      require(total < 3, 429, 'manual_grant_limit');
      const until = Math.max(time, accessUntil) + days * DAY;
      const id = randomUUID();
      db.prepare('UPDATE accounts SET manual_until=? WHERE id=?').run(until, account.id);
      if (plan === 'pro') {
        db.prepare('INSERT INTO pro_access VALUES (?,?) ON CONFLICT(account_id) DO UPDATE SET expires_at=excluded.expires_at')
          .run(account.id, until);
      }
      db.prepare(`
        INSERT INTO manual_grant_audit (id, account_id, reason, days, granted_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?)
      `).run(id, account.id, reason, days, time, until);
      return { grant_id: id, reason, days, plan, granted_at: time, manual_until: until };
    });
  } finally {
    db.close();
  }
}

export function createStore({
  database = ':memory:',
  now = () => Math.floor(Date.now() / 1000),
  allowMockPurchases = false,
  authorizeGateway,
} = {}) {
  const db = localDatabase(database);
  const csrfKey = randomBytes(32);
  const dummySalt = randomBytes(16);
  const dummyHash = randomBytes(32);
  const attempts = new Map();
  let hashing = 0;
  let lastPurge = -Infinity;

  const inTransaction = (callback) => transaction(db, callback);

  function purge() {
    const time = now();
    if (time < lastPurge + 60) return;
    lastPurge = time;
    db.prepare('DELETE FROM sessions WHERE expires_at <= ?').run(time);
    db.prepare('DELETE FROM challenges WHERE expires_at <= ?').run(time);
    for (const [key, value] of attempts) if (time >= value.resetAt) attempts.delete(key);
  }

  function accountById(id) {
    return db.prepare('SELECT * FROM accounts WHERE id = ?').get(id);
  }

  function accountByLogin(login) {
    return db.prepare('SELECT * FROM accounts WHERE login = ?').get(login);
  }

  function activeDevices(accountId) {
    return db.prepare(`
      SELECT id, name, activated_at FROM devices WHERE account_id=? AND revoked_at IS NULL
      ORDER BY activated_at, id
    `).all(accountId);
  }

  function publicAccount(id) {
    if (!id) return null;
    const account = accountById(id);
    if (!account) return null;
    const { trialUntil, accessUntil } = expiry(account);
    const time = now();
    const proUntil = proExpiresAt(db, id);
    const pro = proUntil > time;
    const start = startExpiresAt(account) > time;
    return {
      login: account.login,
      trial_started_at: account.trial_started_at,
      trial_until: trialUntil,
      paid_until: account.paid_until,
      manual_until: account.manual_until,
      access_until: accessUntil,
      active: accessUntil > time,
      plan: pro ? 'pro' : start ? 'start' : null,
      pro_until: proUntil,
      device_limit: deviceLimit(account, proUntil, time),
      devices: activeDevices(id),
    };
  }

  function newSession(accountId = null) {
    const rawToken = token();
    const duration = accountId ? SESSION_SECONDS : 3600;
    db.prepare('INSERT INTO sessions (token_hash, account_id, expires_at) VALUES (?, ?, ?)')
      .run(digest(rawToken), accountId, now() + duration);
    return { rawToken, accountId, duration };
  }

  function findSession(rawToken) {
    if (!TOKEN_PATTERN.test(rawToken ?? '')) return null;
    const row = db.prepare('SELECT account_id FROM sessions WHERE token_hash = ? AND expires_at > ?')
      .get(digest(rawToken), now());
    return row ? { rawToken, accountId: row.account_id } : null;
  }

  function dropSession(session) {
    if (session) db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(digest(session.rawToken));
  }

  function csrfFor(session) {
    return createHmac('sha256', csrfKey).update(session.rawToken).digest('base64url');
  }

  function requireCsrf(session, value) {
    require(session && typeof value === 'string' && TOKEN_PATTERN.test(value), 403, 'csrf_invalid');
    require(timingSafeEqual(Buffer.from(csrfFor(session)), Buffer.from(value)), 403, 'csrf_invalid');
  }

  function sessionData(session) {
    return {
      csrf: csrfFor(session),
      account: publicAccount(session.accountId),
      price_rub: PRICE_RUB,
      payment_mode: allowMockPurchases ? 'mock' : 'disabled',
    };
  }

  function requireAccount(session) {
    const account = session?.accountId && accountById(session.accountId);
    require(account, 401, 'authentication_required');
    return account;
  }

  function checkAccess(account, code, time = now()) {
    const { accessUntil } = expiry(account);
    require(accessUntil > time, 403, code);
    return accessUntil;
  }

  function lease(deviceId, accessUntil) {
    return {
      device_id: deviceId,
      access_until: accessUntil,
      lease_until: Math.min(accessUntil, now() + LEASE_SECONDS),
    };
  }

  async function hash(password, salt) {
    if (hashing >= 2) throw new StoreError(429, 'too_many_attempts', 2);
    hashing += 1;
    try {
      return await passwordHash(password, salt);
    } finally {
      hashing -= 1;
    }
  }

  function authRecord(key, threshold) {
    const time = now();
    const old = attempts.get(key);
    if (old && old.resetAt > time) {
      if (old.blockedUntil > time) throw new StoreError(429, 'too_many_attempts', old.blockedUntil - time);
      return old;
    }
    if (attempts.size >= 512 && !attempts.has(key)) {
      throw new StoreError(429, 'too_many_attempts', 60);
    }
    const record = { failures: 0, blockedUntil: 0, resetAt: time + 900, threshold };
    attempts.set(key, record);
    return record;
  }

  function failed(record) {
    const time = now();
    record.failures += 1;
    if (record.failures >= record.threshold) {
      record.blockedUntil = time + 900;
      record.resetAt = record.blockedUntil;
    } else if (record.threshold === 10 && record.failures >= 5) {
      record.blockedUntil = time + 2 ** (record.failures - 4);
    }
  }

  async function authenticate(data) {
    const login = loginName(data.login);
    const global = authRecord('*', 50);
    const attemptKey = login ?? '_';
    const attempt = authRecord(attemptKey, 10);
    const account = login && accountByLogin(login);
    const password = typeof data.password === 'string' && Buffer.byteLength(data.password) <= 256
      ? data.password : '';
    const computed = await hash(password, account?.password_salt ?? dummySalt);
    const equal = timingSafeEqual(computed, account?.password_hash ?? dummyHash);
    if (!account || !equal) {
      failed(attempt);
      failed(global);
      throw new StoreError(401, 'invalid_credentials');
    }
    const fresh = accountById(account.id);
    require(fresh && timingSafeEqual(fresh.password_hash, account.password_hash), 401, 'invalid_credentials');
    attempts.delete(attemptKey);
    return fresh;
  }

  async function register(data, session) {
    const login = loginName(data.login);
    require(login, 400, 'invalid_login');
    require(acceptablePassword(data.password, login), 400, 'weak_password');
    require(data.accepted_terms === true, 400, 'terms_required');
    require(!accountByLogin(login), 409, 'login_taken');
    const salt = randomBytes(16);
    const password = await hash(data.password, salt);
    const recoveryCode = token();
    return inTransaction(() => {
      require(!accountByLogin(login), 409, 'login_taken');
      const id = randomUUID();
      db.prepare(`
        INSERT INTO accounts (id, login, password_salt, password_hash, recovery_hash, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
      `).run(id, login, salt, password, digest(recoveryCode), now());
      dropSession(session);
      return { session: newSession(id), result: { recovery_code: recoveryCode } };
    });
  }

  async function recover(data, session) {
    require(typeof data.recovery_code === 'string' && TOKEN_PATTERN.test(data.recovery_code), 400, 'invalid_recovery_code');
    const recoveryHash = digest(data.recovery_code);
    const account = db.prepare('SELECT id, login FROM accounts WHERE recovery_hash = ?').get(recoveryHash);
    require(account, 400, 'invalid_recovery_code');
    require(acceptablePassword(data.new_password, account.login), 400, 'weak_password');
    const salt = randomBytes(16);
    const password = await hash(data.new_password, salt);
    const newCode = token();
    return inTransaction(() => {
      const changed = db.prepare(`
        UPDATE accounts SET password_salt=?, password_hash=?, recovery_hash=?
        WHERE id=? AND recovery_hash=?
      `).run(salt, password, digest(newCode), account.id, recoveryHash);
      require(changed.changes === 1, 400, 'invalid_recovery_code');
      db.prepare('DELETE FROM sessions WHERE account_id=?').run(account.id);
      db.prepare("DELETE FROM challenges WHERE account_id=? AND action='activate'").run(account.id);
      dropSession(session);
      return { session: newSession(), result: { recovery_code: newCode } };
    });
  }

  function startTrial(account) {
    if (account.trial_started_at === null) {
      require(!account.paid_until && !account.manual_until, 409, 'trial_unavailable');
      db.prepare('UPDATE accounts SET trial_started_at=? WHERE id=? AND trial_started_at IS NULL')
        .run(now(), account.id);
    }
  }

  function mockPurchase(account, data) {
    require(typeof data.request_id === 'string' && /^[a-zA-Z0-9_-]{8,80}$/.test(data.request_id), 400, 'invalid_request_id');
    require(data.outcome === 'paid' || data.outcome === 'canceled', 400, 'invalid_outcome');
    return inTransaction(() => {
      const existing = db.prepare('SELECT status, paid_until FROM mock_orders WHERE account_id=? AND request_id=?')
        .get(account.id, data.request_id);
      if (existing) {
        require(existing.status === data.outcome, 409, 'order_conflict');
        return { order: { ...existing, request_id: data.request_id, amount_rub: PRICE_RUB, simulated: true } };
      }
      const paidUntil = data.outcome === 'paid'
        ? Math.max(now(), account.paid_until) + PAID_SECONDS
        : account.paid_until;
      db.prepare('INSERT INTO mock_orders (account_id, request_id, status, paid_until) VALUES (?, ?, ?, ?)')
        .run(account.id, data.request_id, data.outcome, paidUntil);
      if (data.outcome === 'paid') {
        db.prepare('UPDATE accounts SET paid_until=? WHERE id=?').run(paidUntil, account.id);
      }
      return {
        order: {
          request_id: data.request_id,
          status: data.outcome,
          paid_until: paidUntil,
          amount_rub: PRICE_RUB,
          simulated: true,
        },
      };
    });
  }

  function revoke(account, id) {
    require(typeof id === 'string' && DEVICE_ID_PATTERN.test(id), 400, 'bad_request');
    const device = db.prepare('SELECT id FROM devices WHERE id=? AND account_id=?').get(id, account.id);
    require(device, 404, 'device_not_found');
    inTransaction(() => {
      const revoked = db.prepare('UPDATE devices SET revoked_at=? WHERE id=? AND revoked_at IS NULL')
        .run(now(), id);
      if (revoked.changes) {
        // Require fresh credentials before a revoked installation may activate again.
        db.prepare("DELETE FROM challenges WHERE action='activate' AND account_id=?").run(account.id);
      }
    });
  }

  async function webAction(action, data, session, csrf) {
    requireCsrf(session, csrf);
    switch (action) {
      case 'register':
        return register(data, session);
      case 'login': {
        const account = await authenticate(data);
        return inTransaction(() => {
          dropSession(session);
          return { session: newSession(account.id) };
        });
      }
      case 'recover':
        return recover(data, session);
      case 'logout':
        return inTransaction(() => {
          dropSession(session);
          return { session: newSession() };
        });
      case 'trial':
        startTrial(requireAccount(session));
        return {};
      case 'mock-purchase':
        require(allowMockPurchases, 404, 'not_found');
        return { result: mockPurchase(requireAccount(session), data) };
      case 'devices/revoke':
        revoke(requireAccount(session), data.device_id);
        return {};
      default:
        throw new StoreError(404, 'not_found');
    }
  }

  function issueChallenge(action, accountId, deviceId = null) {
    const rawToken = token();
    const challenge = token();
    const expiresAt = now() + CHALLENGE_SECONDS;
    db.prepare(`
      INSERT INTO challenges (token_hash, action, account_id, device_id, nonce, expires_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(digest(rawToken), action, accountId, deviceId, challenge, expiresAt);
    return { token: rawToken, challenge, expires_at: expiresAt };
  }

  function readChallenge(value, action) {
    require(typeof value === 'string' && TOKEN_PATTERN.test(value), 400, 'invalid_token');
    const hashValue = digest(value);
    const row = db.prepare(`
      SELECT * FROM challenges WHERE token_hash=? AND action=? AND expires_at>?
    `).get(hashValue, action, now());
    require(row, 400, 'invalid_token');
    return row;
  }

  function consumeProof(rawToken, action, proofKey, signature, processProof) {
    const outcome = inTransaction(() => {
      const challenge = readChallenge(rawToken, action);
      require(validProof(proofKey(challenge), challenge.nonce, signature), 403, 'invalid_proof');
      db.prepare('DELETE FROM challenges WHERE token_hash=?').run(challenge.token_hash);
      try {
        return { value: processProof(challenge) };
      } catch (error) {
        if (!(error instanceof StoreError)) throw error;
        return { error };
      }
    });
    if (outcome.error) throw outcome.error;
    return outcome.value;
  }

  function finishActivation(data) {
    const der = base64(data.public_key, 160);
    const key = keyFromDer(der);
    const signature = base64(data.signature, 100);
    const id = digest(der).toString('hex');
    const name = typeof data.name === 'string' ? data.name.trim() : 'Android';
    require(name.length > 0 && [...name].length <= 64 && !/[\p{C}]/u.test(name), 400, 'bad_request');
    return consumeProof(data.token, 'activate', () => key, signature, (challenge) => {
      const account = accountById(challenge.account_id);
      const time = now();
      const accessUntil = checkAccess(account, 'subscription_required', time);
      const existing = db.prepare('SELECT account_id, revoked_at FROM devices WHERE id=?').get(id);
      require(!existing || existing.account_id === challenge.account_id, 409, 'device_linked_elsewhere');
      const devices = activeDevices(challenge.account_id);
      const proUntil = proExpiresAt(db, challenge.account_id);
      const limit = deviceLimit(account, proUntil, time);
      if (!existing || existing.revoked_at !== null) {
        require(devices.length < limit, 409, 'device_limit');
      } else {
        require(devices.findIndex(device => device.id === id) < limit, 409, 'device_limit');
      }
      db.prepare(`
        INSERT INTO devices (id, account_id, public_key, name, activated_at) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET name=excluded.name, revoked_at=NULL,
          activated_at=CASE WHEN devices.revoked_at IS NULL THEN devices.activated_at ELSE excluded.activated_at END
      `).run(id, challenge.account_id, der, name, time);
      return deviceLease(account, id, accessUntil, proUntil, time);
    });
  }

  function finishRenewal(data) {
    const signature = base64(data.signature, 100);
    let device;
    return consumeProof(data.token, 'renew', (challenge) => {
      device = db.prepare('SELECT * FROM devices WHERE id=?').get(challenge.device_id);
      require(device && device.account_id === challenge.account_id, 403, 'invalid_proof');
      return keyFromDer(Buffer.from(device.public_key));
    }, signature, () => {
      require(device.revoked_at === null, 403, 'device_revoked');
      const account = accountById(device.account_id);
      const time = now();
      const accessUntil = checkAccess(account, 'subscription_expired', time);
      return deviceLease(account, device.id, accessUntil, proExpiresAt(db, device.account_id), time);
    });
  }

  function deviceLease(account, id, accessUntil, proUntil, time) {
    const index = activeDevices(account.id).findIndex(device => device.id === id);
    require(index >= 0 && index < deviceLimit(account, proUntil, time), 409, 'device_limit');
    const tierUntil = index === 3 ? proUntil : index === 2 ? startExpiresAt(account) : accessUntil;
    return lease(id, Math.min(accessUntil, tierUntil));
  }

  async function deviceAction(action, data) {
    switch (action) {
      case 'activation-token': {
        const account = await authenticate(data);
        checkAccess(account, 'subscription_required');
        return issueChallenge('activate', account.id);
      }
      case 'activate':
        return transportLease(finishActivation(data));
      case 'device/challenge': {
        require(typeof data.device_id === 'string' && DEVICE_ID_PATTERN.test(data.device_id), 400, 'bad_request');
        const device = db.prepare('SELECT account_id FROM devices WHERE id=?').get(data.device_id);
        require(device, 404, 'device_not_found');
        return issueChallenge('renew', device.account_id, data.device_id);
      }
      case 'device/renew':
        return transportLease(finishRenewal(data));
      default:
        throw new StoreError(404, 'not_found');
    }
  }

  async function transportLease(grant) {
    if (!authorizeGateway) return grant;
    const gateway = await authorizeGateway(grant);
    return { ...grant, gateway, server_time: now() };
  }

  return {
    close: () => db.close(),
    purge,
    findSession,
    authorizeDownload(session, csrf) {
      requireCsrf(session, csrf);
      requireAccount(session);
    },
    newSession,
    sessionData,
    webAction,
    deviceAction,
  };
}
