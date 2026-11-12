import { DatabaseSync } from 'node:sqlite';
import { createHash, createPublicKey, randomBytes, randomUUID, verify } from 'node:crypto';

export class ApiError extends Error {
  constructor(status, code, retryAfter) { super(code); this.status = status; this.code = code; this.retryAfter = retryAfter; }
}
const hash = value => createHash('sha256').update(value).digest('hex');
const fail = (status, code, retry) => { throw new ApiError(status, code, retry); };

export class Access {
  constructor(path, { now = () => Math.floor(Date.now() / 1000), pepper } = {}) {
    if (!pepper || pepper.length < 32) throw new Error('A private installation pepper is required');
    this.now = now; this.pepper = pepper;
    this.db = new DatabaseSync(path);
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, public_key TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'new', failures INTEGER NOT NULL DEFAULT 0, cooldown INTEGER NOT NULL DEFAULT 0, uuid TEXT, created INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS invites(digest TEXT PRIMARY KEY, expires INTEGER NOT NULL, used_by TEXT);
      CREATE TABLE IF NOT EXISTS challenges(id TEXT PRIMARY KEY, device TEXT NOT NULL, message TEXT NOT NULL, action TEXT NOT NULL, payload TEXT NOT NULL, expires INTEGER NOT NULL, source TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS rates(id TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY, time INTEGER NOT NULL, action TEXT NOT NULL, device TEXT);
      CREATE TABLE IF NOT EXISTS phone_owner(singleton INTEGER PRIMARY KEY CHECK(singleton=1), device_id TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS app_settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
    `);
  }
  tx(fn) {
    this.db.exec('BEGIN IMMEDIATE');
    try { const value = fn(); this.db.exec('COMMIT'); return value; }
    catch (e) { this.db.exec('ROLLBACK'); throw e; }
  }
  audit(action, id = null) { this.db.prepare('INSERT INTO audit(time,action,device) VALUES (?,?,?)').run(this.now(), action, id); }
  rate(ip, kind, max, window) {
    const id = hash(`${this.pepper}:${kind}:${ip}`), now = this.now();
    this.db.prepare('DELETE FROM rates WHERE expires <= ?').run(now);
    const row = this.db.prepare('SELECT * FROM rates WHERE id=?').get(id);
    if (row && row.count >= max) fail(429, 'rate_limited', row.expires - now);
    if (row) this.db.prepare('UPDATE rates SET count=count+1 WHERE id=?').run(id);
    else this.db.prepare('INSERT INTO rates VALUES (?,?,?)').run(id, 1, now + window);
  }
  device(id) { return this.db.prepare('SELECT * FROM devices WHERE id=?').get(id); }
  isPhoneOwner(d) {
    return !!d && d.status==='approved' && this.db.prepare('SELECT device_id FROM phone_owner WHERE singleton=1').get()?.device_id===d.id;
  }
  allowed(d, action) {
    if (d.status === 'blocked' || d.status === 'revoked') fail(403, d.status);
    if (action === 'invite' && d.cooldown > this.now()) fail(429, 'cooldown', d.cooldown - this.now());
  }
  challenge(input, ip) {
    this.rate(ip, 'challenge', 3600, 3600);
    if (!input || !['invite','status','config','manage'].includes(input.action) || typeof input.public_key !== 'string' || input.public_key.length > 200) fail(400, 'invalid_request');
    let key, der;
    try {
      key = createPublicKey({key: Buffer.from(input.public_key, 'base64'), format: 'der', type: 'spki'});
      if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails.namedCurve !== 'prime256v1') fail(400, 'invalid_key');
      der = key.export({format:'der',type:'spki'});
    } catch { fail(400, 'invalid_key'); }
    const id = hash(der), now = this.now();
    let d = this.device(id);
    if (!d) {
      this.rate(ip, 'register', 30, 86400);
      this.db.prepare('INSERT INTO devices(id,public_key,created) VALUES (?,?,?)').run(id, der.toString('base64'), now);
      d = this.device(id);
    }
    this.allowed(d, input.action);
    const payload = input.payload || {};
    let clean = {};
    if (input.action === 'invite') {
      if (typeof payload.code !== 'string' || payload.code.length > 64 || typeof payload.name !== 'string' || payload.name.trim().length < 1 || payload.name.length > 80) fail(400, 'invalid_payload');
      clean = {code_hash: hash(`${this.pepper}:${payload.code.trim().toUpperCase()}`), name: payload.name.trim()};
    } else if(input.action==='manage') {
      if(!this.isPhoneOwner(d))fail(403,'owner_required');
      if(!payload||typeof payload!=='object'||Array.isArray(payload)||!['list','invite','approve','revoke','set_developer'].includes(payload.command))fail(400,'invalid_management');
      const keys=Object.keys(payload).sort().join(',');
      if(payload.command==='list') {
        if(keys!=='command')fail(400,'invalid_management'); clean={command:'list'};
      } else if(payload.command==='invite') {
        if(keys!=='command,hours'||!Number.isInteger(payload.hours)||payload.hours<1||payload.hours>720)fail(400,'invalid_management');
        clean={command:'invite',hours:payload.hours};
      } else if(payload.command==='set_developer') {
        if(keys!=='command,username'||typeof payload.username!=='string'||!/^[A-Za-z][A-Za-z0-9_]{3,31}$/.test(payload.username))fail(400,'invalid_management');
        clean={command:'set_developer',username:payload.username};
      } else {
        if(keys!=='command,device_id'||typeof payload.device_id!=='string'||!/^[a-f0-9]{64}$/.test(payload.device_id))fail(400,'invalid_management');
        if(payload.device_id===d.id)fail(409,'cannot_modify_owner_phone');
        clean={command:payload.command,device_id:payload.device_id};
      }
    }
    this.db.prepare('DELETE FROM challenges WHERE expires <= ?').run(now);
    const source = hash(`${this.pepper}:challenge-source:${ip}`);
    // A public-key observer on another IP cannot exhaust this device's challenge slots.
    const active = this.db.prepare('SELECT count(*) AS n FROM challenges WHERE device=? AND source=?').get(id, source).n;
    if (active >= 5) fail(429, 'too_many_challenges', 120);
    const challenge = randomUUID(), nonce = randomBytes(32).toString('base64url');
    const message = `familygram-v1\n${challenge}\n${nonce}\n${input.action}\n${hash(JSON.stringify(clean))}`;
    this.db.prepare('INSERT INTO challenges VALUES (?,?,?,?,?,?,?)').run(challenge,id,message,input.action,JSON.stringify(clean),now+120,source);
    return {device_id:id,challenge_id:challenge,message,expires_at:now+120};
  }
  complete(input, ip) {
    this.rate(ip, 'complete', 3600, 3600);
    if (!input || typeof input.challenge_id !== 'string' || typeof input.signature !== 'string' || input.signature.length > 200) fail(400, 'invalid_request');
    // Consume before signature validation; even failed proofs cannot be replayed.
    const c = this.tx(() => {
      const row = this.db.prepare('SELECT * FROM challenges WHERE id=?').get(input.challenge_id);
      if (row) this.db.prepare('DELETE FROM challenges WHERE id=?').run(row.id);
      return row;
    });
    if (!c || c.expires <= this.now()) fail(410, 'expired_or_replayed');
    const d = this.device(c.device);
    let valid = false;
    try { valid = verify('sha256',Buffer.from(c.message),createPublicKey({key:Buffer.from(d.public_key,'base64'),format:'der',type:'spki'}),Buffer.from(input.signature,'base64')); } catch {}
    if (!valid) fail(401, 'invalid_proof');
    this.allowed(d, c.action);
    if(c.action==='manage') {
      if(!this.isPhoneOwner(this.device(d.id)))fail(403,'owner_required');
      return {action:'manage',device:this.device(d.id),management:JSON.parse(c.payload)};
    }
    if (c.action === 'invite') {
      if (d.status !== 'new') fail(409, 'already_enrolled');
      this.rate(ip, 'invite', 30, 3600);
      const p = JSON.parse(c.payload);
      const result = this.tx(() => {
        const invite = this.db.prepare('SELECT * FROM invites WHERE digest=?').get(p.code_hash);
        if (!invite || invite.used_by || invite.expires <= this.now()) {
          const attempts = d.failures + 1, status = attempts >= 10 ? 'blocked' : 'new';
          this.db.prepare('UPDATE devices SET failures=?,cooldown=?,status=? WHERE id=?').run(attempts,this.now()+300,status,d.id);
          this.audit(status === 'blocked' ? 'blocked' : 'bad_invite', d.id);
          return status;
        }
        this.db.prepare('UPDATE invites SET used_by=? WHERE digest=? AND used_by IS NULL').run(d.id,invite.digest);
        this.db.prepare("UPDATE devices SET status='pending',failures=0,cooldown=0,name=? WHERE id=?").run(p.name,d.id);
        this.audit('enrolled',d.id);
        return 'pending';
      });
      if (result !== 'pending') fail(result === 'blocked' ? 403 : 429, result === 'blocked' ? 'blocked' : 'invalid_invite', 300);
    }
    const current = this.device(d.id);
    if (c.action === 'config' && current.status !== 'approved') fail(403, 'not_approved');
    return {action:c.action,device:current,result:this.status(current)};
  }
  status(d) { return {device_id:d.id,status:d.status,failures:d.failures,cooldown_until:d.cooldown,server_time:this.now(),phone_owner:this.isPhoneOwner(d),...(d.status==='approved'?{developer_username:this.developerUsername()}:{})}; }
  developerUsername() {
    return this.db.prepare("SELECT value FROM app_settings WHERE key='developer_username'").get()?.value || 'prince_skazki';
  }
  setDeveloper(actor, username) {
    return this.tx(()=>{
      if(!this.isPhoneOwner(this.device(actor)))fail(403,'owner_required');
      if(typeof username!=='string'||!/^[A-Za-z][A-Za-z0-9_]{3,31}$/.test(username))fail(400,'invalid_management');
      this.db.prepare("INSERT INTO app_settings(key,value) VALUES ('developer_username',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").run(username);
      this.audit('developer_contact_updated',actor);
      return {developer_username:username};
    });
  }
  owner(command, id, hours = 24) {
    if (command === 'invite') {
      if (!Number.isInteger(hours) || hours < 1 || hours > 720) fail(400,'invalid_expiry');
      const code = randomBytes(15).toString('hex').toUpperCase();
      this.db.prepare('INSERT INTO invites VALUES (?,?,NULL)').run(hash(`${this.pepper}:${code}`),this.now()+hours*3600);
      this.audit('invite_created'); return {code,expires_at:this.now()+hours*3600};
    }
    if (command === 'list') return this.db.prepare('SELECT id,name,status,failures,created FROM devices ORDER BY created').all().map(d=>({...d,phone_owner:this.isPhoneOwner(d),fingerprint:d.id.slice(0,16).toUpperCase().match(/.{4}/g).join(' ')}));
    const d = this.device(id); if (!d) fail(404,'device_not_found');
    if(command==='set-owner') {
      if(d.status!=='approved')fail(409,'owner_must_be_approved');
      this.db.prepare('INSERT INTO phone_owner VALUES (1,?) ON CONFLICT(singleton) DO UPDATE SET device_id=excluded.device_id').run(id);
      this.audit('phone_owner_bound',id);return this.status(d);
    } else if (command === 'approve') {
      if (d.status !== 'pending') fail(409,'device_not_pending');
      this.db.prepare("UPDATE devices SET status='approved',uuid=? WHERE id=?").run(randomUUID(),id);
    } else if (command === 'revoke') {
      this.db.prepare("UPDATE devices SET status='revoked',uuid=NULL WHERE id=?").run(id);
      this.db.prepare('DELETE FROM challenges WHERE device=?').run(id);
    } else fail(400,'unknown_command');
    this.audit(command,id); return this.status(this.device(id));
  }
  approved() { return this.db.prepare("SELECT id,uuid FROM devices WHERE status='approved'").all(); }
  close() { this.db.close(); }
}
