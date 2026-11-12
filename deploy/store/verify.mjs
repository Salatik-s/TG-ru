import { randomBytes } from 'node:crypto';
import { gatewayClient } from '../../local-store/gateway-client.mjs';
import { probeNativeOutbound, probeReality } from '../../server/egress-probe.mjs';

const wss = process.argv.includes('--wss');
const check = wss ? 'STORE_WSS_VPN_MTPROTO' : 'STORE_REALITY_VPN_MTPROTO';

try {
  const authorize = gatewayClient({ secret: process.env.STORE_GATEWAY_SECRET });
  const now = Math.floor(Date.now() / 1000);
  const profile = await authorize({
    device_id: randomBytes(32).toString('hex'),
    access_until: now + 40,
    lease_until: now + 40,
  });
  if (profile.host !== 'tg.24projects.ru' || profile.port !== 28443) throw new Error('Invalid Store endpoint');
  const options = {
    binary: process.env.XRAY_BINARY,
    gatewayHost: '127.0.0.1',
    timeoutMs: 16_000,
  };
  let result;
  if (wss) {
    const config = profile.wss;
    if (config?.host !== 'tg.24projects.ru' || config.port !== 443 || config.uuid !== profile.uuid ||
        config.path !== '/v1/store-transport') throw new Error('Invalid Store WSS endpoint');
    result = await probeNativeOutbound({
      protocol: 'vless',
      settings: { vnext: [{ address: options.gatewayHost, port: config.port, users: [{ id: config.uuid, encryption: 'none' }] }] },
      streamSettings: {
        network: 'ws', security: 'tls', tlsSettings: { serverName: config.host, alpn: ['http/1.1'] },
        wsSettings: { path: config.path, host: config.host },
      },
    }, options);
  } else {
    result = await probeReality(profile, profile.uuid, options);
  }
  process.stdout.write(JSON.stringify({ check, ...result }) + '\n');
  if (!result.ok) process.exitCode = 1;
} catch {
  process.stdout.write(JSON.stringify({ check, ok: false, code: 'GATEWAY_UNAVAILABLE' }) + '\n');
  process.exitCode = 1;
}
