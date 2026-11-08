// One owner-created FamilyGram invitation, kept only in the restricted local test directory.
import {spawn} from 'node:child_process';
import {writeFileSync,renameSync,lstatSync,existsSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {homedir} from 'node:os';

const directory=resolve(process.argv[2]||'');
const expected=resolve(homedir(),'.codex/private/familygram-test-access');
if(directory!==expected||lstatSync(directory).isSymbolicLink()||!lstatSync(directory).isDirectory())throw Error('Unexpected private test directory');
const output=join(directory,'invite.json'),temporary=join(directory,'invite.next.json');
for(const name of [output,temporary])if(existsSync(name)&&(!lstatSync(name).isFile()||lstatSync(name).isSymbolicLink()))throw Error('Unexpected private test file');
const remote='cd /opt/familygram/current/deploy && docker compose exec -T access node owner.mjs invite';
const child=spawn('ssh',['-T','-o','ConnectTimeout=15','root@157.228.191.231',remote],{stdio:['inherit','pipe','inherit']});
let response='';
child.stdout.setEncoding('utf8');child.stdout.on('data',part=>{if(response.length+part.length>8192){child.kill();return;}response+=part;});
child.on('error',()=>{console.error('Owner SSH could not start.');process.exitCode=1;});
child.on('close',code=>{
  if(code!==0){console.error('Owner SSH could not create the test invitation.');process.exitCode=1;return;}
  try{
    const result=JSON.parse(response.trim());
    if(typeof result.code!=='string'||!/^([A-F0-9]{30})$/.test(result.code)||!Number.isSafeInteger(result.expires_at))throw Error();
    writeFileSync(temporary,JSON.stringify({code:result.code,expires_at:result.expires_at}),{mode:0o600});
    renameSync(temporary,output);
    console.log('Test invitation prepared privately. No invitation code was printed.');
    console.log('The Android test installation will request normal owner approval next.');
  }catch{
    console.error('Unexpected invitation response; private output was not printed.');process.exitCode=1;
  }finally{response='';}
});
