import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { test } from 'node:test';
import { createStoreServer } from '../../local-store/http.mjs';
import { handleStorePages } from './pages.mjs';

test('Store registration, trial and support use server-rendered forms', async (t) => {
  const server = createStoreServer({ pagesHandler: handleStorePages, allowMockPurchases: true });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())));

  const base = 'http://127.0.0.1:' + server.address().port;
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
