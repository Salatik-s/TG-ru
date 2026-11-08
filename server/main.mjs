import { mkdirSync, readFileSync, writeFileSync, existsSync, chmodSync, unlinkSync } from 'node:fs';
import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { Access, ApiError } from './access.mjs';
import { Gateway } from './gateway.mjs';
import { apiServer, body, send, failure } from './http.mjs';
import { PushRelay } from './push.mjs';
import { EgressManager, loadSnapshot } from './egress.mjs';

process.umask(0o077);
const dir=process.env.DATA_DIR || '/data'; mkdirSync(dir,{recursive:true,mode:0o700});
const configPath=`${dir}/server.json`;
if(!existsSync(configPath)) {
  const key=generateKeyPairSync('x25519');
  writeFileSync(configPath,JSON.stringify({host:'24projects.ru',serverName:'www.microsoft.com',pepper:randomBytes(32).toString('hex'),privateKey:key.privateKey.export({format:'jwk'}).d,publicKey:key.publicKey.export({format:'jwk'}).x,shortId:randomBytes(8).toString('hex')}),{mode:0o600,flag:'wx'});
}
const config=JSON.parse(readFileSync(configPath,'utf8'));
const access=new Access(`${dir}/access.sqlite`,{pepper:config.pepper});
const push=new PushRelay(access);
const gateway=new Gateway(config,dir,process.env.XRAY_BINARY);
// Required private snapshot: missing or invalid inputs never enable a direct fallback.
const egress=new EgressManager(loadSnapshot(`${dir}/egress.json`),{
  statePath:`${dir}/egress-state.json`,binary:process.env.XRAY_BINARY,
  onChange:()=>gateway.reload(()=>access.approved(),()=>egress.selected()),
  log:entry=>console.log(JSON.stringify(entry))
});
await gateway.reload(()=>access.approved(),()=>egress.selected());
let modifying=false;
async function management(command,id,hours,actor=null) {
  if(modifying)throw new ApiError(409,'operation_in_progress');
  modifying=true;
  try {
    if(actor!==null) {
      if(!access.isPhoneOwner(access.device(actor)))throw new ApiError(403,'owner_required');
      if(!['invite','list','approve','revoke'].includes(command))throw new ApiError(400,'invalid_management');
      if(['approve','revoke'].includes(command)&&actor===id)throw new ApiError(409,'cannot_modify_owner_phone');
    }
    const result=access.owner(command,id,hours);
    if(command==='revoke')push.revoke(id);
    if(['approve','revoke'].includes(command))await gateway.reload(()=>access.approved(),()=>egress.selected());
    return result;
  } finally {modifying=false;}
}
const api=apiServer(access,gateway,{trustProxy:process.env.TRUST_PROXY==='1',push,
  manage:async(actor,p)=>{
    const result=await management(p.command,p.device_id,p.hours,actor);
    if(p.command!=='list')return result;
    const devices=result.filter(d=>d.status!=='new');
    return {devices:devices.slice(-50),truncated:devices.length>50};
  }});
api.listen(8080,'0.0.0.0');
const socket=`${dir}/owner.sock`; if(existsSync(socket)) unlinkSync(socket);
const admin=createServer(async(req,res)=>{
  try {
    if(req.method!=='POST'||req.url!=='/owner') throw new ApiError(404,'not_found');
    if(modifying) throw new ApiError(409,'operation_in_progress');
    const {command,id,hours}=await body(req);
    // body reads yield; recheck after parsing to serialize all ownership mutations.
    if(modifying) throw new ApiError(409,'operation_in_progress');
    send(res,200,await management(command,id,hours));
  } catch(e) {failure(res,e);}
});
admin.listen(socket,()=>chmodSync(socket,0o600));
setInterval(async()=>{
  if(gateway.listening || modifying || stopping) return;
  modifying=true;
  try {await gateway.reload(()=>access.approved(),()=>egress.selected());}
  catch {console.error('Managed gateway unavailable; retrying in 30 seconds');}
  finally {modifying=false;}
},30000).unref();
let stopping=false;
async function stop() {if(stopping)return;stopping=true;push.close();api.close();admin.close();await egress.close();await gateway.stop();access.close();process.exit(0);}
process.on('SIGTERM',stop); process.on('SIGINT',stop);
console.log('FamilyGram access service ready');
void egress.start();
