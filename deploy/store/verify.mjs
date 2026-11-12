import { randomBytes } from 'node:crypto';
import { gatewayClient } from '../../local-store/gateway-client.mjs';
import { probeReality } from '../../server/egress-probe.mjs';

try {
  const authorize = gatewayClient({ secret: process.env.STORE_GATEWAY_SECRET });
  const now = Math.floor(Date.now() / 1000);
  const profile = await authorize({
    device_id: randomBytes(32).toString('hex'),
    access_until: now + 40,
    lease_until: now + 40,
  });
  if (profile.host !== 'tg.24projects.ru' || profile.port !== 28443) throw new Error('Invalid Store endpoint');
  const result = await probeReality(profile, profile.uuid, {
    binary: process.env.XRAY_BINARY,
    gatewayHost: '127.0.0.1',
    timeoutMs: 16_000,
  });
  process.stdout.write(JSON.stringify({ check: 'STORE_REALITY_VPN_MTPROTO', ...result }) + '\n');
  if (!result.ok) process.exitCode = 1;
} catch {
  process.stdout.write('{"check":"STORE_REALITY_VPN_MTPROTO","ok":false,"code":"GATEWAY_UNAVAILABLE"}\n');
  process.exitCode = 1;
}
