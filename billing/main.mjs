import { isAbsolute, join } from 'node:path';
import { event, listenConfig, readOnly, serve, status } from '../foundation/http.mjs';
import { openBillingDatabase } from './database.mjs';

try {
  const config = listenConfig(process.env, 'TG_RU_BILLING', 8081);
  const directory = process.env.TG_RU_BILLING_DATA_DIR;
  if (!directory || !isAbsolute(directory)) throw new Error('invalid_billing_data_directory');

  const database = openBillingDatabase(join(directory, 'billing.sqlite'));
  const check = database.prepare('SELECT 1 FROM accounts LIMIT 1');
  await serve(config, (request, response) => {
    if (request.url !== '/health') return status(response, 404, 'not_found');
    if (!readOnly(request, response)) return;
    try {
      check.get();
      status(response, 200, 'ok');
    } catch {
      status(response, 503, 'unavailable');
    }
  }, () => database.close());
  event('billing_ready');
} catch {
  event('billing_start_failed', true);
  process.exitCode = 1;
}
