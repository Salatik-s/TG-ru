import { existsSync } from 'node:fs';
import { storeDatabase } from './database-path.mjs';
import { grantStoreAccess, initializeStoreOwner, MANUAL_GRANT_REASONS, StoreError } from './store.mjs';

const args = process.argv.slice(2);
const usage = 'Usage: node local-store/owner.mjs grant <login> <days> --reason <reason> [--plan pro] [--if-inactive]\n'
  + 'Days: 1–365 for START; 90 for PRO\nReasons: ' + MANUAL_GRANT_REASONS.join(', ') + '\n'
  + '       node local-store/owner.mjs initialize-web | reset-web\n';
const options = args.slice(5);
const plan = options[0] === '--plan' ? options.splice(0, 2)[1] : 'start';
const onlyIfInactive = options.length === 1 && options[0] === '--if-inactive';

if (args.length === 1 && args[0] === '--help') {
  process.stdout.write(usage);
} else if (args.length === 1 && ['initialize-web', 'reset-web'].includes(args[0])) {
  try {
    if (!existsSync(storeDatabase)) throw new Error('Store database not found.');
    const owner = await initializeStoreOwner(storeDatabase, args[0] === 'reset-web');
    process.stdout.write(`\nOwner login: ${owner.login}\nOwner password: ${owner.password}\nAuthenticator key: ${owner.totp}\n`);
    process.stdout.write('Add the key in your authenticator as time-based (30 seconds, SHA1, 6 digits). Save these values privately.\n');
  } catch (error) {
    process.stderr.write('Owner setup failed: ' + error.message + '\n');
    process.exitCode = 1;
  }
} else if (args.length < 5 || args[0] !== 'grant' || args[3] !== '--reason' || !/^[1-9][0-9]{0,2}$/.test(args[2])
    || !['start', 'pro'].includes(plan) || options.length > 0 && !onlyIfInactive) {
  process.stderr.write(usage);
  process.exitCode = 2;
} else if (!existsSync(storeDatabase)) {
  process.stderr.write('Owner grant failed: store_database_not_found\n');
  process.exitCode = 1;
} else {
  try {
    const grant = grantStoreAccess({ database: storeDatabase, login: args[1], days: Number(args[2]), reason: args[4],
      plan, onlyIfInactive });
    process.stdout.write(JSON.stringify(grant) + '\n');
  } catch (error) {
    const code = error instanceof StoreError ? error.code : 'store_database_error';
    process.stderr.write('Owner grant failed: ' + code + '\n');
    process.exitCode = 1;
  }
}
