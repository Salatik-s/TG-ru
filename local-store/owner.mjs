import { existsSync } from 'node:fs';
import { storeDatabase } from './database-path.mjs';
import { grantStoreAccess, MANUAL_GRANT_REASONS, StoreError } from './store.mjs';

const args = process.argv.slice(2);
const usage = 'Usage: node local-store/owner.mjs grant <login> <days> --reason <reason>\n'
  + 'Days: 1–365\nReasons: ' + MANUAL_GRANT_REASONS.join(', ') + '\n';

if (args.length === 1 && args[0] === '--help') {
  process.stdout.write(usage);
} else if (args.length !== 5 || args[0] !== 'grant' || args[3] !== '--reason' || !/^[1-9][0-9]{0,2}$/.test(args[2])) {
  process.stderr.write(usage);
  process.exitCode = 2;
} else if (!existsSync(storeDatabase)) {
  process.stderr.write('Owner grant failed: store_database_not_found\n');
  process.exitCode = 1;
} else {
  try {
    const grant = grantStoreAccess({ database: storeDatabase, login: args[1], days: Number(args[2]), reason: args[4] });
    process.stdout.write(JSON.stringify(grant) + '\n');
  } catch (error) {
    const code = error instanceof StoreError ? error.code : 'store_database_error';
    process.stderr.write('Owner grant failed: ' + code + '\n');
    process.exitCode = 1;
  }
}
