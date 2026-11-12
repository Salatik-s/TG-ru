import {readFileSync,writeFileSync,mkdirSync,copyFileSync,existsSync} from 'node:fs';
import {join,dirname} from 'node:path';
import {createHash} from 'node:crypto';
const root='C:/Users/andre/Documents/Codex/familygram-build-2026-11-08';
const task=join(root,'build-support/native-13.2.1');
const commit='979e175a29f666768ebd0f134e27630fc9b65d4b';
const tree=JSON.parse(readFileSync(join(task,'git-tree.json'),'utf8'));
if(tree.truncated||tree.sha!==commit)throw new Error('Wrong or incomplete tree');
const blobs=new Map(tree.tree.map(e=>[e.path,e.sha]));
const old=JSON.parse(readFileSync(join(root,'native/upstream-lock.json'),'utf8'));
const changed=[];
const hash=b=>createHash('sha1').update('blob '+b.length+'\0').update(b).digest('hex');
for(const name of ['build.ps1','build.sh','CMakeLists.txt','prepare.mjs','FamilyTransport.cpp','FamilyTransport.h','FamilyJni.cpp','ConnectionSocket.transport.inc','exports.map','verify-elf.mjs']){
 copyFileSync(join(root,'native',name),join(task,name));
}
const entries=await Promise.all(old.files.map(async entry=>{
 const blob=blobs.get(entry.path);
 if(!blob)throw new Error('Missing path '+entry.path);
 let data=readFileSync(join(root,'native/upstream',entry.path));
 if(hash(data)!==entry.blob)throw new Error('Old source pin mismatch '+entry.path);
 if(blob!==entry.blob){
  const url='https://raw.githubusercontent.com/exteraless/exteraless/'+commit+'/'+entry.path;
  const response=await fetch(url,{signal:AbortSignal.timeout(60000)});
  if(!response.ok)throw new Error('Download '+response.status+' '+entry.path);
  data=Buffer.from(await response.arrayBuffer());
  changed.push({path:entry.path,old_blob:entry.blob,blob});
 }
 if(hash(data)!==blob)throw new Error('New source pin mismatch '+entry.path);
 const target=join(task,'upstream',entry.path);
 mkdirSync(dirname(target),{recursive:true});
 writeFileSync(target,data);
 return {path:entry.path,blob};
}));
writeFileSync(join(task,'upstream-lock.json'),JSON.stringify({repository:old.repository,commit,files:entries},null,2)+'\n');
writeFileSync(join(task,'upgrade-inputs.json'),JSON.stringify({commit,verified:entries.length,changed},null,2)+'\n');
console.log(JSON.stringify({verified:entries.length,changed:changed.length,task}));