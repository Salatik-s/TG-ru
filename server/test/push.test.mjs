import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync,sign } from 'node:crypto';
import { mkdtempSync,rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Access } from '../access.mjs';
import { PushRelay } from '../push.mjs';
import { apiServer } from '../http.mjs';

async function fixture(t){
  let now=100000;const dir=mkdtempSync(join(tmpdir(),'fg-push-'));
  const a=new Access(join(dir,'db'),{pepper:'p'.repeat(64),now:()=>now});
  const key=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),pub=key.publicKey.export({type:'spki',format:'der'}).toString('base64');
  const complete=(action,payload={})=>{const c=a.challenge({public_key:pub,action,payload},'family');return a.complete({challenge_id:c.challenge_id,signature:sign('sha256',Buffer.from(c.message),key.privateKey).toString('base64')},'family');};
  const d=complete('invite',{code:a.owner('invite').code,name:'Phone'}).device;a.owner('approve',d.id);
  const push=new PushRelay(a,{heartbeatMs:999999}),server=apiServer(a,{ready:true,client:()=>({protocol:'vless'})},{push});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  t.after(async()=>{push.close();server.closeAllConnections();await new Promise(r=>server.close(r));a.close();rmSync(dir,{recursive:true,force:true});});
  const base=`http://127.0.0.1:${server.address().port}`;
  const config=push.mint(d.id),path=new URL(config.endpoint).pathname;
  return {a,push,id:d.id,config,base,path,advance:n=>now+=n,complete,
    put:(body='version=123',p=path)=>fetch(base+p,{method:'PUT',body}),
    events:(token=config.token,cursor='0')=>fetch(base+'/v1/events',{headers:{Authorization:`Bearer ${token}`,'Last-Event-ID':cursor}})};
}
async function readThrough(reader,pattern){let text='';for(let n=0;n<10;n++){const v=await reader.read();if(v.done)return text;text+=new TextDecoder().decode(v.value);if(pattern.test(text))return text;}throw Error('SSE event not found');}

test('webhook authenticates approved device, accepts numeric version only, deduplicates and stores no content',async t=>{
  const f=await fixture(t);assert.equal((await f.put('version=1',f.path.slice(0,-1)+'!')).status,404);
  assert.equal((await f.put('message=private text')).status,400);assert.equal((await f.put('version=1&message=secret')).status,400);
  assert.equal((await f.put('x'.repeat(129))).status,413);
  assert.equal((await f.put('version=1')).status,200);assert.equal((await f.put('version=1')).status,200);
  assert.equal((await f.put('version=0')).status,200); // Telegram versions are not necessarily monotonic.
  const rows=f.a.db.prepare('SELECT * FROM push_signals').all();assert.equal(rows.length,2);assert.deepEqual(Object.keys(rows[0]),['device','sequence','version_hash']);
  assert.equal(f.a.db.prepare('SELECT sequence FROM push_state WHERE device=?').get(f.id).sequence,2);
  f.a.owner('revoke',f.id);f.push.revoke(f.id);assert.equal((await f.put()).status,403);
});
test('SSE bearer expiry, no token URL, bounded streams and immediate revoke closure',async t=>{
  const f=await fixture(t);
  assert.equal((await fetch(f.base+'/v1/events?token='+f.config.token)).status,404);
  assert.equal((await f.events('wrong')).status,401);
  const r1=await f.events(),r2=await f.events();assert.equal(r1.status,200);assert.equal(r2.status,200);
  assert.equal((await f.events()).status,429);
  const reader=r1.body.getReader();await readThrough(reader,/ready/);
  await f.put('version=50');const event=await readThrough(reader,/data:/);assert.match(event,/id: 1/);assert.match(event,/"kind":"wake"/);assert.doesNotMatch(event,/50|uuid|message|endpoint/);
  f.a.owner('revoke',f.id);f.push.revoke(f.id);assert.equal((await reader.read()).done,true);
  await r2.body.cancel();assert.equal((await f.events()).status,401);
});
test('75 second lease closes existing SSE and rejects replayed bearer; mint caps active token memory',async t=>{
  const f=await fixture(t),r=await f.events(),reader=r.body.getReader();await readThrough(reader,/ready/);
  f.advance(75);f.push.tick();assert.equal((await reader.read()).done,true);assert.equal((await f.events()).status,401);
  for(let i=0;i<20;i++)f.push.mint(f.id);assert.equal(f.push.tokens.size,2);
});
test('persistent sequence, replay and bounded history coalesce missed signals without leaking versions',async t=>{
  const f=await fixture(t);for(let n=0;n<70;n++)f.push.signal(f.id,Buffer.from('version='+n));
  assert.equal(f.a.db.prepare('SELECT count(*) AS n FROM push_signals').get().n,64);
  const r=await f.events(f.config.token,'1'),reader=r.body.getReader();const event=await readThrough(reader,/event: reset/);assert.match(event,/id: 70/);await reader.cancel();
  const r2=await f.events(f.config.token,'68'),rd2=r2.body.getReader();const replay=await readThrough(rd2,/id: 70/);assert.match(replay,/id: 69/);await rd2.cancel();
  f.push.close();const again=new PushRelay(f.a);t.after(()=>again.close());assert.equal(again.access.db.prepare('SELECT sequence FROM push_state WHERE device=?').get(f.id).sequence,70);
});
test('webhook burst limits expire; pending installations cannot mint notification credentials',async t=>{
  const f=await fixture(t);for(let i=0;i<120;i++)f.push.webhookIdentity(f.path,'telegram');
  assert.throws(()=>f.push.webhookIdentity(f.path,'telegram'),e=>e.code==='rate_limited');f.advance(60);assert.equal(f.push.webhookIdentity(f.path,'telegram'),f.id);
  f.a.db.prepare("UPDATE devices SET status='pending' WHERE id=?").run(f.id);assert.throws(()=>f.push.mint(f.id),e=>e.code==='push_denied');
});
