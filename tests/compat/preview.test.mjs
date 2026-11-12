import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { once } from 'node:events';
import { readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { test } from 'node:test';

const gatewayDir = process.env.COMPAT_GATEWAY_DIR
  ? pathToFileURL(`${resolve(process.env.COMPAT_GATEWAY_DIR)}/`)
  : new URL('../../server/', import.meta.url);
const { Access } = await import(new URL('access.mjs', gatewayDir));
const { Gateway } = await import(new URL('gateway.mjs', gatewayDir));
const { apiServer } = await import(new URL('http.mjs', gatewayDir));
const { PushRelay } = await import(new URL('push.mjs', gatewayDir));
const golden = JSON.parse(readFileSync(new URL('./golden/access-v1.json', import.meta.url), 'utf8'));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');

async function fixture(t) {
  let now = 1_800_000_000;
  const access = new Access(':memory:', { pepper: randomBytes(32), now: () => now });
  const gateway = new Gateway({ host: 'gateway.invalid', serverName: 'cover.invalid',
    publicKey: 'fixture-public-key', shortId: 'aabb' }, tmpdir());
  gateway.ready = true;
  const push = new PushRelay(access, { base: 'https://gateway.invalid:8443' });
  const server = apiServer(access, gateway, { push });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    push.close();
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
    access.close();
  });

  const base = `http://127.0.0.1:${server.address().port}`;
  const key = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const der = key.publicKey.export({ type: 'spki', format: 'der' });
  const id = sha256(der);

  async function post(path, payload) {
    const reply = await fetch(base + path, {
      method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(payload), signal: AbortSignal.timeout(5000),
    });
    return { status: reply.status, body: await reply.json() };
  }

  async function request(action, payload = {}) {
    const challenge = await post('/v1/challenge', { public_key: der.toString('base64'), action, payload });
    if (challenge.status !== 200) return { reply: challenge };
    const { challenge_id, device_id, expires_at, message } = challenge.body;
    assert.deepEqual(Object.keys(challenge.body).sort(), ['challenge_id', 'device_id', 'expires_at', 'message']);
    assert.equal(device_id, id);
    assert.equal(expires_at, now + 120);
    assert.match(challenge_id, /^[a-f0-9-]{36}$/);
    const [protocol, messageId, nonce, messageAction, commitment, ...extra] = message.split('\n');
    assert.equal(protocol, 'familygram-v1');
    assert.equal(messageId, challenge_id);
    assert.match(nonce, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(messageAction, action);
    assert.match(commitment, /^[a-f0-9]{64}$/);
    assert.deepEqual(extra, []);
    if (action !== 'invite') assert.equal(commitment, sha256('{}'));

    const proof = { challenge_id, signature: sign('sha256', Buffer.from(message), key.privateKey).toString('base64') };
    return { reply: await post('/v1/complete', proof), proof };
  }

  function normalize(reply) {
    const normalized = structuredClone(reply);
    const body = normalized.body;
    if ('device_id' in body) {
      assert.equal(body.device_id, id);
      assert.equal(body.server_time, now);
      body.device_id = '<device>';
      body.server_time = 0;
    }
    // This field was introduced as optional by the Android connectivity branch.
    if ('developer_username' in body) {
      assert.match(body.developer_username, /^[A-Za-z][A-Za-z0-9_]{3,31}$/);
      delete body.developer_username;
    }
    if (body.gateway) {
      assert.match(body.gateway.uuid, /^[a-f0-9-]{36}$/);
      assert.equal(body.gateway_fallbacks[0].uuid, body.gateway.uuid);
      body.gateway.uuid = body.gateway_fallbacks[0].uuid = '<uuid>';
      const notifications = body.notifications;
      assert.match(notifications.token, /^[A-Za-z0-9_-]{43}$/);
      const endpoint = new URL(notifications.endpoint);
      const [, prefix, route, installation, secret] = endpoint.pathname.split('/');
      assert.equal(endpoint.origin, 'https://gateway.invalid:8443');
      assert.deepEqual([prefix, route, installation], ['v1', 'push', id]);
      assert.match(secret, /^[A-Za-z0-9_-]{43}$/);
      notifications.endpoint = 'https://gateway.invalid:8443/v1/push/<device>/<secret>';
      notifications.token = '<token>';
      notifications.expires_at -= now;
    }
    return normalized;
  }

  async function events(token) {
    const reply = await fetch(base + '/v1/events', {
      headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(5000),
    });
    await reply.body.cancel();
    return reply.status;
  }
  return { access, id, request, normalize, events, post, now: () => now, advance: (seconds) => { now += seconds; } };
}

for (let preview = 9; preview <= 17; preview += 1) {
  test(`preview${preview} wire fixture: C1 approval and C2 fifteen minute renewals`, async (t) => {
    const f = await fixture(t);
    assert.deepEqual(f.normalize((await f.request('status')).reply), golden.new);
    const invite = f.access.owner('invite');
    const pending = await f.request('invite', { code: invite.code, name: `preview${preview} fixture` });
    assert.deepEqual(f.normalize(pending.reply), golden.pending);
    assert.deepEqual(await f.post('/v1/complete', pending.proof), golden.replay);
    assert.deepEqual((await f.request('config')).reply, golden.pending_config);
    f.access.owner('approve', f.id);
    let config = (await f.request('config')).reply;
    assert.deepEqual(f.normalize(config), golden.config);
    const deviceUuid = config.body.gateway.uuid;

    for (let minute = 1; minute <= 15; minute += 1) {
      f.advance(60);
      assert.ok(config.body.notifications.expires_at > f.now());
      assert.equal(await f.events(config.body.notifications.token), 200);
      config = (await f.request('config')).reply;
      assert.deepEqual(f.normalize(config), golden.config);
      assert.equal(config.body.gateway.uuid, deviceUuid);
    }
    f.advance(75);
    assert.equal(await f.events(config.body.notifications.token), 401);
  });
}
