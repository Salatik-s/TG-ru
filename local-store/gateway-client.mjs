import { createHmac, randomBytes } from 'node:crypto';
import { StoreError } from './store.mjs';

export function gatewayClient({ secret, port = 48186, now = Date.now }) {
  if (!/^[0-9a-f]{64}$/.test(secret ?? '') || !Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('Invalid Store gateway configuration');
  }
  const key = Buffer.from(secret, 'hex');
  const endpoint = `http://127.0.0.1:${port}/internal/store/lease`;
  return async (lease) => {
    const time = String(now());
    const nonce = randomBytes(16).toString('hex');
    const body = JSON.stringify(lease);
    const signature = createHmac('sha256', key).update(`${time}\n${nonce}\n${body}`).digest('hex');
    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        redirect: 'error',
        signal: AbortSignal.timeout(5_000),
        headers: {
          'content-type': 'application/json',
          'x-store-time': time,
          'x-store-nonce': nonce,
          'x-store-signature': signature,
        },
        body,
      });
      if (response.ok) {
        const { gateway } = await response.json();
        if (gateway?.protocol === 'vless' && gateway.security === 'reality') return gateway;
      }
    } catch {
      // Control-plane failures must not turn a subscription into an open route.
    }
    throw new StoreError(503, 'gateway_unavailable');
  };
}
