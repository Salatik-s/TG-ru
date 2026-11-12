import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';

// Telegram's published data-center networks, https://core.telegram.org/resources/cidr.txt
export const TELEGRAM_CIDRS = Object.freeze([
  '91.108.56.0/22', '91.108.4.0/22', '91.108.8.0/22', '91.108.16.0/22', '91.108.12.0/22',
  '149.154.160.0/20', '91.105.192.0/23', '91.108.20.0/22', '185.76.151.0/24',
  '2001:b28:f23d::/48', '2001:b28:f23f::/48', '2001:67c:4e8::/48', '2001:b28:f23c::/48', '2a0a:f280::/32'
]);

const CONTROL_PATH = '/internal/store/lease';
const MAX_LEASE_SECONDS = 75;
const MAX_BODY_BYTES = 1024;
const MAX_SIGNATURE_AGE_MS = 30000;
const MAX_FUTURE_SKEW_MS = 5000;
const MAX_NONCES = 4096;
const MAX_DEVICES = 2048;

class ControlError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
  }
}

function send(res, status, value) {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  res.end(JSON.stringify(value));
}

async function readBody(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req.iterator({ destroyOnReturn: false })) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new ControlError(413, 'body_too_large');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function parseClaims(body, nowSeconds) {
  let claims;
  try { claims = JSON.parse(body.toString('utf8')); }
  catch { throw new ControlError(400, 'invalid_claims'); }
  if (!claims || Array.isArray(claims) || typeof claims !== 'object' || Object.keys(claims).length !== 3 ||
      typeof claims.device_id !== 'string' || !/^[a-f0-9]{64}$/.test(claims.device_id) ||
      !Number.isSafeInteger(claims.access_until) || !Number.isSafeInteger(claims.lease_until) ||
      claims.lease_until <= nowSeconds || claims.lease_until > nowSeconds + MAX_LEASE_SECONDS ||
      claims.lease_until > claims.access_until) {
    throw new ControlError(400, 'invalid_claims');
  }
  return claims;
}

export function createStoreGateway({ secret, gateway, selectedRoute = () => null, now = Date.now, onError = () => {} }) {
  if (!Buffer.isBuffer(secret) || secret.length !== 32) throw new Error('Store Gateway requires a 32-byte signing key');
  const nonces = new Map();
  const leases = new Map();
  let work = Promise.resolve();
  let timer;
  let stopping = false;
  const seconds = () => Math.floor(now() / 1000);

  function serialize(operation) {
    const result = work.then(operation);
    work = result.catch(() => {});
    return result;
  }

  function removeExpired(at) {
    let changed = false;
    for (const [id, lease] of leases) {
      if (lease.leaseUntil <= at) {
        leases.delete(id);
        changed = true;
      }
    }
    return changed;
  }

  function scheduleExpiry() {
    clearTimeout(timer);
    if (!leases.size || stopping) return;
    const next = Math.min(...Array.from(leases.values(), lease => lease.leaseUntil));
    timer = setTimeout(() => {
      void serialize(async () => {
        try { if (removeExpired(seconds())) await reload(); }
        finally { scheduleExpiry(); }
      }).catch(onError);
    }, Math.max(1, next * 1000 - now()));
    timer.unref();
  }

  function reload() {
    return gateway.reload(() => Array.from(leases.values()).filter(lease => lease.leaseUntil > seconds()), selectedRoute);
  }

  function authenticate(req, body) {
    const timestamp = req.headers['x-store-time'];
    const nonce = req.headers['x-store-nonce'];
    const signature = req.headers['x-store-signature'];
    const at = now();
    if (typeof timestamp !== 'string' || !/^\d{13}$/.test(timestamp) ||
        typeof nonce !== 'string' || !/^[a-f0-9]{32}$/.test(nonce) ||
        typeof signature !== 'string' || !/^[a-f0-9]{64}$/.test(signature) ||
        Number(timestamp) <= at - MAX_SIGNATURE_AGE_MS || Number(timestamp) > at + MAX_FUTURE_SKEW_MS) {
      throw new ControlError(401, 'invalid_proof');
    }
    const expected = createHmac('sha256', secret).update(`${timestamp}\n${nonce}\n`).update(body).digest();
    if (!timingSafeEqual(expected, Buffer.from(signature, 'hex'))) throw new ControlError(401, 'invalid_proof');
    for (const [key, expiresAt] of nonces) if (expiresAt <= at) nonces.delete(key);
    if (nonces.has(nonce)) throw new ControlError(409, 'replayed_proof');
    if (nonces.size >= MAX_NONCES) throw new ControlError(429, 'proof_capacity');
    nonces.set(nonce, Number(timestamp) + MAX_SIGNATURE_AGE_MS);
  }

  async function admit(body) {
    if (stopping) throw new ControlError(503, 'gateway_unavailable');
    const at = seconds();
    const { device_id: id, access_until: accessUntil, lease_until: leaseUntil } = parseClaims(body, at);
    const expired = removeExpired(at);
    const previous = leases.get(id);
    try {
      if (expired) await reload();
      if (previous && (previous.leaseUntil > leaseUntil || previous.accessUntil > accessUntil)) {
        throw new ControlError(409, 'stale_claims');
      }
      if (!previous && leases.size >= MAX_DEVICES) throw new ControlError(429, 'lease_capacity');
      const lease = { uuid: previous?.uuid ?? randomUUID(), leaseUntil, accessUntil };
      leases.set(id, lease);
      try {
        if (!previous || !gateway.ready) await reload();
        if (!gateway.ready || leaseUntil <= seconds()) throw new ControlError(503, 'gateway_unavailable');
      } catch (failure) {
        if (previous && previous.leaseUntil > seconds()) leases.set(id, previous);
        else leases.delete(id);
        await reload();
        throw failure;
      }
      const profile = gateway.client(lease);
      if (gateway.options?.storeWssPort) profile.wss = gateway.storeWss(lease);
      return { gateway: profile };
    } finally {
      scheduleExpiry();
    }
  }

  const server = createServer({ requestTimeout: 5000, headersTimeout: 5000, connectionsCheckingInterval: 1000 }, async (req, res) => {
    try {
      if (req.socket.remoteAddress !== '127.0.0.1' || req.method !== 'POST' || req.url !== CONTROL_PATH) {
        throw new ControlError(404, 'not_found');
      }
      if (req.headers['content-type'] !== 'application/json') throw new ControlError(415, 'invalid_content_type');
      const body = await readBody(req);
      authenticate(req, body);
      send(res, 200, await serialize(() => admit(body)));
    } catch (error) {
      if (!(error instanceof ControlError)) onError(error);
      send(res, error instanceof ControlError ? error.status : 503, {
        error: error instanceof ControlError ? error.message : 'gateway_unavailable'
      });
    }
  });
  server.maxConnections = 128;

  return {
    server,
    refresh() {
      return serialize(async () => {
        if (stopping) return;
        removeExpired(seconds());
        try { await reload(); }
        finally { scheduleExpiry(); }
      });
    },
    async close() {
      stopping = true;
      clearTimeout(timer);
      if (server.listening) server.close();
      await serialize(() => gateway.stop());
      leases.clear();
      nonces.clear();
    }
  };
}
