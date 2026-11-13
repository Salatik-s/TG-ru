import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { request as httpRequest } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { createStoreServer } from '../../local-store/http.mjs';
import { initializeStoreOwner } from '../../local-store/store.mjs';
import { totpCode } from '../../local-store/owner-auth.mjs';
import { handleStorePages } from './pages.mjs';

test('Store registration, trial and support use server-rendered forms', async (t) => {
  const server = createStoreServer({ pagesHandler: handleStorePages, allowMockPurchases: true });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())));

  const base = 'http://127.0.0.1:' + server.address().port;
  const home = await (await fetch(base)).text();
  assert.match(home, /name="color-scheme" content="light"/);
  assert.match(home, /rel="icon" type="image\/png" sizes="736x736" href="\/tg-ru-mark\.png"/);
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
  assert.match(home, /href="\/checkout\?plan=pro">Выбрать PRO<\/a>/);
  assert.match(home, /На DEMO можно подключить два устройства, на START — три, на PRO — четыре/);
  const gatewayCards = home.split('id="gateway-plans"')[1].split('id="editor-plans"')[0];
  const features = /Расшифровка голосовых и кружочков[\s\S]*Просмотр PDF, Word, TXT и CSV \(экспорт из Excel\)[\s\S]*Запуск Python прямо в чате[\s\S]*Установка дополнительных плагинов/;
  const [demoCard, startCard, proCard] = gatewayCards.split('<article ').slice(1);
  assert.match(demoCard, features);
  assert.match(startCard, features);
  assert.match(proCard, /Всё то же, что и в START[\s\S]*До 4 устройств[\s\S]*Оплата за 90 дней/);
  assert.doesNotMatch(proCard, features);
  assert.match(home, /href="https:\/\/t\.me\/prince_skazki">Написать разработчику<\/a>/);
  assert.doesNotMatch(home, /class="[^"]*plan-tools/);
  assert.match(home, /DEMO SECRET на 30 минут будет действовать то же/);
  assert.match(await (await fetch(base + '/terms')).text(), /id="trial-conditions"[\s\S]*открытый ключ установки/);
  const proCheckout = await (await fetch(base + '/checkout?plan=pro')).text();
  assert.match(proCheckout, /500 ₽ за 90 дней\. До 4 устройств/);
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
  assert.match(overviewBody, /href="\/devices">Управление устройствами<\/a>/);
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

test('the owner can issue, extend and revoke access with a separate password and single-use TOTP', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'tgru-owner-web-'));
  const database = join(directory, 'store.sqlite');
  let now = 1_900_000_000;
  const server = createStoreServer({ database, pagesHandler: handleStorePages, now: () => now });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = 'http://127.0.0.1:' + server.address().port;
  const ownerPath = '/form/owner/';
  const get = (path, cookie) => fetch(base + path, { headers: cookie ? { cookie } : {}, redirect: 'manual' });
  const post = (path, fields, cookie, origin = base) => fetch(base + path, {
    method: 'POST', headers: { origin, ...(cookie ? { cookie } : {}) },
    body: new URLSearchParams(fields), redirect: 'manual',
  });
  const native = (path, data) => fetch(base + '/api/store/' + path, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(data),
  });
  const cookie = response => response.headers.get('set-cookie')?.split(';', 1)[0];
  const csrf = html => html.match(/name="csrf" value="([^"]+)"/)[1];
  const data = { login: 'issued_user', password: randomBytes(24).toString('base64url') };
  try {
    assert.equal(totpCode(Buffer.from('12345678901234567890'), 1), '287082');
    const owner = await initializeStoreOwner(database);
    const stored = new DatabaseSync(database, { readOnly: true });
    try {
      const secret = stored.prepare('SELECT totp_key, password_hash FROM store_owner').get();
      assert.equal(secret.password_hash.length, 32);
      assert.notDeepEqual(Buffer.from(secret.password_hash), Buffer.from(owner.password));
      owner.otp = totpCode(secret.totp_key, Math.floor(now / 30));
    } finally {
      stored.close();
    }
    assert.match(owner.totp, /^[A-Z2-7]{32}$/);
    const userForm = await get('/register');
    const ordinary = await post('/form/register', {
      ...data, login: 'ordinary_user', csrf: csrf(await userForm.text()), accepted_terms: 'yes',
    }, cookie(userForm));
    assert.equal(ordinary.status, 201);
    const ordinaryCookie = cookie(ordinary);
    const ordinaryCsrf = csrf(await (await get('/cabinet', ordinaryCookie)).text());
    assert.equal((await get(ownerPath + 'cabinet')).status, 403);
    assert.equal((await get(ownerPath + 'cabinet', ordinaryCookie)).status, 403);
    for (const path of ['create', 'grant', 'revoke', 'logout']) {
      const response = await post(ownerPath + path, { ...data, csrf: ordinaryCsrf, days: 7, plan: 'pro' }, ordinaryCookie);
      assert.equal(response.status, 403, path);
      assert.equal((await response.json()).code, 'owner_required');
    }
    const login = { login: owner.login, password: owner.password, otp: owner.otp };
    assert.equal((await post(ownerPath + 'login', login, undefined, 'https://foreign.example')).status, 403);
    assert.equal((await post(ownerPath + 'login', { ...login, password: data.password })).status, 401);
    const authenticated = await post(ownerPath + 'login', login, ordinaryCookie);
    assert.equal(authenticated.status, 303);
    assert.equal(authenticated.headers.get('location'), ownerPath + 'cabinet');
    assert.match(authenticated.headers.get('set-cookie'), /^__Host-tgru_owner=.*; HttpOnly; Secure; SameSite=Strict$/);
    const ownerCookie = cookie(authenticated);
    assert.equal((await post(ownerPath + 'login', login)).status, 401);
    assert.equal((await get('/api/store/session', ownerCookie).then(response => response.json())).account, null);
    const panel = await get(ownerPath + 'cabinet', ownerCookie);
    assert.equal(panel.status, 200);
    assert.match(panel.headers.get('cache-control'), /no-store/);
    const ownerCsrf = csrf(await panel.text());
    assert.equal((await post(ownerPath + 'create', { ...data, plan: 'start', days: 7, csrf: ordinaryCsrf }, ownerCookie)).status, 403);
    const created = await post(ownerPath + 'create', { ...data, plan: 'start', days: 7, csrf: ownerCsrf }, ownerCookie);
    assert.equal(created.status, 201);
    assert.match(await created.text(), /Аккаунт создан/);
    const userLogin = await get('/login');
    const issued = await post('/form/login', { ...data, csrf: csrf(await userLogin.text()) }, cookie(userLogin));
    assert.equal(issued.status, 303);
    const account = () => get('/api/store/session', cookie(issued)).then(response => response.json()).then(result => result.account);
    assert.equal((await account()).plan, 'start');
    assert.equal((await account()).access_until, now + 7 * 86_400);
    const dashboard = () => get(ownerPath + 'cabinet?search=issued_user', ownerCookie).then(response => response.text());
    const initial = await dashboard();
    assert.doesNotMatch(initial, new RegExp(data.password));
    const grant = { login: data.login, plan: 'pro', days: 42, csrf: csrf(initial) };
    assert.equal((await post(ownerPath + 'grant', grant, ownerCookie)).status, 303);
    assert.equal((await post(ownerPath + 'grant', grant, ownerCookie)).status, 403);
    assert.equal((await account()).plan, 'pro');
    assert.equal((await account()).access_until, now + 49 * 86_400);
    const challenge = await (await native('activation-token', data)).json();
    const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const proof = (challenge) => sign('sha256', Buffer.from('tg-ru-store-local\n' + challenge.challenge), privateKey).toString('base64');
    const activation = (challenge) => ({
      token: challenge.token, name: 'Test Android', public_key: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
      signature: proof(challenge),
    });
    const admitted = await (await native('activate', activation(challenge))).json();
    assert.ok(admitted.device_id);
    const renewal = await (await native('device/challenge', { device_id: admitted.device_id })).json();
    const revoke = async (confirm) => post(ownerPath + 'revoke', { login: data.login, csrf: csrf(await dashboard()), confirm }, ownerCookie);
    assert.equal((await revoke('no')).status, 400);
    assert.equal((await revoke('yes')).status, 303);
    assert.equal((await account()).active, false);
    assert.equal((await native('activation-token', data)).status, 403);
    assert.equal((await native('device/renew', { token: renewal.token, signature: proof(renewal) })).status, 400);
    const renewed = await (await native('device/challenge', { device_id: admitted.device_id })).json();
    assert.equal((await native('device/renew', { token: renewed.token, signature: proof(renewed) })).status, 403);
    const user = await get('/cabinet', cookie(issued));
    await post('/form/trial', { csrf: csrf(await user.text()) }, cookie(issued));
    assert.equal((await account()).active, false);
    const other = await get('/cabinet', ordinaryCookie);
    await post('/form/trial', { csrf: csrf(await other.text()) }, ordinaryCookie);
    const otherChallenge = await (await native('activation-token', { ...data, login: 'ordinary_user' })).json();
    const duplicate = await (await native('activate', activation(otherChallenge))).json();
    assert.equal(duplicate.code, 'device_linked_elsewhere');
    const logout = await post(ownerPath + 'logout', { csrf: csrf(await dashboard()) }, ownerCookie);
    assert.equal(logout.status, 303);
    assert.equal((await get(ownerPath + 'cabinet', ownerCookie)).status, 403);
    const audit = new DatabaseSync(database, { readOnly: true });
    try {
      assert.deepEqual(audit.prepare('SELECT action FROM store_owner_audit ORDER BY id').all().map(row => row.action),
        ['create', 'grant', 'revoke']);
    } finally {
      audit.close();
    }
  } finally {
    await new Promise(resolve => server.close(resolve));
    rmSync(directory, { recursive: true, force: true });
  }
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
  assert.deepEqual(await (await request('/api/store/release')).json(), {
    ok: true, version_code: 10023, version_name: release.version_name, min_sdk: 24,
    bytes: release.bytes, sha256: release.sha256,
  });
  const landing = await (await request('/')).text();
  const hero = landing.match(/<section class="wrap hero">[\s\S]*?<\/section>/)[0];
  assert.match(hero, /href="\/register">Создать аккаунт<\/a>[\s\S]*href="\/login">Войти<\/a>/);
  assert.doesNotMatch(hero, /Скачать APK/);
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
