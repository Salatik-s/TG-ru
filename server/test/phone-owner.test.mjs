import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,sign} from 'node:crypto';
import {Access} from '../access.mjs';
import {apiServer} from '../http.mjs';

function setup(t){
  const access=new Access(':memory:',{pepper:'owner-test-private-pepper-'.repeat(3)});t.after(()=>access.close());
  function device(name,approved=true){
    const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),public_key=keys.publicKey.export({type:'spki',format:'der'}).toString('base64');
    const challenge=(action='status',payload={})=>access.challenge({public_key,action,payload},'owner-test-ip');
    const proof=c=>({challenge_id:c.challenge_id,signature:sign('sha256',Buffer.from(c.message),keys.privateKey).toString('base64')});
    const invite=access.owner('invite'),c=challenge('invite',{code:invite.code,name});access.complete(proof(c),'owner-test-ip');
    if(approved)access.owner('approve',c.device_id);
    return {id:c.device_id,challenge,proof,complete:c=>access.complete(proof(c),'owner-test-ip')};
  }
  return {access,device};
}
const error=(code,status)=>e=>e.code===code&&e.status===status;

test('no first-device ownership; only an explicitly bound approved installation gets phone-owner authority',t=>{
  const {access,device}=setup(t),owner=device('Owner'),family=device('Family'),pending=device('Pending',false);
  assert.equal(owner.complete(owner.challenge('config')).result.phone_owner,false);
  assert.throws(()=>owner.challenge('manage',{command:'list'}),error('owner_required',403));
  assert.throws(()=>access.owner('set-owner',pending.id),error('owner_must_be_approved',409));
  access.owner('set-owner',owner.id);
  assert.equal(owner.complete(owner.challenge()).result.phone_owner,true);
  assert.equal(family.complete(family.challenge()).result.phone_owner,false);
  assert.throws(()=>family.challenge('manage',{command:'invite',hours:24}),error('owner_required',403));
  assert.throws(()=>pending.challenge('manage',{command:'list'}),error('owner_required',403));
});

test('management proof binds exact command, target and lifetime, consumes once, and never permits public owner transfer or self-revoke',t=>{
  const {access,device}=setup(t),owner=device('Owner'),family=device('Family',false);access.owner('set-owner',owner.id);
  for(const p of [{command:'set-owner',device_id:family.id},{command:'list',device_id:family.id},{command:'invite',hours:0},{command:'approve',device_id:'short'},{command:'revoke',device_id:owner.id}])
    assert.throws(()=>owner.challenge('manage',p));
  const c=owner.challenge('manage',{command:'approve',device_id:family.id}),proof=owner.proof(c);
  proof.payload={command:'revoke',device_id:owner.id};proof.action='set-owner';
  const response=access.complete(proof,'owner-test-ip');
  assert.deepEqual(response.management,{command:'approve',device_id:family.id});
  assert.equal(access.device(family.id).status,'pending','proof validation alone cannot mutate membership');
  assert.throws(()=>access.complete(proof,'owner-test-ip'),error('expired_or_replayed',410));
});

test('owner transfer invalidates preissued management proofs and revoking owner removes authority',t=>{
  const {access,device}=setup(t),one=device('One'),two=device('Two');access.owner('set-owner',one.id);
  const c=one.challenge('manage',{command:'list'});access.owner('set-owner',two.id);
  assert.throws(()=>one.complete(c),error('owner_required',403));
  assert.equal(access.isPhoneOwner(access.device(one.id)),false);
  assert.equal(access.isPhoneOwner(access.device(two.id)),true);
  access.owner('revoke',two.id);assert.equal(access.isPhoneOwner(access.device(two.id)),false);
});

test('HTTP management requires signed owner proof, invokes shared membership handler once and never exposes local owner binding',async t=>{
  const {access,device}=setup(t),owner=device('Owner'),family=device('Family',false);access.owner('set-owner',owner.id);
  let calls=0;
  const server=apiServer(access,{ready:true},{manage:async(actor,p)=>{
    assert.equal(actor,owner.id);assert.equal(access.isPhoneOwner(access.device(actor)),true);calls++;
    return access.owner(p.command,p.device_id,p.hours);
  }});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
  const root=`http://127.0.0.1:${server.address().port}`;
  const c=owner.challenge('manage',{command:'approve',device_id:family.id}),payload=owner.proof(c);
  const post=()=>fetch(root+'/v1/complete',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(payload)});
  const first=await post();assert.equal(first.status,200);const value=await first.json();assert.equal(value.phone_owner,true);assert.equal(value.management.status,'approved');assert.equal(calls,1);
  assert.equal((await post()).status,410);assert.equal(calls,1);
  assert.equal((await fetch(root+'/owner',{method:'POST'})).status,404);
  assert.throws(()=>family.challenge('manage',{command:'list'}),error('owner_required',403));
});
