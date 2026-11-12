import { existsSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { storeDatabase } from './database-path.mjs';
import { createStoreServer } from './http.mjs';

const host = '127.0.0.1';
const port = 48185;
const pages = new URL('../web/local-store/pages.mjs', import.meta.url);
const mockPayments = process.env.TG_RU_STORE_MOCK_PAYMENTS === '1';

mkdirSync(dirname(storeDatabase), { recursive: true });
const { handleStorePages } = existsSync(pages) ? await import(pages) : {};
const server = createStoreServer({
  database: storeDatabase,
  allowMockPurchases: mockPayments,
  pagesHandler: handleStorePages,
});
server.listen(port, host, () => {
  process.stdout.write(`TG-ru Store: http://${host}:${port} (mock payments ${mockPayments ? 'enabled' : 'disabled'})\n`);
});
