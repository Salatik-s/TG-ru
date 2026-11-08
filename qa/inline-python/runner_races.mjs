import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {Worker as NodeWorker} from 'node:worker_threads';
const base=new URL('../../overlay/',import.meta.url);
const java=fs.readFileSync(new URL('java/org/telegram/messenger/family/FamilyPythonRunner.java',base),'utf8');
const editor=fs.readFileSync(new URL('java/org/telegram/messenger/family/FamilyPythonActivity.java',base),'utf8');
const html=fs.readFileSync(new URL('assets/familygram/runner/index.html',base),'utf8');
const script=fs.readFileSync(new URL('assets/familygram/runner/controller.mjs',base),'utf8');
for(const src of [java,editor]){assert.ok(src.includes('controller.mjs'));assert.ok(src.includes('setBlockNetworkLoads(true)'));assert.ok(!src.includes('addJavascriptInterface('));assert.ok(src.includes('10_000'));assert.ok(src.includes('35_000'));}
assert.ok(html.includes('/runner/controller.mjs'));
assert.ok(editor.includes('event.optLong("id",-1)!=epoch'));
for(const prefix of ['SAA_INLINE:','SAA_RUNNER:']){
 const workers=[],events=[],timers=new Map();let nextTimer=1;
 class Worker{constructor(){workers.push(this);}terminate(){this.terminated=true;}postMessage(value){this.input=value;}}
 const context=vm.createContext({Worker,SAA_PREFIX:prefix,console:{log(value){events.push(JSON.parse(value.slice(prefix.length)));}},setTimeout(fn,delay){const id=nextTimer++;timers.set(id,{fn,delay});return id;},clearTimeout(id){timers.delete(id);}});
 vm.runInContext(script,context);
 const start=id=>{context.executePython({id,code:'print(1)'});return workers.at(-1);};
 let first=start(1);assert.equal([...timers.values()][0].delay,30000);first.onmessage({data:{type:'ready'}});assert.equal([...timers.values()][0].delay,10000);
 let second=start(2);assert.ok(first.terminated);const before=events.length;first.onmessage({data:{type:'result',text:'STALE'}});first.onerror();assert.equal(events.length,before);
 second.onmessage({data:{type:'ready'}});second.onmessage({data:{type:'error',code:'python_exception',text:'NameError: bad'}});assert.equal(events.at(-1).type,'error');assert.equal(events.at(-1).text,'NameError: bad');assert.equal(events.at(-1).phase,'run');assert.ok(second.terminated);
 let third=start(3);third.onmessage({data:{type:'ready'}});[...timers.values()][0].fn();assert.equal(events.at(-1).code,'execution_timeout');assert.match(events.at(-1).text,/10/);assert.ok(third.terminated);
 let fourth=start(4);[...timers.values()][0].fn();assert.equal(events.at(-1).code,'bootstrap_timeout');assert.equal(events.at(-1).phase,'bootstrap');assert.match(events.at(-1).text,/Не удалось запустить среду Python/);assert.ok(fourth.terminated);
 let fifth=start(5);context.stopRun();const stopped=events.length;fifth.onmessage({data:{type:'ready'}});fifth.onerror();assert.equal(events.length,stopped);assert.equal(timers.size,0);
 let sixth=start(6);sixth.onerror({message:'wasm missing'});assert.match(events.at(-1).text,/wasm missing/);assert.equal(events.at(-1).code,'bootstrap_failed');
 let seventh=start(7);seventh.onmessage({data:{type:'ready'}});context.stopRun();const count=events.length;seventh.onmessage({data:{type:'result',text:'late'}});assert.equal(events.length,count);
}
console.log('PASS both surfaces: boot30/run10 clocks, reason/phase, epoch replacement and stop at each stage');
// Real pinned CPython-in-Wasm: verify exception type and source line, not mocked strings.
const worker=new NodeWorker(new URL('./runtime-worker.mjs',import.meta.url));
function next(){return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{cleanup();reject(new Error('Harness wait timeout'));},45000);const message=value=>{cleanup();resolve(value);};const error=value=>{cleanup();reject(value);};function cleanup(){clearTimeout(timer);worker.off('message',message);worker.off('error',error);}worker.on('message',message);worker.on('error',error);});}
try{
 assert.equal((await next()).type,'ready');
 for(const [code,kind,line] of [['print(2+2)',null,null],['if True print(1)','SyntaxError',1],['print(1)\nmissing_name','NameError',2],['print(1)\n1 / 0','ZeroDivisionError',2]]){
  const waiting=next();worker.postMessage({code,input:''});const result=await waiting;
  assert.equal(result.type,kind?'error':'result');
  if(kind){assert.equal(result.code,'python_exception');assert.match(result.text,new RegExp(kind));assert.match(result.text,new RegExp('line '+line));}else assert.equal(result.text.trim(),'4');
 }
 const waiting=next();worker.postMessage({code:"print('x' * 70000)",input:''});const limited=await waiting;assert.equal(limited.type,'error');assert.ok(limited.text.length<=65536);assert.match(limited.text,/Лимит вывода/);
 console.log('PASS actual shipped Pyodide: print, SyntaxError, NameError, ZeroDivisionError, traceback line, bounded overflow error');
 const start=performance.now();worker.postMessage({code:'while True: pass',input:''});await new Promise(resolve=>setTimeout(resolve,10000));await worker.terminate();const elapsed=performance.now()-start;assert.ok(elapsed>=10000&&elapsed<13000,`termination elapsed ${elapsed}`);console.log(`PASS actual infinite Wasm worker terminated after ${Math.round(elapsed)}ms`);
}finally{await worker.terminate();}
