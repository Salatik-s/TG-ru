import { createHash } from 'node:crypto';
import { createReadStream, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { createStoreServer } from './http.mjs';
import { storeDatabase } from './database-path.mjs';
import { handlePreviewPages } from '../web/local-store/preview-pages.mjs';

const localApk = process.argv[2] ? resolve(process.argv[2]) : undefined;
const releases = {
  '26ded0ec939baa63ee4cb8e77ed5f56cf95aed0b2855f56014f7729c13cee2e4': {
    version_code: 10024, version_name: '0.1.0-preview23-store', released_at: '2026-11-12T22:35:00Z',
  },
  'e00e29122c085fe511c2861ce326750696f47e61a6caeb34c0f799c1d80ee5d7': {
    version_code: 10025, version_name: '0.1.0-preview24-store', released_at: '2026-11-13T00:39:00Z',
  },
  '2d8b023f17602f22aa10bba633180153d3d5b1249c2806898d22338b6eddee48': {
    version_code: 10026, version_name: '0.1.0-preview25-store', released_at: '2026-11-13T06:54:41Z',
  },
  '4840613513b29a11b7d4114b62e4750379a960c5868d7a1739a9bebe94dc66cd': {
    version_code: 10027, version_name: '0.1.0-preview26-store', released_at: '2026-11-13T07:08:53Z',
  },
};
let release;
if (localApk) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(localApk)) hash.update(chunk);
  const sha256 = hash.digest('hex');
  const metadata = releases[sha256];
  if (!metadata) throw new Error('Expected a verified Store APK');
  release = { ...metadata, min_sdk: 24, bytes: statSync(localApk).size, sha256 };
}
createStoreServer({ database: storeDatabase, pagesHandler: handlePreviewPages, localApk, release })
  .listen(48187, '127.0.0.1', () => console.log('TG-ru design preview: http://127.0.0.1:48187/'));
