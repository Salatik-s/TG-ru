import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

const applicationId = 0x5447424c; // TGBL
const migrations = [new URL('./migrations/001-initial.sql', import.meta.url)];

export function openBillingDatabase(path) {
  if (typeof path !== 'string' || !path.trim()) {
    throw new TypeError('A Billing database path is required');
  }

  const database = new DatabaseSync(path, { enableForeignKeyConstraints: true });
  try {
    database.exec('PRAGMA busy_timeout = 5000; PRAGMA recursive_triggers = ON;');
    database.exec('BEGIN IMMEDIATE');
    const { application_id: identity } = database.prepare('PRAGMA application_id').get();
    const { user_version: version } = database.prepare('PRAGMA user_version').get();

    if (identity === 0 && version === 0) {
      const existingObject = database.prepare(`
        SELECT 1 FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' LIMIT 1
      `).get();
      if (existingObject) throw new Error('Database is not an empty Billing database');
      database.exec(`PRAGMA application_id = ${applicationId}`);
    } else if (identity !== applicationId || version < 1 || version > migrations.length) {
      throw new Error('Unsupported Billing database or schema version');
    }

    for (let index = version; index < migrations.length; index += 1) {
      database.exec(readFileSync(migrations[index], 'utf8'));
      database.exec(`PRAGMA user_version = ${index + 1}`);
    }
    database.exec('COMMIT');
    database.exec('PRAGMA secure_delete = ON');
    return database;
  } catch (error) {
    // Closing rolls back any open migration transaction and releases its lock.
    database.close();
    throw error;
  }
}
