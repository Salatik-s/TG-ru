import {readFileSync,writeFileSync} from 'node:fs';
import {homedir} from 'node:os';
import {join} from 'node:path';
const dir=join(homedir(),'.codex/private/familygram-qa');
const [command,id,hours]=process.argv.slice(2);
if(!['invite','list','approve','revoke'].includes(command))throw new Error('Usage: control.mjs invite|list|approve|revoke [64hex device ID] [hours]');
if(['approve','revoke'].includes(command)&&!(/^[a-f0-9]{64}$/.test(id||'')))throw new Error('Exact lowercase 64-hex device ID required');
const settings=JSON.parse(readFileSync(join(dir,'settings.json'),'utf8'));
const response=await fetch('http://127.0.0.1:18444/owner',{method:'POST',headers:{'content-type':'application/json',authorization:`Bearer ${settings.ownerToken}`},body:JSON.stringify({command,id,hours:hours?Number(hours):24}),signal:AbortSignal.timeout(5000)});
const result=await response.json();
if(!response.ok)throw new Error(result.error||'Owner command failed');
if(command==='invite'){
 const path=join(dir,'credentials.json');writeFileSync(path,JSON.stringify(result),{mode:0o600});console.log(JSON.stringify({saved:path}));
}else console.log(JSON.stringify(result,null,2));
