import {readFileSync,writeFileSync,renameSync,statSync} from 'node:fs';
import {isIP} from 'node:net';
import {createHash} from 'node:crypto';
import {probeNativeOutbound,PROBE_CODES} from './egress-probe.mjs';

const fail=()=>{throw new Error('Invalid private egress snapshot');};
const tagPattern=/^node-[a-f0-9]{24,64}$/;
const ciphers=new Set(['aes-128-gcm','aes-256-gcm','chacha20-ietf-poly1305','xchacha20-poly1305','xchacha20-ietf-poly1305','2022-blake3-aes-128-gcm','2022-blake3-aes-256-gcm','2022-blake3-chacha20-poly1305']);
function publicHost(value){
  if(typeof value!=='string'||value.length>253)return false;
  if(isIP(value)===4){const [a,b]=value.split('.').map(Number);return !([0,10,127].includes(a)||a>=224||(a===169&&b===254)||(a===172&&b>=16&&b<=31)||(a===192&&b===168)||(a===100&&b>=64&&b<=127));}
  if(isIP(value)===6)return !/^(::($|1$|ffff:)|f[cd]|fe[89ab]|ff)/i.test(value);
  return value.includes('.')&&!/^(?:0x[\da-f]+|\d+)(?:\.(?:0x[\da-f]+|\d+)){0,3}$/i.test(value)&&/^[a-z0-9.-]+$/i.test(value)&&!/(^|\.)(localhost|local|internal)$/i.test(value)&&value.split('.').every(p=>/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(p));
}
function inspectObject(value){
  if(!value||typeof value!=='object')return;
  for(const [key,item] of Object.entries(value)){
    if(['proxySettings','dialerProxy','sendThrough','sockopt','dialer'].includes(key))fail();
    if(['allowInsecure','insecure'].includes(key)&&item!==false)fail();
    inspectObject(item);
  }
}
export function validateSnapshot(input){
  if(!input||input.version!==1||!Array.isArray(input.candidates)||!input.candidates.length||input.candidates.length>256)fail();
  if(input.preferredTag!==null&&input.preferredTag!==undefined&&!tagPattern.test(input.preferredTag))fail();
  const candidates=[],seen=new Set();
  for(const candidate of input.candidates){
    if(!candidate||!tagPattern.test(candidate.tag)||seen.has(candidate.tag)||!['regular','bs'].includes(candidate.tier))fail();
    seen.add(candidate.tag);
    const outbound=structuredClone(candidate.outbound);
    if(!outbound||!['vless','trojan','shadowsocks'].includes(outbound.protocol)||JSON.stringify(outbound).length>16384)fail();
    if(Object.keys(outbound).some(k=>!['tag','protocol','settings','streamSettings','mux'].includes(k)))fail();
    inspectObject(outbound);
    const settings=outbound.settings,security=outbound.streamSettings?.security||'none';
    if(!settings||!['tls','reality','none'].includes(security))fail();
    if(outbound.protocol!=='shadowsocks'&&!['tls','reality'].includes(security))fail();
    const endpoints=settings.address?[settings]:(settings.vnext||settings.servers);
    if(!Array.isArray(endpoints)||endpoints.length!==1)fail();
    for(const endpoint of endpoints){
      if(!publicHost(endpoint.address)||!Number.isInteger(endpoint.port)||endpoint.port<1||endpoint.port>65535)fail();
      if(outbound.protocol==='shadowsocks'&&(!ciphers.has(endpoint.method)||typeof endpoint.password!=='string'||!endpoint.password))fail();
      if(outbound.protocol==='trojan'&&(typeof endpoint.password!=='string'||!endpoint.password))fail();
      for(const user of endpoint.users||[endpoint]){
        if(user.flow==='xtls-rprx-vision')user.flow='xtls-rprx-vision-udp443';
      }
    }
    outbound.tag=candidate.tag;
    candidates.push({tag:candidate.tag,tier:candidate.tier,outbound});
  }
  return {version:1,preferredTag:seen.has(input.preferredTag)?input.preferredTag:null,candidates};
}
export function loadSnapshot(path){
  if(statSync(path).size>4*1024*1024)fail();
  try{return validateSnapshot(JSON.parse(readFileSync(path,'utf8')));}catch{fail();}
}

export class EgressManager {
  constructor(snapshot,{probe=probeNativeOutbound,onChange=async()=>{},statePath=null,binary='/usr/local/bin/xray',batchSize=4,timeoutMs=6000,intervalMs=5000,healthyIntervalMs=30000,regularRecoveryMs=120000,now=Date.now,log=()=>{}}={}){
    this.snapshot=validateSnapshot(snapshot);this.probe=probe;this.onChange=onChange;this.statePath=statePath;this.binary=binary;
    this.batchSize=Math.max(1,Math.min(6,batchSize));this.timeoutMs=timeoutMs;this.intervalMs=intervalMs;this.healthyIntervalMs=healthyIntervalMs;this.regularRecoveryMs=regularRecoveryMs;this.now=now;this.log=log;
    this.current=null;this.phase='regular';this.cursor=0;this.lastGood=null;this.running=null;this.stopped=false;this.timer=null;this.abort=new AbortController();this.recoveryCursor=0;this.nextRecovery=0;
    this.snapshotHash=createHash('sha256').update(JSON.stringify(this.snapshot)).digest('hex');
    let recheckSelection=false;
    if(statePath){try{const state=JSON.parse(readFileSync(statePath,'utf8'));if(state.version===1&&state.snapshotHash===this.snapshotHash){if(['regular','bs'].includes(state.phase))this.phase=state.phase;if(Number.isInteger(state.cursor)&&state.cursor>=0)this.cursor=state.cursor;if(tagPattern.test(state.lastGood||''))this.lastGood=state.lastGood;recheckSelection=state.selected===true;}}catch{}}
    const hints=[this.lastGood,this.snapshot.preferredTag];
    this.regular=this.snapshot.candidates.filter(c=>c.tier==='regular').sort((a,b)=>{const rank=c=>hints.includes(c.tag)?hints.indexOf(c.tag):-1;return (rank(a)<0?99:rank(a))-(rank(b)<0?99:rank(b));});
    this.bs=this.snapshot.candidates.filter(c=>c.tier==='bs');
    // Restart never trusts a previously selected route; persisted cursor only preserves a failed sweep.
    if(recheckSelection){this.phase='regular';this.cursor=0;}
    if(this.cursor>(this.phase==='regular'?this.regular:this.bs).length)this.cursor=0;
  }
  selected(){return this.current;}
  save(){if(!this.statePath)return;const temp=`${this.statePath}.tmp`;writeFileSync(temp,JSON.stringify({version:1,snapshotHash:this.snapshotHash,phase:this.phase,cursor:this.cursor,lastGood:this.lastGood,selected:Boolean(this.current)}),{mode:0o600});renameSync(temp,this.statePath);}
  async check(candidate){
    try{
      const result=await this.probe(candidate.outbound,{binary:this.binary,timeoutMs:this.timeoutMs,signal:this.abort.signal});
      const ok=!this.stopped&&(result===true||result?.ok===true);
      if(!ok&&!this.stopped)this.log({event:'egress-probe',state:'failed',tier:candidate.tier,code:PROBE_CODES.includes(result?.code)?result.code:'NETWORK_ERROR'});
      return ok;
    }catch{if(!this.stopped)this.log({event:'egress-probe',state:'failed',tier:candidate.tier,code:'NETWORK_ERROR'});return false;}
  }
  async select(candidate){
    if(this.stopped)return;
    const previous=this.current;this.current=candidate;
    if(candidate)this.lastGood=candidate.tag;
    this.save();
    if(previous?.tag!==candidate?.tag){this.log({event:'egress-route',state:candidate?'selected':'unavailable',tier:candidate?.tier||null});await this.onChange(candidate);}
  }
  tick(){if(this.stopped)return Promise.resolve();if(this.running)return this.running;this.running=this.cycle().finally(()=>{this.running=null;});return this.running;}
  async cycle(){
    let failedTag=null;
    if(this.current){
      if(await this.check(this.current)){
        if(this.current.tier==='bs'&&this.now()>=this.nextRecovery){
          for(let n=0;n<this.batchSize&&this.recoveryCursor<this.regular.length;n++){
            const candidate=this.regular[this.recoveryCursor++];if(await this.check(candidate)){this.recoveryCursor=0;await this.select(candidate);return;}
            if(this.stopped)return;
          }
          if(this.recoveryCursor>=this.regular.length){this.recoveryCursor=0;this.nextRecovery=this.now()+this.regularRecoveryMs;}
        }
        return;
      }
      if(this.stopped)return;
      failedTag=this.current.tag;await this.select(null);this.phase='regular';this.cursor=0;
    }
    for(let examined=0;examined<this.batchSize&&!this.stopped;){
      let list=this.phase==='regular'?this.regular:this.bs;
      if(this.cursor>=list.length){
        if(this.phase==='regular'){this.phase='bs';this.cursor=0;list=this.bs;if(!list.length){this.phase='regular';this.save();return;}}
        else{this.phase='regular';this.cursor=0;this.save();return;}
      }
      const candidate=list[this.cursor++];examined++;
      if(candidate.tag!==failedTag&&await this.check(candidate)){await this.select(candidate);this.phase='regular';this.cursor=0;this.nextRecovery=this.now()+this.regularRecoveryMs;this.save();return;}
      this.save();
    }
  }
  start(){
    const loop=async()=>{try{await this.tick();if(!this.current)this.log({event:'egress-search',state:'unavailable',tier:this.phase,checked_in_tier:this.cursor,regular_candidates:this.regular.length,bs_candidates:this.bs.length});}catch{this.log({event:'egress-cycle',state:'failed'});}finally{if(!this.stopped){this.timer=setTimeout(loop,this.current?this.healthyIntervalMs:this.intervalMs);this.timer.unref();}}};
    return loop();
  }
  async close(){this.stopped=true;clearTimeout(this.timer);this.abort.abort();try{await this.running;}catch{}this.current=null;}
}
