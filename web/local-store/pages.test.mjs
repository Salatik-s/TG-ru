import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { request as httpRequest } from 'node:http';
import { test } from 'node:test';
import { createStoreServer } from '../../local-store/http.mjs';
import { handleStorePages } from './pages.mjs';

test('Store registration, trial and support use server-rendered forms', async (t) => {
  const server = createStoreServer({ pagesHandler: handleStorePages, allowMockPurchases: true });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())));

  const base = 'http://127.0.0.1:' + server.address().port;
  const home = await (await fetch(base)).text();
  assert.match(home, /name="color-scheme" content="dark"/);
  assert.doesNotMatch(home, /id="theme"|content="dark light"/);
  for (const [route, text] of [
    ['/terms', /Проба, цена и срок/],
    ['/privacy', /Какие данные обрабатываем и зачем/],
    ['/visibility', /Как проходит соединение/],
    ['/refunds', /Как рассматривается обращение/],
    ['/compatibility', /Редакции и доступ/],
  ]) {
    const document = await fetch(base + route);
    assert.equal(document.status, 200);
    assert.match(await document.text(), text);
  }
  assert.match(home, /id="gateway-plans"[\s\S]*<h4>DEMO<\/h4>[\s\S]*<h4>START<\/h4>[\s\S]*<h4>PRO<\/h4>/);
  assert.match(home, /id="editor-plans"[\s\S]*<h4>DEMO SECRET<\/h4>[\s\S]*<h4>SECRET<\/h4>[\s\S]*<h4>SECRET\+<\/h4>[\s\S]*CUSTOM/);
  assert.match(home, /disabled>Выбрать PRO<\/button>/);
  const appAccountLink = await fetch(base + '/account', { redirect: 'manual' });
  assert.equal(appAccountLink.status, 303);
  assert.equal(appAccountLink.headers.get('location'), '/cabinet');
  const registration = await fetch(base + '/register');
  assert.equal(registration.status, 200);
  assert.match(registration.headers.get('content-security-policy'), /form-action 'self'/);
  const form = await registration.text();
  const token = form.match(/name="csrf" value="([^"]+)"/)?.[1];
  assert.ok(token);
  const anonCookie = registration.headers.get('set-cookie')?.split(';', 1)[0];
  assert.ok(anonCookie);
  const post = (url, fields, cookie = anonCookie, site = 'same-origin') => fetch(base + url, {
    method: 'POST',
    headers: { cookie, origin: 'null', 'sec-fetch-site': site },
    body: new URLSearchParams(fields),
    redirect: 'manual',
  });
  const registrationFields = {
    csrf: token,
    login: 'store_example',
    password: randomBytes(22).toString('base64url'),
    accepted_terms: 'yes',
  };
  const crossSite = await post('/form/register', registrationFields, anonCookie, 'cross-site');
  assert.equal(crossSite.status, 403);
  const invalid = await post('/form/register', { ...registrationFields, csrf: 'invalid' });
  assert.equal(invalid.status, 403);
  const signedIn = await post('/form/register', registrationFields);
  assert.equal(signedIn.status, 201);
  assert.match(await signedIn.text(), /Сохраните код восстановления/);
  const cookie = signedIn.headers.get('set-cookie')?.split(';', 1)[0];
  assert.ok(cookie);
  assert.notEqual(cookie, anonCookie);

  const overview = await fetch(base + '/cabinet', { headers: { cookie } });
  const overviewBody = await overview.text();
  assert.match(overviewBody, /Попробовать 5 часов бесплатно/);
  assert.match(overviewBody, /<h2>Платежи<\/h2>/);
  const trialToken = overviewBody.match(/name="csrf" value="([^"]+)"/)?.[1];
  const trial = await post('/form/trial', { csrf: trialToken }, cookie);
  assert.equal(trial.status, 303);
  assert.equal(trial.headers.get('location'), '/cabinet');
  const active = await fetch(base + '/cabinet', { headers: { cookie } });
  assert.match(await active.text(), /Пробный период/);

  const checkout = await fetch(base + '/checkout', { headers: { cookie } });
  const checkoutBody = await checkout.text();
  assert.match(checkoutBody, /Оплата временно недоступна\. Для получения доступа обратитесь в поддержку\./);
  assert.match(checkoutBody, /href="https:\/\/t\.me\/prince_skazki"/);
  assert.doesNotMatch(checkoutBody, /mock-purchase|Тестовая оплата/);
  const removedCheckout = await post('/form/mock-purchase', { csrf: trialToken }, cookie);
  assert.equal(removedCheckout.status, 404);
});

test('public Store downloads require a matching logged-in session and a fresh single-use ticket', async (t) => {
  const release = { version_name: '0.1.0-preview22-store', version_code: 10023, bytes: 159328102,
    min_sdk: 24, released_at: '2026-11-13T00:00:00Z', sha256: randomBytes(32).toString('hex') };
  let now = 1_900_000_000;
  const host = 'tg.24projects.ru';
  const server = createStoreServer({ pagesHandler: handleStorePages, publicOrigin: `https://${host}`, release, now: () => now });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  const request = (path, cookie, fields) => new Promise((resolve, reject) => {
    const req = httpRequest(base + path, {
      method: fields ? 'POST' : 'GET',
      headers: { host, ...(cookie ? { cookie } : {}),
        ...(fields ? { origin: `https://${host}`, 'content-type': 'application/x-www-form-urlencoded' } : {}) },
    }, async (res) => {
      try {
        const chunks = [];
        for await (const chunk of res) chunks.push(chunk);
        const headers = new Headers();
        for (const [key, value] of Object.entries(res.headers)) headers.set(key, value);
        resolve(new Response(Buffer.concat(chunks), { status: res.statusCode, headers }));
      } catch (error) {
        reject(error);
      }
    });
    req.on('error', reject);
    req.end(fields ? new URLSearchParams(fields).toString() : undefined);
  });
  assert.equal((await request('/downloads')).headers.get('location'), '/login');
  assert.equal((await request('/downloads/TG-ru.apk')).status, 403);
  const register = await request('/register');
  const anon = register.headers.get('set-cookie').split(';', 1)[0];
  const csrf = (await register.text()).match(/name="csrf" value="([^"]+)"/)[1];
  const created = await request('/form/register', anon, {
    login: 'download_example', password: randomBytes(22).toString('base64url'), csrf, accepted_terms: 'yes',
  });
  assert.equal(created.status, 201);
  const account = created.headers.get('set-cookie').split(';', 1)[0];
  const downloads = await request('/downloads', account);
  const page = await downloads.text();
  const accountCsrf = page.match(/name="csrf" value="([^"]+)"/)[1];
  assert.match(page, /action="\/form\/download"/);
  assert.ok(page.includes(release.sha256));
  assert.equal((await request('/form/download', account, { csrf })).status, 403);
  const issue = () => request('/form/download', account, { csrf: accountCsrf });
  const first = await issue();
  assert.equal(first.status, 303);
  const ticket = first.headers.get('location');
  assert.match(ticket, /^\/downloads\/TG-ru\.apk\?ticket=[A-Za-z0-9_-]{43}$/);
  assert.equal((await request(ticket, anon)).status, 403);
  const [a, b] = await Promise.all([request(ticket, account), request(ticket, account)]);
  assert.deepEqual([a.status, b.status].sort(), [200, 403]);
  assert.equal([a, b].find((response) => response.ok).headers.get('x-accel-redirect'), '/_tg_ru_store_apk');
  const expiring = (await issue()).headers.get('location');
  now += 900;
  assert.equal((await request(expiring, account)).status, 403);
});
