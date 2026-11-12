import { existsSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { storeDatabase } from './database-path.mjs';
import { createStoreServer } from './http.mjs';
import { gatewayClient } from './gateway-client.mjs';

const host = '127.0.0.1';
const port = 48185;
const pages = new URL('../web/local-store/pages.mjs', import.meta.url);
const mockPayments = process.env.TG_RU_STORE_MOCK_PAYMENTS === '1';
const secret = process.env.STORE_GATEWAY_SECRET;
const publicOrigin = process.env.TG_RU_STORE_PUBLIC_ORIGIN;
if (publicOrigin && (!secret || mockPayments)) throw new Error('Public Store requires a gateway and disables simulated payments');
const authorizeGateway = secret === undefined ? undefined : gatewayClient({
  secret,
  port: Number(process.env.STORE_GATEWAY_CONTROL_PORT ?? 48186),
});

mkdirSync(dirname(storeDatabase), { recursive: true });
const { handleStorePages } = existsSync(pages) ? await import(pages) : {};
const server = createStoreServer({
  database: storeDatabase,
  allowMockPurchases: mockPayments,
  pagesHandler: handleStorePages,
  authorizeGateway,
  publicOrigin,
});
server.listen(port, host, () => {
  process.stdout.write(`TG-ru Store: http://${host}:${port} (mock payments ${mockPayments ? 'enabled' : 'disabled'})\n`);
});
