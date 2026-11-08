import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,sign,createHash} from 'node:crypto';
import {mkdtempSync,rmSync,readdirSync,readFileSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {Readable,PassThrough} from 'node:stream';
import {Access} from '../access.mjs';
import {Transcription} from '../transcription.mjs';
import {runBounded} from '../transcription-runner.mjs';
import {apiServer,transcriptionServer} from '../http.mjs';

const hash=b=>createHash('sha256').update(b).digest('hex');
const payload=b=>({sha256:hash(b),size:b.length,mime:'audio/ogg'});
function fixture(t,runner=async()=> 'Test transcript'){
 const dir=mkdtempSync(join(tmpdir(),'family-transcription-'));let time=1000000;
 const access=new Access(join(dir,'access.sqlite'),{pepper:'a'.repeat(64),now:()=>time});
 const newDevice=()=>{const key=generateKeyPairSync('ec',{namedCurve:'prime256v1'});key.public_key=key.publicKey.export({format:'der',type:'spki'}).toString('base64');const invitation=access.owner('invite');const challenge=access.challenge({public_key:key.public_key,action:'invite',payload:{code:invitation.code,name:'Phone'}},'127.0.0.1');access.complete(proof(challenge,key),'127.0.0.1');access.owner('approve',challenge.device_id);return {key,id:challenge.device_id};};
 const device=newDevice(),service=new Transcription(access,{directory:join(dir,'work'),runner,now:()=>time,uploadTimeoutMs:40});
 t.after(async()=>{await service.close();access.close();rmSync(dir,{recursive:true,force:true});});
 return {dir,access,service,...device,newDevice,advance:n=>time+=n,challenge:(action,p)=>access.challenge({public_key:device.key.public_key,action,payload:p},'127.0.0.1')};
}
const proof=(c,k)=>({challenge_id:c.challenge_id,signature:sign('sha256',Buffer.from(c.message),k.privateKey).toString('base64')});
const request=(bytes,reservation,overrides={})=>{const r=Readable.from([bytes]);r.headers={authorization:`Bearer ${reservation.upload_token}`,'content-type':'audio/ogg','content-length':String(bytes.length),...overrides};return r;};
test('transcription proof binds exact normalized sha256/size/mime; replay, malformed metadata and revoke fail closed',t=>{
 const f=fixture(t),p=payload(Buffer.from('audio')),c=f.challenge('transcription',p);
 assert.equal(c.message.split('\n').at(-1),hash(JSON.stringify(p)));
 const done=f.access.complete({...proof(c,f.key),payload:payload(Buffer.from('changed'))},'127.0.0.1');assert.deepEqual(done.transcriptionRequest,p);
 assert.throws(()=>f.access.complete(proof(c,f.key),'127.0.0.1'),e=>e.code==='expired_or_replayed');
 for(const bad of [{...p,size:16777217},{...p,mime:'application/json'},{...p,sha256:'x'.repeat(64)},{...p,filename:'../../x'}])assert.throws(()=>f.challenge('transcription',bad),e=>e.code==='invalid_transcription_payload');
 const late=f.challenge('transcription',p);f.access.owner('revoke',f.id);assert.throws(()=>f.access.complete(proof(late,f.key),'127.0.0.1'),e=>e.code==='expired_or_replayed');
});
test('upload is one-use and exact bytes are hashed before worker; results are device-private and files removed',async t=>{
 let seen;const f=fixture(t,async job=>{seen=readFileSync(job.path);return 'Private recognized words';}),bytes=Buffer.from('audio');
 const r=f.service.reserve(f.id,payload(bytes));await f.service.upload(request(bytes,r),r.job_id);await f.service.jobs.get(r.job_id).operation;
 assert.deepEqual(seen,bytes);assert.equal(f.service.status(f.id,r.job_id).text,'Private recognized words');assert.equal(readdirSync(f.service.directory).length,0);
 await assert.rejects(f.service.upload(request(bytes,r),r.job_id),e=>e.code==='invalid_upload_token');
 assert.throws(()=>f.service.status(f.newDevice().id,r.job_id),e=>e.code==='transcription_not_found');
 const db=JSON.stringify(f.access.db.prepare('SELECT * FROM challenges').all());assert(!db.includes('Private recognized words'));
});
test('hash or MIME mismatch consumes upload and never executes worker',async t=>{
 let runs=0;const f=fixture(t,async()=>{runs++;return 'never';}),bytes=Buffer.from('audio');
 for(const change of ['hash','mime']){
  const r=f.service.reserve(f.id,payload(bytes));const req=request(change==='hash'?Buffer.from('wrong'):bytes,r,change==='mime'?{'content-type':'video/mp4'}:{});
  await assert.rejects(f.service.upload(req,r.job_id),e=>['content_mismatch','upload_invalid'].includes(e.code));
  await assert.rejects(f.service.upload(request(bytes,r),r.job_id),e=>e.code==='invalid_upload_token');
 }
 assert.equal(runs,0);assert.equal(readdirSync(f.service.directory).length,0);
});
test('one real worker slot is held until cancelled computation actually exits',async t=>{
 let finish;const f=fixture(t,()=>new Promise(r=>finish=r)),bytes=Buffer.from('audio');const r=f.service.reserve(f.id,payload(bytes));
 await f.service.upload(request(bytes,r),r.job_id);const running=f.service.jobs.get(r.job_id).operation;
 assert.equal(f.service.cancel(f.id,r.job_id).state,'cancelled');assert.throws(()=>f.service.reserve(f.id,payload(bytes)),e=>e.code==='transcription_busy');
 finish('must not escape');await running;assert.equal(f.service.status(f.id,r.job_id).text,undefined);assert.equal(f.service.active,null);assert.equal(readdirSync(f.service.directory).length,0);
});
test('revoke aborts active worker, deletes results and makes upload tokens unusable',async t=>{
 let aborted=false;const f=fixture(t,({signal})=>new Promise((_,reject)=>signal.addEventListener('abort',()=>{aborted=true;reject(Error());},{once:true}))),bytes=Buffer.from('audio');
 const r=f.service.reserve(f.id,payload(bytes));await f.service.upload(request(bytes,r),r.job_id);const running=f.service.jobs.get(r.job_id).operation;
 f.access.owner('revoke',f.id);f.service.revoke(f.id);await running;assert(aborted);assert.equal(f.service.jobs.size,0);assert.equal(f.service.active,null);assert.equal(readdirSync(f.service.directory).length,0);
 await assert.rejects(f.service.upload(request(bytes,r),r.job_id),e=>e.code==='invalid_upload_token');
});
test('expired reservations and completed results are removed; slow upload closes and clears files',async t=>{
 const f=fixture(t),bytes=Buffer.from('audio');const old=f.service.reserve(f.id,payload(bytes));f.advance(121);f.service.sweep();assert.equal(f.service.active,null);assert.equal(f.service.jobs.size,0);
 await assert.rejects(f.service.upload(request(bytes,old),old.job_id),e=>e.code==='invalid_upload_token');
 const r=f.service.reserve(f.id,payload(bytes)),slow=new PassThrough();slow.headers=request(bytes,r).headers;
 await assert.rejects(f.service.upload(slow,r.job_id),e=>e.code==='upload_timeout');assert.equal(f.service.active,null);assert.equal(readdirSync(f.service.directory).length,0);
 const next=f.service.reserve(f.id,payload(bytes));await f.service.upload(request(bytes,next),next.job_id);await f.service.jobs.get(next.job_id).operation;
 f.advance(601);f.service.sweep();assert.equal(f.service.jobs.size,0);
});
test('separate HTTP upload preserves API timeout and approved PoP envelope',async t=>{
 const f=fixture(t),bytes=Buffer.from('audio'),api=apiServer(f.access,{ready:true},{transcription:f.service}),upload=transcriptionServer(f.service);
 await Promise.all([new Promise(r=>api.listen(0,'127.0.0.1',r)),new Promise(r=>upload.listen(0,'127.0.0.1',r))]);t.after(()=>{api.close();upload.close();});assert.equal(api.requestTimeout,10000);assert.equal(upload.requestTimeout,90000);
 const complete=async actionPayload=>{const c=f.challenge(actionPayload.action,actionPayload.payload);const response=await fetch(`http://127.0.0.1:${api.address().port}/v1/complete`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(proof(c,f.key))});assert.equal(response.status,200);return response.json();};
 const reserved=await complete({action:'transcription',payload:payload(bytes)});assert.equal(reserved.status,'approved');assert(reserved.transcription.upload_token);
 const res=await fetch(`http://127.0.0.1:${upload.address().port}${reserved.transcription.upload_url}`,{method:'PUT',headers:{Authorization:`Bearer ${reserved.transcription.upload_token}`,'Content-Type':'audio/ogg'},body:bytes});assert.equal(res.status,202);
 await f.service.jobs.get(reserved.transcription.job_id).operation;
 const status=await complete({action:'transcription_status',payload:{job_id:reserved.transcription.job_id}});assert.equal(status.transcription.state,'done');assert.equal(status.transcription.upload_token,undefined);
});
test('worker subprocess has real deadline and bounded output without forwarding stderr',async()=>{
 await assert.rejects(runBounded(process.execPath,['-e','setInterval(()=>{},1000)'],{timeoutMs:30}),e=>e.code==='worker_timeout');
 await assert.rejects(runBounded(process.execPath,['-e',"process.stdout.write('x'.repeat(10000))"],{timeoutMs:3000,maxOutput:128}),e=>e.code==='output_too_large');
 const controller=new AbortController();controller.abort();await assert.rejects(runBounded(process.execPath,['-e',''],{signal:controller.signal,timeoutMs:100}),e=>e.code==='cancelled');
});
