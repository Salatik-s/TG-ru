import { fileURLToPath } from 'node:url';

export const storeDatabase = fileURLToPath(new URL('../.private/local-store/store.sqlite', import.meta.url));
