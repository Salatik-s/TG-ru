import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { isIP } from 'node:net';
import { join, resolve } from 'node:path';
import { EgressManager, loadSnapshot } from './egress.mjs';
import { Gateway } from './gateway.mjs';
import { createStoreGateway, TELEGRAM_CIDRS } from './store-gateway.mjs';

const LEGACY_PORTS = new Set([8080, 8443, 10000, 18443]);

function port(name, defaultPort) {
  const value = Number(process.env[name] ?? defaultPort);
  if (!Number.isInteger(value) || value < 1024 || value > 65535 || LEGACY_PORTS.has(value)) {
    throw new Error(`Invalid dedicated ${name}`);
  }
  return value;
}

function keys(dir) {
  const path = join(dir, 'store-reality.json');
  if (!existsSync(path)) {
    const pair = generateKeyPairSync('x25519');
    const key = {
      privateKey: pair.privateKey.export({ format: 'jwk' }).d,
      publicKey: pair.publicKey.export({ format: 'jwk' }).x,
      shortId: randomBytes(8).toString('hex')
    };
    writeFileSync(path, JSON.stringify(key), { mode: 0o600, flag: 'wx' });
  }
  const key = JSON.parse(readFileSync(path, 'utf8'));
  if (!/^[A-Za-z0-9_-]{43}$/.test(key.privateKey ?? '') || !/^[A-Za-z0-9_-]{43}$/.test(key.publicKey ?? '') ||
      !/^[a-f0-9]{16}$/.test(key.shortId ?? '')) throw new Error('Invalid Store Gateway REALITY keys');
  return { privateKey: key.privateKey, publicKey: key.publicKey, shortId: key.shortId };
}

process.umask(0o077);
const qa = process.env.STORE_GATEWAY_QA === '1';
const host = process.env.STORE_GATEWAY_HOST ?? (qa ? '127.0.0.1' : '');
const controlPort = port('STORE_GATEWAY_CONTROL_PORT', 48186);
const realityPort = port('STORE_GATEWAY_REALITY_PORT', 28443);
const qaStatsPort = qa ? port('STORE_GATEWAY_QA_STATS_PORT', 28444) : null;
const qaSocksPort = qa && process.env.STORE_GATEWAY_QA_SOCKS_PORT ? port('STORE_GATEWAY_QA_SOCKS_PORT') : null;
const hex = process.env.STORE_GATEWAY_SECRET ?? '';
if (!/^[a-f0-9]{64}$/.test(hex)) throw new Error('STORE_GATEWAY_SECRET must contain 64 lowercase hexadecimal characters');
if (new Set([controlPort, realityPort, qaStatsPort]).size !== 3) throw new Error('Store Gateway ports must differ');
if ([controlPort, realityPort, qaStatsPort].includes(qaSocksPort) && qaSocksPort !== null) throw new Error('QA SOCKS port must differ');
if (qa && host !== '127.0.0.1') throw new Error('QA only advertises loopback');
if (!qa && (isIP(host) || !/^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}$/i.test(host))) {
  throw new Error('STORE_GATEWAY_HOST must be the public gateway domain');
}
if (!process.env.STORE_GATEWAY_DIR) throw new Error('STORE_GATEWAY_DIR is required');
if (!qa && !process.env.STORE_GATEWAY_EGRESS_FILE) throw new Error('STORE_GATEWAY_EGRESS_FILE is required outside QA');
const snapshot = qa ? null : loadSnapshot(process.env.STORE_GATEWAY_EGRESS_FILE);
const dir = resolve(process.env.STORE_GATEWAY_DIR, 'store-gateway');
mkdirSync(dir, { recursive: true, mode: 0o700 });
const gateway = new Gateway({ host, serverName: 'www.microsoft.com', ...keys(dir) }, dir, process.env.XRAY_BINARY, {
  allowDirectForTests: qa, realityPort, listenAddress: qa ? '127.0.0.1' : '0.0.0.0',
  realityOnly: true, destinationCidrs: TELEGRAM_CIDRS, qaStatsPort, qaSocksPort
});

let egress;
const control = createStoreGateway({
  secret: Buffer.from(hex, 'hex'), gateway, selectedRoute: () => egress?.selected() ?? null,
  onError: () => console.error('Store Gateway transport unavailable')
});
if (snapshot) {
  egress = new EgressManager(snapshot, {
    statePath: join(dir, 'egress-state.json'), binary: process.env.XRAY_BINARY,
    onChange: () => control.refresh(), log: event => console.log(JSON.stringify(event))
  });
}

try {
  await new Promise((resolve, reject) => {
    control.server.once('error', reject);
    control.server.listen(controlPort, '127.0.0.1', resolve);
  });
  await control.refresh();
} catch {
  await control.close();
  throw new Error('Store Gateway could not bind its dedicated listeners');
}
console.log(`Store Gateway listening on loopback control ${controlPort} and REALITY ${realityPort}`);
void egress?.start();

let stopping = false;
async function stop() {
  if (stopping) return;
  stopping = true;
  await egress?.close();
  await control.close();
}
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
