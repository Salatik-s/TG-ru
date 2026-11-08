import assert from 'node:assert/strict';
import {generateKeyPairSync,randomBytes,createHash} from 'node:crypto';
import {mkdtempSync,mkdirSync,readFileSync,readdirSync,rmSync} from 'node:fs';
import {homedir} from 'node:os';
import {dirname,join,resolve,sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {Access} from '../server/access.mjs';

const qa=dirname(fileURLToPath(import.meta.url)),root=resolve(qa,'..');
const jdk=process.env.JAVA_HOME||'C:/Users/andre/AppData/Local/Programs/Android Studio/jbr';
const executable=name=>join(jdk,'bin',name+(process.platform==='win32'?'.exe':''));
const parent=join(homedir(),'.codex/private/familygram-qa');
mkdirSync(parent,{recursive:true,mode:0o700});
const temp=mkdtempSync(join(parent,'interop-')),classes=join(temp,'classes');mkdirSync(classes);
const dbPath=join(temp,'interop.sqlite');
const access=new Access(dbPath,{pepper:randomBytes(32).toString('hex')});
const secrets=[];
const hash=value=>createHash('sha256').update(value).digest('hex');
function check(message,id,action,key){
 const encode=v=>Buffer.from(v).toString('base64');
 const input=[encode(id),encode(action),encode(message),key?key.privateKey.export({type:'pkcs8',format:'der'}).toString('base64'):''].join('\n')+'\n';
 return execFileSync(executable('java'),['-cp',classes,'org.telegram.messenger.family.ProtocolInterop'],{input,encoding:'utf8'}).trim();
}
function installation(){const key=generateKeyPairSync('ec',{namedCurve:'prime256v1'});return {key,public_key:key.publicKey.export({format:'der',type:'spki'}).toString('base64')};}
function challenge(d,action,payload={}){
 const value=access.challenge({public_key:d.public_key,action,payload},'127.0.0.1');
 if(action==='invite'){
  const row=access.db.prepare('SELECT payload,message FROM challenges WHERE id=?').get(value.challenge_id);
  assert(!JSON.stringify(row).includes(payload.code),'Active challenge must not store raw invitation');
  const stored=JSON.parse(row.payload);assert.match(stored.code_hash,/^[a-f0-9]{64}$/);assert(!('code' in stored));
 }
 return value;
}
function complete(c,d){const signature=check(c.message,c.challenge_id,c.message.split('\n')[3],d.key);assert.notEqual(signature,'invalid');return access.complete({challenge_id:c.challenge_id,signature},'127.0.0.1');}
try{
 execFileSync(executable('javac'),['--release','17','-d',classes,join(root,'overlay/java/org/telegram/messenger/family/FamilyProtocol.java'),join(qa,'ProtocolInterop.java')],{stdio:'pipe'});
 const wrong=installation(),badCode=randomBytes(15).toString('hex').toUpperCase();secrets.push(badCode);
 const bad=challenge(wrong,'invite',{code:badCode,name:'Cross-language wrong code'});
 assert.equal(check(bad.message,bad.challenge_id,'invite'),'valid');
 assert.throws(()=>complete(bad,wrong),e=>e.code==='invalid_invite'&&e.retryAfter===300);
 assert.throws(()=>challenge(wrong,'invite',{code:badCode,name:'Retry'}),e=>e.code==='cooldown');
 const good=installation(),invite=access.owner('invite',undefined,1);secrets.push(invite.code);
 const payload={code:invite.code,name:'Cross-language accepted'},valid=challenge(good,'invite',payload);
 assert.notEqual(valid.message.split('\n')[4],hash(JSON.stringify(payload)),'Invite commitment is server-owned and peppered, not a raw-code JSON digest');
 assert.equal(check(valid.message,valid.challenge_id,'invite'),'valid');
 const mutations=[
  [valid.message+'\n',valid.challenge_id,'invite'],
  [valid.message,valid.challenge_id,'config'],
  [valid.message,'00000000-0000-0000-0000-000000000000','invite'],
  [valid.message.replace('familygram-v1','familygram-v2'),valid.challenge_id,'invite'],
  [valid.message.replace(/\n[A-Za-z0-9_-]{43}\n/,'\nbadnonce\n'),valid.challenge_id,'invite'],
  [valid.message.replace(/\n[a-f0-9]{64}$/,'\n'+'G'.repeat(64)),valid.challenge_id,'invite'],
  [valid.message.replace(/\n[a-f0-9]{64}$/,'\n'+'a'.repeat(63)),valid.challenge_id,'invite'],
  [valid.message.replaceAll('\n','\r\n'),valid.challenge_id,'invite'],
  [valid.message,valid.challenge_id,'arbitrary'],
 ];
 for(const [message,id,action] of mutations)assert.equal(check(message,id,action),'invalid');
 const pending=complete(valid,good);assert.equal(pending.result.status,'pending');
 assert.throws(()=>complete(valid,good),e=>e.code==='expired_or_replayed');
 access.owner('approve',valid.device_id);
 for(const action of ['status','config']){
  const c=challenge(good,action);assert.equal(check(c.message,c.challenge_id,action),'valid');
  assert.equal(check(c.message.replace(/\n[a-f0-9]{64}$/,'\n'+'a'.repeat(64)),c.challenge_id,action),'invalid');
  assert.equal(complete(c,good).result.status,'approved');
 }
 const stored=[...access.db.prepare('SELECT payload,message FROM challenges').all(),...access.db.prepare('SELECT * FROM invites').all()];
 for(const secret of secrets)assert(!JSON.stringify(stored).includes(secret),'Raw invitation must never persist in database columns');
 access.close();
 for(const name of readdirSync(temp).filter(n=>n.startsWith('interop.sqlite'))){const bytes=readFileSync(join(temp,name));for(const secret of secrets)assert(!bytes.includes(Buffer.from(secret)),'Raw invitation must not appear in SQLite/WAL bytes');}
 console.log('Java/Node protocol interop PASS: actual FamilyProtocol + SHA256withECDSA, peppered wrong/correct invites, 300s cooldown, pending/approval, status/config, replay, 9 malformed messages, no plaintext code persistence.');
}finally{try{access.close();}catch{}assert(resolve(temp).startsWith(resolve(parent)+sep));rmSync(temp,{recursive:true,force:true});}
