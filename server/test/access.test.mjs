import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Access } from '../access.mjs';
import { apiServer } from '../http.mjs';
import { Gateway } from '../gateway.mjs';

const key = () => {const k=generateKeyPairSync('ec',{namedCurve:'prime256v1'});return {...k,public_key:k.publicKey.export({type:'spki',format:'der'}).toString('base64')};};
const proof=(c,k)=>({challenge_id:c.challenge_id,signature:sign('sha256',Buffer.from(c.message),k.privateKey).toString('base64')});
function fixture(t) {
  let now=1000000;const dir=mkdtempSync(join(tmpdir(),'familygram-test-'));const path=join(dir,'access.sqlite');
  const a=new Access(path,{now:()=>now,pepper:'a'.repeat(64)});
  t.after(()=>{a.close();rmSync(dir,{recursive:true,force:true});});
  return {a,path,now:()=>now,advance:n=>now+=n,challenge:(k,action='status',payload={},ip='127.0.0.1')=>a.challenge({public_key:k.public_key,action,payload},ip),complete:(c,k,ip='127.0.0.1')=>a.complete(proof(c,k),ip)};
}
const error=(code,status)=>e=>e.code===code&&e.status===status;

test('valid proof, wrong key, signature tampering, replay and TTL',t=>{
  const f=fixture(t),k=key(),other=key();
  const c=f.challenge(k);assert.equal(f.complete(c,k).result.status,'new');
  assert.throws(()=>f.complete(c,k),error('expired_or_replayed',410));
  const wrong=f.challenge(k);assert.throws(()=>f.complete(wrong,other),error('invalid_proof',401));
  assert.throws(()=>f.complete(wrong,k),error('expired_or_replayed',410));
  const expired=f.challenge(k);f.advance(120);assert.throws(()=>f.complete(expired,k),error('expired_or_replayed',410));
});
test('P256 only and exact action payload binding',t=>{
  const f=fixture(t),k=key(),rsa=generateKeyPairSync('rsa',{modulusLength:2048});
  assert.throws(()=>f.a.challenge({public_key:rsa.publicKey.export({type:'spki',format:'der'}).toString('base64'),action:'status'},'ip'),error('invalid_request',400));
  const p384=generateKeyPairSync('ec',{namedCurve:'secp384r1'});
  assert.throws(()=>f.a.challenge({public_key:p384.publicKey.export({type:'spki',format:'der'}).toString('base64'),action:'status'},'ip'),error('invalid_key',400));
  const invite=f.a.owner('invite'),c=f.challenge(k,'invite',{code:invite.code,name:'Family phone'});
  assert.equal(JSON.stringify(f.a.db.prepare('SELECT * FROM challenges').all()).includes(invite.code),false,'Raw invitation must not be persisted in a challenge');
  const p=proof(c,k);p.action='config';p.payload={code:'fake'};
  assert.equal(f.a.complete(p,'ip').result.status,'pending');
  assert.equal(f.a.device(c.device_id).name,'Family phone');
});
test('one-use invite and approval gate; revoke deletes credentials and pending proofs',t=>{
  const f=fixture(t),k=key(),other=key(),invite=f.a.owner('invite');
  const c=f.challenge(k,'invite',{code:invite.code,name:'One'});assert.equal(f.complete(c,k).result.status,'pending');
  assert.throws(()=>f.complete(f.challenge(k,'config'),k),error('not_approved',403));
  assert.throws(()=>f.complete(f.challenge(other,'invite',{code:invite.code,name:'Two'}),other),error('invalid_invite',429));
  f.a.owner('approve',c.device_id);const conf=f.complete(f.challenge(k,'config'),k);
  assert.match(conf.device.uuid,/^[0-9a-f-]{36}$/);assert.equal(f.a.approved().length,1);
  const before=f.challenge(k,'config');f.a.owner('revoke',c.device_id);
  assert.throws(()=>f.complete(before,k),error('expired_or_replayed',410));
  assert.throws(()=>f.challenge(k),error('revoked',403));assert.equal(f.a.approved().length,0);assert.equal(f.a.device(c.device_id).uuid,null);
});
test('each bad code enforces 300s cooldown; preissued proofs cannot bypass; tenth blocks persistently',t=>{
  const f=fixture(t),k=key();let id;
  for(let n=1;n<=10;n++) {
    const c=f.challenge(k,'invite',{code:'wrong',name:'Phone'}),other=f.challenge(k,'invite',{code:'wrong2',name:'Phone'});id=c.device_id;
    assert.throws(()=>f.complete(c,k),error(n===10?'blocked':'invalid_invite',n===10?403:429));
    assert.equal(f.a.device(id).failures,n);
    assert.throws(()=>f.complete(other,k),error(n===10?'blocked':'cooldown',n===10?403:429));
    if(n<10){f.advance(299);assert.throws(()=>f.challenge(k,'invite',{code:'x',name:'P'}),error('cooldown',429));f.advance(1);}
  }
  f.advance(99999);assert.throws(()=>f.challenge(k),error('blocked',403));
  const reopened=new Access(f.path,{now:f.now,pepper:'a'.repeat(64)});assert.equal(reopened.device(id).status,'blocked');reopened.close();
});
test('successful code resets failure count and expired invitation fails',t=>{
  const f=fixture(t),k=key();assert.throws(()=>f.complete(f.challenge(k,'invite',{code:'bad',name:'P'}),k));f.advance(300);
  const invite=f.a.owner('invite'),c=f.challenge(k,'invite',{code:invite.code,name:'P'});assert.equal(f.complete(c,k).result.failures,0);
  const expired=f.a.owner('invite',null,1),other=key();f.advance(3600);
  assert.throws(()=>f.complete(f.challenge(other,'invite',{code:expired.code,name:'P'}),other),error('invalid_invite',429));
});
test('secondary IP limits expire and never permanently ban a shared IP',t=>{
  const f=fixture(t);for(let i=0;i<30;i++) f.challenge(key(),'status',{},'shared');
  assert.throws(()=>f.challenge(key(),'status',{},'shared'),error('rate_limited',429));
  assert.ok(f.challenge(key(),'status',{},'other'));f.advance(86400);assert.ok(f.challenge(key(),'status',{},'shared'));
});
test('public key observer cannot consume another IP device challenge slots',t=>{
  const f=fixture(t),k=key();for(let i=0;i<5;i++) f.challenge(k,'status',{},'attacker');
  assert.throws(()=>f.challenge(k,'status',{},'attacker'),error('too_many_challenges',429));
  assert.equal(f.complete(f.challenge(k,'status',{},'family'),k,'family').result.status,'new');
});
test('HTTP concurrent invite completions consume once; 10 simultaneous wrong proofs count once',async t=>{
  const f=fixture(t),server=apiServer(f.a,{ready:true,client:()=>({})});await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>server.close(r)));
  const post=async(p)=>{const r=await fetch(`http://127.0.0.1:${server.address().port}/v1/complete`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(p)});return {status:r.status,body:await r.json()};};
  const k=key(),c=f.challenge(k,'invite',{code:'bad',name:'P'}),responses=await Promise.all(Array.from({length:10},()=>post(proof(c,k))));
  assert.equal(responses.filter(r=>r.status===429).length,1);assert.equal(responses.filter(r=>r.status===410).length,9);assert.equal(f.a.device(c.device_id).failures,1);
  const invite=f.a.owner('invite'),k1=key(),k2=key(),c1=f.challenge(k1,'invite',{code:invite.code,name:'One'}),c2=f.challenge(k2,'invite',{code:invite.code,name:'Two'});
  const raced=await Promise.all([post(proof(c1,k1)),post(proof(c2,k2))]);assert.deepEqual(raced.map(r=>r.status).sort(),[200,429]);
});
test('HTTP does not expose owner or arbitrary relay; config fails closed when gateway down',async t=>{
  const f=fixture(t),k=key(),i=f.a.owner('invite'),c=f.challenge(k,'invite',{code:i.code,name:'P'});f.complete(c,k);f.a.owner('approve',c.device_id);
  const server=apiServer(f.a,{ready:false});await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>server.close(r)));
  const base=`http://127.0.0.1:${server.address().port}`;
  for(const path of ['/owner','/relay?url=http://127.0.0.1','/health']) {const r=await fetch(base+path);assert.equal(r.status,path==='/health'?503:404);}
  const r=await fetch(base+'/v1/complete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(proof(f.challenge(k,'config'),k))});assert.equal(r.status,503);assert.equal((await r.json()).error,'gateway_unavailable');
});
test('proof-authorized config retains REALITY and adds same-device WSS; pending and revoked devices get no profiles',async t=>{
  const f=fixture(t),k=key(),invite=f.a.owner('invite'),enrollment=f.challenge(k,'invite',{code:invite.code,name:'Phone'});f.complete(enrollment,k);
  const gateway=new Gateway({host:'24projects.ru',serverName:'www.microsoft.com',publicKey:'public-test-key',shortId:'aabb'},'unused');gateway.ready=true;
  const server=apiServer(f.a,gateway);await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>server.close(r)));
  const post=async challenge=>{const r=await fetch(`http://127.0.0.1:${server.address().port}/v1/complete`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(proof(challenge,k))});return {status:r.status,body:await r.json()};};
  const pending=await post(f.challenge(k,'config'));assert.equal(pending.status,403);assert.equal(pending.body.gateway_fallbacks,undefined);
  f.a.owner('approve',enrollment.device_id);
  const approved=await post(f.challenge(k,'config'));assert.equal(approved.status,200);assert.equal(approved.body.gateway.security,'reality');assert.equal(approved.body.gateway.port,18443);
  assert.deepEqual(approved.body.gateway_fallbacks,[{protocol:'vless',host:'24projects.ru',port:8443,uuid:approved.body.gateway.uuid,security:'tls',transport:'ws',path:'/v1/transport',server_name:'24projects.ru',flow:'',fingerprint:''}]);
  const oldProof=f.challenge(k,'config');f.a.owner('revoke',enrollment.device_id);
  const revoked=await post(oldProof);assert.equal(revoked.status,410);assert.equal(revoked.body.gateway,undefined);assert.equal(revoked.body.gateway_fallbacks,undefined);
});
