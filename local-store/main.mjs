import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { storeDatabase } from './database-path.mjs';
import { createStoreServer } from './http.mjs';
import { gatewayClient } from './gateway-client.mjs';

const host = '127.0.0.1';
const port = 48185;
const pages = new URL('../web/local-store/preview-pages.mjs', import.meta.url);
const mockPayments = process.env.TG_RU_STORE_MOCK_PAYMENTS === '1';
const secret = process.env.STORE_GATEWAY_SECRET;
const publicOrigin = process.env.TG_RU_STORE_PUBLIC_ORIGIN;
if (publicOrigin && (!secret || mockPayments)) throw new Error('Public Store requires a gateway and disables simulated payments');
let release;
if (publicOrigin) {
  const data = JSON.parse(readFileSync(process.env.TG_RU_STORE_RELEASE_FILE, 'utf8'));
  if (!/^\d+\.\d+\.\d+(?:-[a-z0-9-]+)?$/.test(data.version_name ?? '')
      || !Number.isSafeInteger(data.version_code) || data.version_code < 1
      || !Number.isSafeInteger(data.min_sdk) || data.min_sdk < 24
      || !Number.isSafeInteger(data.bytes) || data.bytes < 1
      || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(data.released_at ?? '')
      || !Number.isFinite(Date.parse(data.released_at))
      || !/^[a-f0-9]{64}$/.test(data.sha256 ?? '')) throw new Error('Invalid public Store APK metadata');
  release = { version_name: data.version_name, version_code: data.version_code, min_sdk: data.min_sdk,
    released_at: data.released_at, bytes: data.bytes, sha256: data.sha256 };
}
const authorizeGateway = secret === undefined ? undefined : gatewayClient({
  secret,
  port: Number(process.env.STORE_GATEWAY_CONTROL_PORT ?? 48186),
});

mkdirSync(dirname(storeDatabase), { recursive: true });
const { handlePreviewPages } = existsSync(pages) ? await import(pages) : {};
const server = createStoreServer({
  database: storeDatabase,
  allowMockPurchases: mockPayments,
  pagesHandler: handlePreviewPages,
  authorizeGateway,
  publicOrigin,
  release,
});
server.listen(port, host, () => {
  process.stdout.write(`TG-ru Store: http://${host}:${port} (mock payments ${mockPayments ? 'enabled' : 'disabled'})\n`);
});
