import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { isIPv4 } from 'node:net';
import { fileURLToPath } from 'node:url';

const cwd = fileURLToPath(new URL('.', import.meta.url));
const project = `tg-ru-qa-${randomUUID()}`;
const options = { cwd, env: { ...process.env, TG_RU_WEB_PORT: '0' }, encoding: 'utf8' };
const docker = (...args) => execFileSync('docker', args, options).trim();
const compose = (...args) => docker('compose', '-p', project, '-f', 'compose.yaml', '-f', 'isolation.compose.yaml', ...args);
const run = (service, source, ...args) => compose('exec', '-T', service, 'node', '-e', source, ...args);
function address(service) {
  const ip = docker('inspect', '--format', '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}', compose('ps', '-q', service));
  assert.ok(isIPv4(ip), `Missing container IP for ${service}`);
  return ip;
}

const config = JSON.parse(compose('config', '--format', 'json'));
const { billing, web, 'gateway-probe': gateway } = config.services;
assert.deepEqual(Object.keys(billing.networks), ['billing']);
assert.deepEqual(Object.keys(web.networks), ['web']);
assert.deepEqual(Object.keys(gateway.networks), ['gateway-probe']);
assert.equal(config.networks.billing.internal, true);
assert.equal(billing.user, '10002:10002');
assert.equal(web.user, '10003:10003');
assert.equal(gateway.user, '10001:10001');
assert.equal(billing.ports?.length ?? 0, 0);
assert.equal(web.ports[0].host_ip, '127.0.0.1');
assert.deepEqual(billing.volumes.filter((volume) => !volume.read_only).map((volume) => volume.source), ['billing-data']);
assert.ok(web.volumes.every((volume) => volume.read_only));
console.log('Compose users, networks, ports and mounts validated.');

if (process.argv.includes('--config-only')) process.exit(0);

const checkFiles = `
  const fs = require('node:fs');
  const [own, other] = process.argv.slice(1);
  fs.accessSync(own, fs.constants.R_OK);
  if (fs.existsSync(other)) process.exit(1);
`;
const checkNetwork = `
  const [own, other] = process.argv.slice(1);
  const get = (url) => fetch(url, { signal: AbortSignal.timeout(2000), redirect: 'error' });
  (async () => {
    if (!(await get(own)).ok) throw new Error('own service unavailable');
    try { await get(other); } catch { return; }
    throw new Error('cross-network service reachable');
  })().catch((error) => { console.error(error.message); process.exitCode = 1; });
`;

try {
  compose('up', '--build', '--wait', '--wait-timeout', '75');
  run('billing', checkFiles, '/data/billing.sqlite', '/data/access.sqlite');
  run('gateway-probe', checkFiles, '/data/access.sqlite', '/data/billing.sqlite');
  run('web', `if (require('node:fs').existsSync('/data')) process.exit(1);`);
  const billingUrl = `http://${address('billing')}:8081/health`;
  const gatewayUrl = `http://${address('gateway-probe')}:8080/`;
  run('billing', checkNetwork, billingUrl, gatewayUrl);
  run('gateway-probe', checkNetwork, gatewayUrl, billingUrl);
  run('web', checkNetwork, 'http://127.0.0.1:8082/health', billingUrl);
  console.log('Billing, web and Gateway probe passed database and network isolation checks.');
} finally {
  compose('down', '--volumes', '--remove-orphans');
}
