import { createServer } from 'node:http';
import { createStore, StoreError } from './store.mjs';

const SESSION_COOKIE = '__Host-tgru_store_local';
const MAX_BODY = 8192;
const WEB_ACTIONS = new Set(['register', 'login', 'recover', 'logout', 'trial', 'mock-purchase', 'devices/revoke']);
const DEVICE_ACTIONS = new Set(['activation-token', 'activate', 'device/challenge', 'device/renew']);

function cookieValue(request) {
  const pair = request.headers.cookie?.split(';').map((value) => value.trim())
    .find((value) => value.startsWith(SESSION_COOKIE + '='));
  return pair?.slice(SESSION_COOKIE.length + 1);
}

function writeCookie(response, session) {
  response.setHeader('Set-Cookie', `${SESSION_COOKIE}=${session.rawToken}; Path=/; Max-Age=${session.duration}; HttpOnly; Secure; SameSite=Strict`);
}

function json(response, status, payload) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify(payload));
}

function localUrl(request) {
  if (request.socket.remoteAddress !== '127.0.0.1'
      || !/^(?:127\.0\.0\.1|localhost)(?::[0-9]{1,5})?$/.test(request.headers.host ?? '')) {
    throw new StoreError(400, 'invalid_host');
  }
  const base = 'http://' + request.headers.host;
  const url = new URL(request.url, base);
  const site = request.headers['sec-fetch-site'];
  const origin = request.headers.origin;
  // Chrome/Edge may use a null Origin for native forms under Referrer-Policy: no-referrer.
  const nativeForm = origin === 'null' && site === 'same-origin' && url.pathname.startsWith('/form/');
  if (request.method === 'POST' && (site === 'cross-site' || (origin && origin !== base && !nativeForm))) {
    throw new StoreError(403, 'invalid_origin');
  }
  return url;
}

async function parseJson(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] ?? '')) {
    throw new StoreError(415, 'unsupported_media_type');
  }
  if (Number(request.headers['content-length']) > MAX_BODY) {
    request.resume();
    throw new StoreError(413, 'body_too_large');
  }
  const chunks = [];
  let total = 0;
  for await (const chunk of request.iterator({ destroyOnReturn: false })) {
    total += chunk.length;
    if (total > MAX_BODY) {
      request.resume();
      throw new StoreError(413, 'body_too_large');
    }
    chunks.push(chunk);
  }
  let parsed;
  try {
    parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new StoreError(400, 'bad_request');
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new StoreError(400, 'bad_request');
  }
  return parsed;
}

function securityHeaders(response) {
  response.setHeader('Content-Security-Policy', "default-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
  response.setHeader('Referrer-Policy', 'no-referrer');
  response.setHeader('X-Content-Type-Options', 'nosniff');
  response.setHeader('Cache-Control', 'no-store');
}

export function createStoreServer({ pagesHandler, ...storeOptions } = {}) {
  const store = createStore(storeOptions);
  const server = createServer(async (request, response) => {
    securityHeaders(response);
    try {
      const url = localUrl(request);
      const path = url.pathname;
      store.purge();
      if (path === '/health' && request.method === 'GET') {
        return json(response, 200, { ok: true, local: true });
      }

      let session = store.findSession(cookieValue(request));
      function getSession() {
        if (!session) {
          session = store.newSession();
          writeCookie(response, session);
        }
        return { ok: true, ...store.sessionData(session) };
      }

      async function submit(action, data, csrf) {
        const outcome = await store.webAction(action, data, session, csrf);
        if (outcome.session) {
          session = outcome.session;
          writeCookie(response, session);
        }
        return { ok: true, ...store.sessionData(session), ...outcome.result };
      }

      if (path === '/api/store/session' && request.method === 'GET') {
        return json(response, 200, getSession());
      }
      if (path.startsWith('/api/store/')) {
        const action = path.slice('/api/store/'.length);
        if (!WEB_ACTIONS.has(action) && !DEVICE_ACTIONS.has(action)) {
          throw new StoreError(404, 'not_found');
        }
        if (request.method !== 'POST') {
          response.setHeader('Allow', 'POST');
          throw new StoreError(405, 'method_not_allowed');
        }
        const data = await parseJson(request);
        if (WEB_ACTIONS.has(action)) {
          return json(response, 200, await submit(action, data, request.headers['x-csrf-token']));
        }
        return json(response, 200, { ok: true, ...await store.deviceAction(action, data) });
      }
      if (pagesHandler && await pagesHandler(request, response, { getSession, submit })) return;
      throw new StoreError(404, 'not_found');
    } catch (error) {
      if (response.headersSent) {
        response.end();
        return;
      }
      const known = error instanceof StoreError;
      const payload = { ok: false, code: known ? error.code : 'internal_error' };
      if (known && error.retryAfter) {
        response.setHeader('Retry-After', String(error.retryAfter));
        payload.retry_after = error.retryAfter;
      }
      json(response, known ? error.status : 500, payload);
    }
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 5000;
  server.on('close', () => store.close());
  return server;
}
