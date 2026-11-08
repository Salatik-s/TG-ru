import {readFileSync,writeFileSync,existsSync} from 'node:fs';
import {homedir} from 'node:os';
import {join} from 'node:path';
import {createServer as httpsServer} from 'node:https';
import {createServer as httpServer} from 'node:http';
import {timingSafeEqual} from 'node:crypto';
import {Access,ApiError} from '../server/access.mjs';
import {apiServer,body,send,failure} from '../server/http.mjs';
import {PushRelay} from '../server/push.mjs';
process.umask(0o077);
const dir=join(homedir(),'.codex/private/familygram-qa'),path=n=>join(dir,n);
const settings=JSON.parse(readFileSync(path('settings.json'),'utf8'));
const access=new Access(path('access.sqlite'),{pepper:settings.pepper});
const push=new PushRelay(access);
// QA-only transport provisioning: no listening gateway and no external Telegram access.
const gateway={ready:true,client:d=>({protocol:'vless',host:'10.0.2.2',port:18443,uuid:d.uuid,security:'reality',public_key:settings.publicKey,short_id:settings.shortId,server_name:'www.microsoft.com',flow:'xtls-rprx-vision',fingerprint:'chrome'})};
const productionHttp=apiServer(access,gateway,{push});
const api=httpsServer({key:readFileSync(path('server.key')),cert:readFileSync(path('server.pem')),minVersion:'TLSv1.2'},productionHttp.listeners('request')[0]);
api.requestTimeout=10000;api.headersTimeout=10000;api.maxHeadersCount=32;
const owner=httpServer(async(req,res)=>{
 try{
  const actual=Buffer.from(req.headers.authorization||''),expected=Buffer.from(`Bearer ${settings.ownerToken}`);
  if(actual.length!==expected.length||!timingSafeEqual(actual,expected))throw new ApiError(401,'unauthorized');
  if(req.method!=='POST'||req.url!=='/owner')throw new ApiError(404,'not_found');
  const {command,id,hours}=await body(req);
  if(!['invite','list','approve','revoke'].includes(command))throw new ApiError(400,'unknown_command');
  if(['approve','revoke'].includes(command)&&!(/^[a-f0-9]{64}$/.test(id||'')))throw new ApiError(400,'invalid_id');
  const result=access.owner(command,id,hours);
  if(command==='revoke')push.revoke(id);
  send(res,200,result);
 }catch(e){failure(res,e);}
});
owner.requestTimeout=5000;owner.headersTimeout=5000;owner.maxHeadersCount=16;
const listen=(server,port)=>new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve);});
try{await listen(owner,18444);await listen(api,8443);}catch(e){push.close();api.close();owner.close();access.close();throw e;}
if(!existsSync(path('credentials.json')))writeFileSync(path('credentials.json'),JSON.stringify(access.owner('invite',undefined,24)),{mode:0o600,flag:'wx'});
writeFileSync(path('runtime.json'),JSON.stringify({pid:process.pid,api:'127.0.0.1:8443',owner:'127.0.0.1:18444',gateway:'configuration-only-no-listener',started:new Date().toISOString()}));
console.log('QA backend ready: HTTPS 127.0.0.1:8443; owner 127.0.0.1:18444; no gateway listener.');
let stopping=false;
function stop(){if(stopping)return;stopping=true;push.close();api.close();owner.close();api.closeAllConnections();owner.closeAllConnections();access.close();process.exit(0);}
process.on('SIGINT',stop);process.on('SIGTERM',stop);
