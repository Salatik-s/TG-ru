import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac, generateKeyPairSync, randomBytes } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { Gateway, xrayConfig } from '../gateway.mjs';
import { createStoreGateway, TELEGRAM_CIDRS } from '../store-gateway.mjs';

const secret = randomBytes(32);
const device = 'a'.repeat(64);
const nowSeconds = () => Math.floor(Date.now() / 1000);

function signed(body, { time = Date.now(), nonce = randomBytes(16).toString('hex') } = {}) {
  return {
    'content-type': 'application/json',
    'x-store-time': String(time),
    'x-store-nonce': nonce,
    'x-store-signature': createHmac('sha256', secret).update(`${time}\n${nonce}\n${body}`).digest('hex')
  };
}

function claims({ id = device, access = nowSeconds() + 300, lease = nowSeconds() + 60 } = {}) {
  return JSON.stringify({ device_id: id, access_until: access, lease_until: lease });
}

async function start(t, gateway) {
  const errors = [];
  const control = createStoreGateway({ secret, gateway, onError: error => errors.push(error) });
  t.after(() => control.close());
  await new Promise(resolve => control.server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${control.server.address().port}`;
  return {
    errors,
    request: (body, headers = signed(body), path = '/internal/store/lease') =>
      fetch(base + path, { method: 'POST', headers, body })
  };
}

function fakeGateway() {
  return {
    ready: false,
    applied: [],
    async reload(source) { this.applied.push(source()); this.ready = true; },
    client(lease) { return { uuid: lease.uuid }; },
    async stop() { this.ready = false; }
  };
}

test('requires a recent signed lease and consumes each nonce once', async t => {
  const gateway = fakeGateway();
  const { request } = await start(t, gateway);
  const body = claims();
  const headers = signed(body);
  assert.equal((await request(body, { 'content-type': 'application/json' })).status, 401);
  assert.equal((await request(`${body} `, headers)).status, 401);
  assert.equal((await request(body, signed(body, { time: Date.now() - 31000 }))).status, 401);
  assert.equal((await request(body, signed(body, { time: Date.now() + 6000 }))).status, 401);
  assert.equal((await request(body, headers, '/v1/config')).status, 404);
  assert.equal((await request(claims({ lease: nowSeconds() + 90 }))).status, 400);
  assert.equal((await request(claims({ lease: nowSeconds() - 1 }))).status, 400);
  assert.equal((await request(claims({ id: '../legacy' }))).status, 400);
  assert.equal((await request('x'.repeat(1025))).status, 413);
  assert.equal(gateway.applied.length, 0);

  const first = await request(body, headers);
  assert.equal(first.status, 200);
  assert.match((await first.json()).gateway.uuid, /^[a-f0-9-]{36}$/);
  assert.equal((await request(body, headers)).status, 409);
  assert.equal(gateway.applied.length, 1);
});

test('keeps UUID on a live renewal and tears down the allow-list when a lease expires', async t => {
  const gateway = fakeGateway();
  const { request } = await start(t, gateway);
  const started = nowSeconds();
  const initial = await (await request(claims({ lease: started + 2 }))).json();
  const renewed = await (await request(claims({ lease: started + 3 }))).json();
  assert.equal(renewed.gateway.uuid, initial.gateway.uuid);
  assert.equal(gateway.applied.length, 1);
  assert.equal((await request(claims({ lease: started + 2 }))).status, 409);
  await delay(Math.max(100, (started + 3) * 1000 - Date.now() + 150));
  assert.deepEqual(gateway.applied.at(-1), []);
  const admittedAgain = await (await request(claims())).json();
  assert.notEqual(admittedAgain.gateway.uuid, initial.gateway.uuid);
});

test('does not return a credential when Xray failed to load it', async t => {
  const gateway = fakeGateway();
  gateway.reload = async source => { gateway.applied.push(source()); throw new Error('fixture unavailable'); };
  const { request, errors } = await start(t, gateway);
  const response = await request(claims());
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: 'gateway_unavailable' });
  assert.equal(errors.length, 1);
  assert.deepEqual(gateway.applied.at(-1), []);
});

test('isolated Gateway is Telegram-only and adds aggregate stats exclusively for QA', () => {
  const config = { serverName: 'www.microsoft.com' };
  const options = { listenAddress: '127.0.0.1', realityPort: 28443, realityOnly: true, destinationCidrs: TELEGRAM_CIDRS };
  const production = xrayConfig(config, [], null, options);
  assert.deepEqual(production.inbounds.map(({ listen, port }) => [listen, port]), [['127.0.0.1', 28443]]);
  assert.equal(production.api, undefined);
  assert.equal(production.outbounds[0].protocol, 'blackhole');
  assert.deepEqual(production.routing.rules[0].ip, TELEGRAM_CIDRS);
  assert.equal(production.routing.rules[0].outboundTag, 'block');
  assert.equal(production.routing.domainStrategy, 'AsIs');
  const qa = xrayConfig(config, [], null, { ...options, allowDirectForTests: true, qaStatsPort: 28444 });
  assert.deepEqual(qa.api, { tag: 'qa-stats', listen: '127.0.0.1:28444', services: ['StatsService'] });
  assert.equal(qa.routing.rules[0].outboundTag, 'test-direct');
  const proxiedQa = xrayConfig(config, [], null, { ...options, allowDirectForTests: true, qaSocksPort: 10808 });
  assert.equal(proxiedQa.routing.rules[0].outboundTag, 'test-socks');
  assert.deepEqual(proxiedQa.outbounds[1].settings.servers, [{ address: '127.0.0.1', port: 10808 }]);
  assert.deepEqual(xrayConfig(config, []).inbounds.map(({ port }) => port), [18443, 10000]);
});

async function allocatePort() {
  const socket = createServer();
  await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve));
  const { port } = socket.address();
  await new Promise(resolve => socket.close(resolve));
  return port;
}

test('real isolated Xray starts with the admitted UUID and removes it on expiry', {
  skip: !process.env.XRAY_TEST_BINARY, timeout: 15000
}, async t => {
  const dir = mkdtempSync(join(tmpdir(), 'tg-ru-store-gateway-test-'));
  const key = generateKeyPairSync('x25519');
  const gateway = new Gateway({
    host: '127.0.0.1', serverName: 'www.microsoft.com', shortId: randomBytes(8).toString('hex'),
    privateKey: key.privateKey.export({ format: 'jwk' }).d,
    publicKey: key.publicKey.export({ format: 'jwk' }).x
  }, dir, process.env.XRAY_TEST_BINARY, {
    listenAddress: '127.0.0.1', realityOnly: true, realityPort: await allocatePort(),
    destinationCidrs: TELEGRAM_CIDRS, allowDirectForTests: true, qaStatsPort: await allocatePort()
  });
  const { request, errors } = await start(t, gateway);
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const deadline = nowSeconds() + 3;
  const response = await request(claims({ lease: deadline }));
  assert.equal(response.status, 200);
  assert.equal((await response.json()).gateway.port, gateway.options.realityPort);
  const admittedChild = gateway.child;
  await delay(Math.max(200, (deadline + 1) * 1000 - Date.now()));
  assert.notEqual(gateway.child, admittedChild);
  assert.equal(gateway.ready, true);
  assert.equal(errors.length, 0);
});
