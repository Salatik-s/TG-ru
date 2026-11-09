const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../python/Resources/family-python/runner/controller.mjs'), 'utf8');
function fixture() {
  let now = 0, next = 0;
  const timers = new Map(), events = [], workers = [];
  class Worker {
    constructor(url, options) { assert.equal(url, '/runner/worker.mjs'); assert.equal(options.type, 'module'); workers.push(this); }
    terminate() { this.terminated = true; }
    postMessage(data) { this.received = data; }
    reply(data) { this.onmessage({data}); }
  }
  const context = vm.createContext({Worker, window: {webkit: {messageHandlers: {familyPython: {postMessage: e => events.push(e)}}}},
    setTimeout: (cb, ms) => {const id = ++next; timers.set(id, {at: now + ms, cb}); return id;}, clearTimeout: id => timers.delete(id)});
  vm.runInContext(source, context);
  function advance(ms) {const end = now + ms; for (;;) {let found = [...timers].filter(([,t]) => t.at <= end).sort((a,b) => a[1].at-b[1].at)[0]; if (!found) break; now = found[1].at; timers.delete(found[0]); found[1].cb();} now = end;}
  return {context, workers, events, advance};
}
{
 const f=fixture(); f.context.executePython({id:'a',code:'print(1)'}); f.advance(25000);
 assert.equal(f.events.at(-1).type,'loading'); f.workers[0].reply({type:'ready'}); f.advance(9999);
 assert.equal(f.events.at(-1).type,'running'); f.advance(1);
 assert.equal(f.events.at(-1).code,'execution_timeout'); assert.equal(f.workers[0].terminated,true);
}
{
 const f=fixture(); f.context.executePython({id:'a',code:'while True: pass'}); f.advance(30000);
 assert.equal(f.events.at(-1).code,'bootstrap_timeout'); assert.equal(f.workers[0].received,undefined);
}
{
 const f=fixture(); f.context.executePython({id:'old',code:'pass'}); const old=f.workers[0];
 f.context.executePython({id:'new',code:'print(2)',input:'x'}); old.reply({type:'ready'});
 assert.equal(old.terminated,true); assert.equal(old.received,undefined);
 const current=f.workers[1]; current.reply({type:'ready'}); current.reply({type:'result',text:'2'});
 assert.equal(f.events.at(-1).id,'new'); assert.equal(f.events.at(-1).text,'2');
 f.advance(60000); assert.equal(f.events.at(-1).type,'result');
}
{
 const f=fixture(); f.context.executePython({id:'a',code:'pass'}); f.context.stopRun(); f.advance(60000);
 assert.equal(f.events.at(-1).type,'loading'); assert.equal(f.workers[0].terminated,true);
}
console.log('Python controller: boot/run budgets, stale epoch, completion, cancel PASS');
