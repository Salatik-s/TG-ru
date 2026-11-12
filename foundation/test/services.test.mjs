import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const cwd = fileURLToPath(new URL('../../', import.meta.url));

async function openPort() {
  const listener = createServer().listen(0, '127.0.0.1');
  await once(listener, 'listening');
  const { port } = listener.address();
  await new Promise((resolve) => listener.close(resolve));
  return String(port);
}

async function start(t, name, env = {}) {
  const port = await openPort();
  const prefix = `TG_RU_${name.toUpperCase()}`;
  const child = spawn(process.execPath, [`${name}/main.mjs`], {
    cwd, env: { ...process.env, ...env, [`${prefix}_HOST`]: '127.0.0.1', [`${prefix}_PORT`]: port },
  });
  const exited = once(child, 'exit');
  let logs = '';
  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  child.stdout.on('data', (text) => { logs += text; });
  child.stderr.on('data', (text) => { logs += text; });
  t.after(async () => {
    child.kill();
    await exited;
  });

  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${name} startup timed out`)), 5000);
    exited.then(() => reject(new Error(`${name} exited before readiness`)), reject);
    child.stdout.on('data', () => {
      if (logs.includes(`"code":"${name}_ready"`)) resolve();
    });
    child.once('exit', () => clearTimeout(timer));
    child.stdout.once('end', () => clearTimeout(timer));
    timer.unref();
  });
  await ready;
  return {
    pid: child.pid,
    logs: () => logs,
    request: (path, options) => fetch(`http://127.0.0.1:${port}${path}`, {
      signal: AbortSignal.timeout(5000), ...options,
    }),
  };
}

test('Billing migrates its private data and web serves a standalone Store preview', async (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'tg-ru-foundation-'));
  const billing = await start(t, 'billing', { TG_RU_BILLING_DATA_DIR: directory });
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const web = await start(t, 'web');
  assert.notEqual(web.pid, billing.pid);

  for (const service of [billing, web]) {
    const health = await service.request('/health');
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { status: 'ok' });
    assert.equal(health.headers.get('cache-control'), 'no-store');
    const head = await service.request('/health', { method: 'HEAD' });
    assert.equal(head.status, 200);
    assert.equal(await head.text(), '');
  }

  const db = new DatabaseSync(join(directory, 'billing.sqlite'), { readOnly: true });
  try {
    assert.equal(db.prepare('PRAGMA user_version').get().user_version, 1);
    assert.deepEqual(db.prepare('SELECT id FROM accounts').all(), []);
  } finally {
    db.close();
  }

  const page = await web.request('/');
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-type'), /^text\/html/);
  assert.match(page.headers.get('content-security-policy'), /default-src 'none'/);
  assert.match(await page.text(), /<html lang="ru">[\s\S]*<h1>Неофициальный клиент Telegram со своим шлюзом<\/h1>/);
  assert.match((await web.request('/preview.css')).headers.get('content-type'), /^text\/css/);
  assert.equal((await web.request('/health', { method: 'POST' })).status, 405);
  assert.equal((await web.request('/api/accounts')).status, 404);

  const marker = 'synthetic-secret-never-log';
  assert.equal((await billing.request(`/${marker}`)).status, 404);
  assert.doesNotMatch(billing.logs(), new RegExp(marker));
  assert.equal((await billing.request('/')).status, 404);
});

test('Billing fails before listening on an invalid SQLite file and omits its path from logs', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'tg-ru-invalid-billing-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  writeFileSync(join(directory, 'billing.sqlite'), 'invalid sqlite fixture');
  const run = spawnSync(process.execPath, ['billing/main.mjs'], {
    cwd, env: { ...process.env, TG_RU_BILLING_DATA_DIR: directory }, encoding: 'utf8', timeout: 5000,
  });
  assert.equal(run.status, 1);
  assert.equal(run.stdout, '');
  assert.equal(JSON.parse(run.stderr).code, 'billing_start_failed');
  assert.ok(!run.stderr.includes(directory));
});
