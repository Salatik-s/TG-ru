import assert from 'node:assert/strict';
import { createHmac, generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, rmdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { createStoreServer } from './http.mjs';
import { grantStoreAccess } from './store.mjs';
import { gatewayClient } from './gateway-client.mjs';

const password = () => 'Testing_' + randomBytes(18).toString('base64url');
const credentials = () => ({ login: 'tester', password: password() });
const DAY = 86_400;

function installation() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const public_key = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  return {
    activation: (challenge) => ({
      token: challenge.token,
      public_key,
      name: 'Тестовое Android-устройство',
      signature: sign('sha256', Buffer.from('tg-ru-store-local\n' + challenge.challenge), privateKey).toString('base64'),
    }),
    renewal: (challenge) => ({
      token: challenge.token,
      signature: sign('sha256', Buffer.from('tg-ru-store-local\n' + challenge.challenge), privateKey).toString('base64'),
    }),
  };
}

async function fixture(t, options = {}) {
  let clock = 1_900_000_000;
  const server = createStoreServer({ ...options, now: () => clock });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;

  function browser() {
    let cookie;
    let csrf;
    return {
      cookie: () => cookie,
      csrf: () => csrf,
      async request(action, data, headers = {}) {
        const init = { method: data ? 'POST' : 'GET', headers: {} };
        if (cookie) init.headers.Cookie = cookie;
        if (data) {
          init.headers['Content-Type'] = 'application/json';
          if (csrf) init.headers['x-csrf-token'] = csrf;
          init.body = JSON.stringify(data);
        }
        Object.assign(init.headers, headers);
        const response = await fetch(base + '/api/store/' + action, init);
        const header = response.headers.get('Set-Cookie');
        if (header) cookie = header.split(';')[0];
        const body = await response.json();
        if (body.csrf) csrf = body.csrf;
        return { status: response.status, body, headers: response.headers };
      },
    };
  }

  async function signup(client, login = credentials()) {
    await client.request('session');
    const registered = await client.request('register', { ...login, accepted_terms: true });
    assert.equal(registered.status, 200);
    return { login, registered: registered.body };
  }

  return { browser, signup, base, now: () => clock, advance: (delta) => { clock += delta; } };
}

test('registration and recovery rotate sessions, enforce same-origin CSRF and reveal recovery code once', async (t) => {
  const { browser } = await fixture(t);
  const client = browser();
  const login = credentials();
  const anonymous = await client.request('session');
  assert.equal(anonymous.body.account, null);
  assert.match(anonymous.headers.get('Set-Cookie'), /; HttpOnly; Secure; SameSite=Strict$/);
  assert.match(anonymous.headers.get('Content-Security-Policy'), /frame-ancestors 'none'/);
  const originalCookie = client.cookie();
  assert.equal((await client.request('register', login)).body.code, 'terms_required');
  assert.equal((await client.request('register', { ...login, accepted_terms: true }, { 'x-csrf-token': '' })).body.code, 'csrf_invalid');
  assert.equal((await client.request('register', { ...login, accepted_terms: true }, { Origin: 'http://other.invalid' })).body.code, 'invalid_origin');
  const registered = (await client.request('register', { ...login, accepted_terms: true })).body;
  assert.equal(registered.account.login, login.login);
  assert.match(registered.recovery_code, /^[a-zA-Z0-9_-]{43}$/);
  assert.notEqual(client.cookie(), originalCookie);
  assert.equal((await client.request('session')).body.recovery_code, undefined);
  assert.equal((await browser().request('session', undefined, { Cookie: originalCookie })).body.account, null);
  const accountCookie = client.cookie();
  const other = browser();
  await other.request('session');
  const newPassword = password();
  const recovered = (await other.request('recover', { recovery_code: registered.recovery_code, new_password: newPassword })).body;
  assert.equal(recovered.account, null);
  assert.notEqual(recovered.recovery_code, registered.recovery_code);
  assert.equal((await other.request('recover', { recovery_code: registered.recovery_code, new_password: newPassword })).body.code, 'invalid_recovery_code');
  assert.equal((await browser().request('session', undefined, { Cookie: accountCookie })).body.account, null);
  assert.equal((await other.request('login', login)).body.code, 'invalid_credentials');
  assert.equal((await other.request('login', { ...login, password: newPassword })).body.account.login, login.login);
  assert.equal((await other.request('logout', {})).body.account, null);
});

test('trial lasts exactly five hours and simultaneous or subsequent requests keep the original deadline', async (t) => {
  const { browser, signup, advance, now } = await fixture(t);
  const client = browser();
  const { login } = await signup(client);
  assert.equal((await browser().request('activation-token', login)).body.code, 'subscription_required');
  const started = now();
  const responses = await Promise.all([client.request('trial', {}), client.request('trial', {})]);
  for (const { status, body } of responses) {
    assert.equal(status, 200);
    assert.equal(body.account.trial_started_at, started);
    assert.equal(body.account.trial_until, started + 18_000);
    assert.equal(body.account.paid_until, 0);
  }
  advance(17_999);
  const android = browser();
  const challenge = (await android.request('activation-token', login)).body;
  const device = installation();
  const activated = (await android.request('activate', device.activation(challenge))).body;
  assert.equal(activated.lease_until, started + 18_000);
  const renewal = (await android.request('device/challenge', { device_id: activated.device_id })).body;
  advance(1);
  assert.equal((await android.request('device/renew', device.renewal(renewal))).body.code, 'subscription_expired');
  assert.equal((await android.request('activation-token', login)).body.code, 'subscription_required');
  const retry = (await client.request('trial', {})).body;
  assert.equal(retry.account.active, false);
  assert.equal(retry.account.trial_started_at, started);
});

test('the Store forwards only verified lease claims and fails closed when the gateway is unavailable', async (t) => {
  const secret = randomBytes(32);
  const claims = [];
  let gatewayReady = true;
  const gateway = { protocol: 'vless', security: 'reality', host: '127.0.0.1' };
  const control = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = Buffer.concat(chunks).toString('utf8');
    const time = request.headers['x-store-time'];
    const nonce = request.headers['x-store-nonce'];
    const signature = createHmac('sha256', secret).update(`${time}\n${nonce}\n${body}`).digest('hex');
    assert.equal(request.url, '/internal/store/lease');
    assert.match(nonce, /^[0-9a-f]{32}$/);
    assert.equal(request.headers['x-store-signature'], signature);
    claims.push(JSON.parse(body));
    response.writeHead(gatewayReady ? 200 : 503, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ gateway }));
  });
  await new Promise((resolve) => control.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => control.close(resolve)));
  const authorizeGateway = gatewayClient({ secret: secret.toString('hex'), port: control.address().port });
  const { browser, signup, now } = await fixture(t, { authorizeGateway });
  const web = browser();
  const { login } = await signup(web);
  const android = browser();
  assert.equal((await android.request('activation-token', login)).status, 403);
  assert.equal(claims.length, 0);
  await web.request('trial', {});
  const device = installation();
  const challenge = (await android.request('activation-token', login)).body;
  const proof = device.activation(challenge);
  const activated = (await android.request('activate', proof)).body;
  assert.deepEqual(activated.gateway, gateway);
  assert.equal((await android.request('activate', proof)).status, 400);
  assert.deepEqual(claims, [{ device_id: activated.device_id, access_until: now() + 18000, lease_until: now() + 75 }]);
  gatewayReady = false;
  const renewal = (await android.request('device/challenge', { device_id: activated.device_id })).body;
  const failed = await android.request('device/renew', device.renewal(renewal));
  assert.equal(failed.status, 503);
  assert.equal(failed.body.code, 'gateway_unavailable');
  assert.equal(failed.body.gateway, undefined);
  assert.deepEqual(claims[1], claims[0]);
});

test('mock payments require explicit mode and paid, canceled and replayed orders affect access correctly', async (t) => {
  const disabled = await fixture(t);
  const first = disabled.browser();
  await disabled.signup(first);
  const request_id = 'qa_purchase_' + randomBytes(8).toString('hex');
  assert.equal((await first.request('mock-purchase', { request_id, outcome: 'paid' })).status, 404);
  assert.equal((await first.request('session')).body.payment_mode, 'disabled');
  const enabled = await fixture(t, { allowMockPurchases: true });
  const second = enabled.browser();
  await enabled.signup(second);
  assert.equal((await second.request('session')).body.payment_mode, 'mock');
  const trial = (await second.request('trial', {})).body.account.trial_until;
  const canceled = await second.request('mock-purchase', { request_id, outcome: 'canceled' });
  assert.equal(canceled.body.order.simulated, true);
  assert.equal(canceled.body.order.amount_rub, 199);
  assert.equal(canceled.body.account.paid_until, 0);
  assert.equal(canceled.body.account.access_until, trial);
  assert.equal((await second.request('mock-purchase', { request_id, outcome: 'paid' })).body.code, 'order_conflict');
  const paidOrder = { request_id: request_id + '_paid', outcome: 'paid' };
  const paid = (await second.request('mock-purchase', paidOrder)).body;
  assert.equal(paid.account.paid_until, enabled.now() + 30 * DAY);
  assert.equal(paid.account.access_until, paid.account.paid_until);
  assert.equal((await second.request('mock-purchase', paidOrder)).body.account.paid_until, paid.account.paid_until);
  const renewal = await second.request('mock-purchase', { ...paidOrder, request_id: request_id + '_renew' });
  assert.equal(renewal.body.account.paid_until, paid.account.paid_until + 30 * DAY);
});

test('trial activation proves key possession, expires after ten minutes, is single-use and limits two devices', async (t) => {
  const { browser, signup, advance } = await fixture(t);
  const client = browser();
  const { login } = await signup(client);
  await client.request('trial', {});
  const native = browser();
  const issue = async () => {
    const result = await native.request('activation-token', login);
    assert.equal(result.status, 200);
    return result.body;
  };
  const first = installation();
  const second = installation();
  const third = installation();
  const stale = await issue();
  advance(600);
  assert.equal((await native.request('activate', first.activation(stale))).body.code, 'invalid_token');
  const challenge = await issue();
  const valid = first.activation(challenge);
  const wrongSignature = second.activation(challenge).signature;
  assert.equal((await native.request('activate', { ...valid, signature: wrongSignature })).body.code, 'invalid_proof');
  const activated = (await native.request('activate', valid)).body;
  assert.match(activated.device_id, /^[a-f0-9]{64}$/);
  assert.equal((await native.request('activate', valid)).body.code, 'invalid_token');
  assert.equal((await native.request('activate', second.activation(await issue()))).status, 200);
  const thirdAttempt = third.activation(await issue());
  assert.equal((await native.request('activate', thirdAttempt)).body.code, 'device_limit');
  assert.equal((await native.request('activate', thirdAttempt)).body.code, 'invalid_token');
  const renew = (await native.request('device/challenge', { device_id: activated.device_id })).body;
  assert.equal((await native.request('device/renew', first.renewal(renew))).status, 200);
  assert.equal((await native.request('device/renew', first.renewal(renew))).body.code, 'invalid_token');
  assert.equal((await client.request('devices/revoke', { device_id: activated.device_id })).body.account.devices.length, 1);
  const revoked = (await native.request('device/challenge', { device_id: activated.device_id })).body;
  assert.equal((await native.request('device/renew', first.renewal(revoked))).body.code, 'device_revoked');
  assert.equal((await native.request('activate', third.activation(await issue()))).status, 200);
});

test('owner grants START with three devices and PRO with four, then expires the extra PRO lease', async (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'tgru-plan-grant-'));
  const database = join(directory, 'store.sqlite');
  const { browser, signup, now, advance } = await fixture(t, { database });
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const web = browser();
  const { login } = await signup(web);
  const native = browser();
  const grant = (days, plan, onlyIfInactive = false) =>
    grantStoreAccess({ database, login: login.login, days, plan, onlyIfInactive, reason: 'test', now });
  const enroll = async (device) => {
    const issued = (await native.request('activation-token', login)).body;
    return native.request('activate', device.activation(issued));
  };

  const start = grant(30, 'start', true);
  assert.equal(grant(30, 'start', true).access_until, start.manual_until);
  assert.equal((await web.request('session')).body.account.device_limit, 3);
  const devices = Array.from({ length: 4 }, () => installation());
  for (const device of devices.slice(0, 3)) {
    assert.equal((await enroll(device)).status, 200);
    advance(1);
  }
  assert.equal((await enroll(devices[3])).body.code, 'device_limit');
  const pro = grant(90, 'pro');
  const fourth = (await enroll(devices[3])).body;
  assert.ok(fourth.device_id);
  assert.equal((await enroll(installation())).body.code, 'device_limit');
  const current = (await web.request('session')).body.account;
  assert.equal(current.plan, 'pro');
  assert.equal(current.device_limit, 4);
  assert.equal(current.pro_until, start.manual_until + 90 * DAY);
  grant(30, 'start');
  advance(pro.manual_until - now() - 5);
  const challenge = () => native.request('device/challenge', { device_id: fourth.device_id });
  const renew = async () => native.request('device/renew', devices[3].renewal((await challenge()).body));
  assert.equal((await renew()).body.lease_until, pro.manual_until);
  advance(5);
  assert.equal((await renew()).body.code, 'device_limit');
  await web.request('session');
  const renewed = (await web.request('login', login)).body.account;
  assert.equal(renewed.plan, 'start');
  assert.equal(renewed.device_limit, 3);
  assert.equal(renewed.active, true);
});

test('manual grants preserve the trial, are console-only and expire with the signed device lease', async (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'tgru-owner-grant-'));
  const database = join(directory, 'store.sqlite');
  const { browser, signup, advance, now } = await fixture(t, { database });
  t.after(() => {
    rmSync(database, { force: true });
    rmdirSync(directory);
  });
  const client = browser();
  const { login } = await signup(client);
  const trial = (await client.request('trial', {})).body.account.trial_until;
  const grant = (days, reason) => grantStoreAccess({ database, login: login.login, days, reason, now });
  const denied = await client.request('grant', { login: login.login, days: 30, reason: 'gift' });
  assert.equal(denied.status, 404);
  assert.equal((await client.request('owner/grant', { login: login.login, days: 30, reason: 'gift' })).status, 404);
  assert.throws(() => grant(366, 'gift'), { code: 'invalid_duration' });
  assert.throws(() => grant(1, 'unknown'), { code: 'invalid_reason' });
  assert.equal(grant(1, 'gift').manual_until, trial + DAY);
  assert.equal(grant(1, 'payment_not_credited').manual_until, trial + 2 * DAY);
  const third = grant(1, 'compensation');
  assert.equal(third.manual_until, trial + 3 * DAY);
  assert.throws(() => grant(1, 'test'), { code: 'manual_grant_limit' });
  const account = (await client.request('session')).body.account;
  assert.equal(account.manual_until, third.manual_until);
  assert.equal(account.access_until, third.manual_until);
  assert.equal(account.trial_until, trial);
  assert.equal(account.paid_until, 0);

  const audit = new DatabaseSync(database);
  try {
    const saved = audit.prepare('SELECT * FROM manual_grant_audit WHERE id=?').get(third.grant_id);
    assert.equal(saved.actor, 'owner-console');
    assert.equal(saved.reason, 'compensation');
    assert.equal(saved.days, 1);
    assert.equal(saved.expires_at, third.manual_until);
    assert.equal(Object.hasOwn(saved, 'login'), false);
    assert.equal(audit.prepare('SELECT count(*) AS total FROM manual_grant_audit').get().total, 3);
    assert.throws(() => audit.exec("UPDATE manual_grant_audit SET reason='test'"), /append_only/);
    assert.throws(() => audit.exec('DELETE FROM manual_grant_audit'), /append_only/);
    assert.throws(() => audit.exec('INSERT OR REPLACE INTO manual_grant_audit SELECT * FROM manual_grant_audit LIMIT 1'), /append_only/);
  } finally {
    audit.close();
  }

  advance(third.manual_until - now() - 1);
  const native = browser();
  const device = installation();
  const challenge = (await native.request('activation-token', login)).body;
  const activated = (await native.request('activate', device.activation(challenge))).body;
  assert.equal(activated.lease_until, third.manual_until);
  const renewal = (await native.request('device/challenge', { device_id: activated.device_id })).body;
  advance(1);
  assert.equal((await native.request('device/renew', device.renewal(renewal))).body.code, 'subscription_expired');
  assert.equal((await client.request('session')).body.account.active, false);
  assert.equal(grant(1, 'test').manual_until, now() + DAY);
});

test('the owner grant upgrades the original local Store schema without changing paid or trial records', () => {
  const directory = mkdtempSync(join(tmpdir(), 'tgru-owner-migration-'));
  const path = join(directory, 'store.sqlite');
  const db = new DatabaseSync(path);
  try {
    db.exec(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'));
    db.exec('PRAGMA application_id=1413958739; PRAGMA user_version=1');
    db.prepare(`
      INSERT INTO accounts
        (id, login, password_salt, password_hash, recovery_hash, created_at, trial_started_at, paid_until)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run('test-migration-account', 'migration_user', randomBytes(16), randomBytes(32), randomBytes(32),
      1_000_000, 1_000_000, 2_000_000);
    const grant = grantStoreAccess({ database: path, login: 'migration_user', days: 1, reason: 'gift', now: () => 1_001_000 });
    assert.equal(grant.manual_until, 2_000_000 + DAY);
    const row = db.prepare('SELECT trial_started_at, paid_until, manual_until FROM accounts').get();
    assert.deepEqual({ ...row }, {
      trial_started_at: 1_000_000,
      paid_until: 2_000_000,
      manual_until: 2_000_000 + DAY,
    });
    assert.equal(db.prepare('PRAGMA user_version').get().user_version, 2);
  } finally {
    db.close();
    rmSync(path, { force: true });
    rmdirSync(directory);
  }
});

test('browser null Origin is accepted only for same-origin native forms, still with CSRF', async (t) => {
  const { browser, signup, base } = await fixture(t, {
    async pagesHandler(request, response, { submit }) {
      if (request.url !== '/form/trial') return false;
      await submit('trial', {}, request.headers['x-csrf-token']);
      response.writeHead(204).end();
      return true;
    },
  });
  const client = browser();
  await signup(client);
  const native = { Cookie: client.cookie(), Origin: 'null', 'sec-fetch-site': 'same-origin' };
  const noCsrf = await fetch(base + '/form/trial', { method: 'POST', headers: native });
  assert.equal((await noCsrf.json()).code, 'csrf_invalid');
  native['x-csrf-token'] = client.csrf();
  const crossSite = await fetch(base + '/form/trial', {
    method: 'POST', headers: { ...native, 'sec-fetch-site': 'cross-site' },
  });
  assert.equal((await crossSite.json()).code, 'invalid_origin');
  const api = await client.request('trial', {}, { Origin: 'null', 'sec-fetch-site': 'same-origin' });
  assert.equal(api.body.code, 'invalid_origin');
  const result = await fetch(base + '/form/trial', { method: 'POST', headers: native });
  assert.equal(result.status, 204);
});
