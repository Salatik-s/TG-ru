import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';
import {homedir} from 'node:os';
import {request,Agent} from 'node:https';
import {generateKeyPairSync,sign} from 'node:crypto';
const dir=join(homedir(),'.codex/private/familygram-qa');
const settings=JSON.parse(readFileSync(join(dir,'settings.json'),'utf8'));
const agent=new Agent({ca:readFileSync(join(dir,'ca.pem')),lookup:(_host,options,callback)=>callback(null,options.all?[{address:'127.0.0.1',family:4}]:'127.0.0.1',4)});
function api(path,value,method=value?'POST':'GET',headers={}){
 return new Promise((resolve,reject)=>{
  const req=request(`https://24projects.ru:8443${path}`,{method,agent,headers:{...(value?{'content-type':'application/json'}:{}),...headers}},res=>{let text='';res.on('data',c=>text+=c);res.on('end',()=>resolve({status:res.statusCode,body:JSON.parse(text)}));});
  req.setTimeout(5000,()=>req.destroy(new Error('TLS request timeout')));req.on('error',reject);req.end(value?JSON.stringify(value):undefined);
 });
}
async function owner(command,id){const res=await fetch('http://127.0.0.1:18444/owner',{method:'POST',headers:{'content-type':'application/json',authorization:`Bearer ${settings.ownerToken}`},body:JSON.stringify({command,id,hours:1})});assert.equal(res.status,200);return res.json();}
function device(){const key=generateKeyPairSync('ec',{namedCurve:'prime256v1'});return {key,public_key:key.publicKey.export({type:'spki',format:'der'}).toString('base64')};}
async function proof(d,action,payload={}){
 const c=await api('/v1/challenge',{public_key:d.public_key,action,payload});
 if(c.status!==200)return c;
 d.id=c.body.device_id;
 return api('/v1/complete',{challenge_id:c.body.challenge_id,signature:sign('sha256',Buffer.from(c.body.message),d.key.privateKey).toString('base64')});
}
try{
 assert.equal((await api('/health')).status,200);
 assert.equal((await fetch('http://127.0.0.1:18444/owner',{method:'POST'})).status,401);
 const wrong=device();let r=await proof(wrong,'invite',{code:'WRONG_QA_CODE',name:'QA wrong code'});
 assert.equal(r.status,429);assert.equal(r.body.error,'invalid_invite');assert.equal(r.body.retry_after,300);
 r=await proof(wrong,'invite',{code:'WRONG_QA_CODE',name:'QA cooldown'});assert.equal(r.status,429);assert.equal(r.body.error,'cooldown');
 await owner('revoke',wrong.id);
 const valid=device(),invitation=await owner('invite');
 r=await proof(valid,'invite',{code:invitation.code,name:'QA smoke (not Android)'});assert.equal(r.body.status,'pending');
 assert.equal((await proof(valid,'config')).status,403);
 await owner('approve',valid.id);r=await proof(valid,'config');assert.equal(r.status,200);
 assert.equal(r.body.gateway.host,'10.0.2.2');assert.equal(r.body.gateway.port,18443);
 assert.match(r.body.notifications.endpoint,/^https:\/\/24projects\.ru:8443\/v1\/push\//);
 const token=r.body.notifications.token;
 await owner('revoke',valid.id);
 assert.equal((await api('/v1/events',null,'GET',{authorization:`Bearer ${token}`})).status,401);
 assert.equal((await proof(valid,'config')).status,403);
 console.log('QA TLS/P256 smoke PASS: trusted hostname, owner auth, wrong-code cooldown, pending, approve/config, notification token, revoke. Initial APK invitation untouched.');
}finally{agent.destroy();}
