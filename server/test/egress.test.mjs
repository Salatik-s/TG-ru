import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {EgressManager,validateSnapshot} from '../egress.mjs';
import {Gateway,xrayConfig} from '../gateway.mjs';

const node=(n,tier='regular')=>({tag:'node-'+n.toString(16).padStart(24,'0'),tier,outbound:{protocol:'vless',settings:{address:'vpn.example.org',port:443,id:'11111111-1111-4111-8111-111111111111',encryption:'none',flow:'xtls-rprx-vision'},streamSettings:{security:'tls',tlsSettings:{serverName:'vpn.example.org',allowInsecure:false}}}});
const snapshot=(...candidates)=>({version:1,preferredTag:null,candidates});
const config={serverName:'cover.example.org',privateKey:'test',shortId:'0123456789abcdef'};
test('egress accepts only encrypted public native routes and retains UDP443 Vision support',()=>{
 const original=snapshot(node(1));const valid=validateSnapshot(original);
 assert.equal(valid.candidates[0].outbound.settings.flow,'xtls-rprx-vision-udp443');assert.equal(original.candidates[0].outbound.settings.flow,'xtls-rprx-vision');
 for(const mutation of [s=>s.candidates[0].outbound.protocol='freedom',s=>s.candidates[0].outbound.protocol='socks',s=>s.candidates[0].outbound.streamSettings.security='none',s=>s.candidates[0].outbound.streamSettings.tlsSettings.allowInsecure=true,s=>s.candidates[0].outbound.settings.address='127.0.0.1',s=>s.candidates[0].outbound.proxySettings={tag:'direct'},s=>s.candidates.push(structuredClone(s.candidates[0]))]){
  const changed=structuredClone(original);mutation(changed);assert.throws(()=>validateSnapshot(changed),/Invalid private/);
 }
 assert.throws(()=>validateSnapshot(snapshot(...Array.from({length:257},(_,i)=>node(i+1)))));
});
test('gateway defaults to block and routes TCP+UDP only to selected encrypted outbound after private-address blocks',()=>{
 const blocked=xrayConfig(config,[]);assert.deepEqual(blocked.outbounds.map(o=>o.protocol),['blackhole']);assert.equal(blocked.routing.rules.at(-1).outboundTag,'block');
 const active=xrayConfig(config,[],validateSnapshot(snapshot(node(1))).candidates[0]);
 assert.deepEqual(active.outbounds.map(o=>o.protocol),['blackhole','vless']);assert.equal(active.routing.rules.at(-1).network,'tcp,udp');assert.equal(active.routing.rules.at(-1).outboundTag,'family-egress');assert(active.routing.rules.slice(0,-1).every(r=>r.outboundTag==='block'));
 assert(!JSON.stringify(active).includes('freedom'));assert(!JSON.stringify(active).includes('socks'));
});
test('REALITY and internal WebSocket share approved UUIDs and revoke both without Vision on WebSocket',()=>{
 const devices=[{uuid:'11111111-1111-4111-8111-111111111111'},{uuid:'22222222-2222-4222-8222-222222222222'}];
 const current=xrayConfig(config,devices,node(1)),reality=current.inbounds.find(i=>i.tag==='family-reality'),ws=current.inbounds.find(i=>i.tag==='family-ws');
 assert.equal(reality.port,18443);assert(reality.settings.clients.every(c=>c.flow==='xtls-rprx-vision'));
 assert.equal(ws.port,10000);assert.equal(ws.listen,'0.0.0.0');assert.deepEqual(ws.streamSettings,{network:'ws',security:'none',wsSettings:{path:'/v1/transport'}});
 assert.deepEqual(ws.settings.clients,devices.map(d=>({id:d.uuid})));
 const revoked=xrayConfig(config,[devices[1]],node(1));for(const inbound of revoked.inbounds)assert.deepEqual(inbound.settings.clients.map(c=>c.id),[devices[1].uuid]);
 assert.equal(current.routing.rules.at(-1).network,'tcp,udp');assert(!JSON.stringify(current).includes('freedom'));
});
test('all regular candidates are tested across bounded batches before BS; cursor survives restart',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'family-egress-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
 const statePath=join(dir,'state.json'),s=snapshot(node(1),node(2),node(3),node(4),node(5),node(6,'bs')),seen=[];
 const probe=async o=>{seen.push(o.tag);return {ok:o.tag===node(6).tag};};
 const first=new EgressManager(s,{probe,statePath,batchSize:2});await first.tick();assert.deepEqual(seen,[node(1).tag,node(2).tag]);await first.close();
 const next=new EgressManager(s,{probe,statePath,batchSize:2});await next.tick();assert.deepEqual(seen,[1,2,3,4].map(n=>node(n).tag));assert.equal(next.selected(),null);
 await next.tick();assert.deepEqual(seen,[1,2,3,4,5,6].map(n=>node(n).tag));assert.equal(next.selected().tier,'bs');
 assert(!readFileSync(statePath,'utf8').includes('11111111-1111'));await next.close();
});
test('failed active route is withdrawn before fallback, regular recovery replaces healthy BS',async()=>{
 let now=0;const healthy=new Set([node(1).tag]),changes=[];
 const m=new EgressManager(snapshot(node(1),node(2,'bs')),{probe:async o=>healthy.has(o.tag),onChange:async c=>changes.push(c?.tier||'blocked'),batchSize:2,regularRecoveryMs:10,now:()=>now});
 await m.tick();assert.equal(m.selected().tier,'regular');healthy.delete(node(1).tag);healthy.add(node(2).tag);
 await m.tick();assert.deepEqual(changes,['regular','blocked','bs']);
 now=20;healthy.add(node(1).tag);await m.tick();assert.equal(m.selected().tier,'regular');await m.close();
});
test('overlapping ticks share one probe; shutdown abort cannot select a late successful result',async()=>{
 let resolveProbe,calls=0;const changes=[];
 const m=new EgressManager(snapshot(node(1)),{probe:()=>{calls++;return new Promise(r=>resolveProbe=r);},onChange:async c=>changes.push(c)});
 const a=m.tick(),b=m.tick();assert.equal(calls,1);assert.equal(a,b);const closing=m.close();resolveProbe({ok:true});await Promise.all([a,b,closing]);assert.equal(m.selected(),null);assert.equal(changes.length,0);
});
test('queued route reload always reads latest membership and closes obsolete generation before applying revoke',async()=>{
 class FakeGateway extends Gateway{
  constructor(){super(config,'unused');this.appliedDevices=[];this.releaseFirst=null;this.firstStarted=new Promise(r=>this.started=r);this.count=0;}
  async stopChild(){this.ready=false;this.child=null;}
  async launch(devices,route,revision){this.count++;this.appliedDevices.push(devices.map(d=>d.uuid));if(this.count===1){this.started();await new Promise(r=>this.releaseFirst=r);}if(revision===this.revision)this.ready=Boolean(route);}
 }
 const g=new FakeGateway();let devices=[{uuid:'revoked-user'}],route=node(1);
 const first=g.reload(()=>devices,()=>route);await g.firstStarted;
 devices=[];const revoke=g.reload(()=>devices,()=>route);route=node(2);const switchRoute=g.reload(()=>devices,()=>route);
 g.releaseFirst();await Promise.all([first,revoke,switchRoute]);assert.deepEqual(g.appliedDevices,[['revoked-user'],[]]);assert.equal(g.applied,g.revision);assert(g.ready);await g.stop();
});
