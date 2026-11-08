// A temporary Xray client carries an account-free MTProto handshake. Secrets
// enter only the child's stdin; neither its output nor its config is returned.
import {spawn} from 'node:child_process';
import {createServer,createConnection,isIP} from 'node:net';
import {randomBytes,createCipheriv,createDecipheriv} from 'node:crypto';
import {performance} from 'node:perf_hooks';

export const PROBE_CODES=Object.freeze(['OK','INVALID_OPTIONS','INVALID_OUTBOUND','ABORTED','TIMEOUT','PORT_BUSY','BIND_FAILED','SPAWN_FAILED','INPUT_FAILED','CHILD_EXIT','CONNECT_FAILED','NETWORK_ERROR','EOF','INVALID_RESPONSE','CLEANUP_FAILED']);
const fault=code=>Object.assign(new Error(code),{code});
const abortCode=signal=>PROBE_CODES.includes(signal?.reason)?signal.reason:'ABORTED';

export function buildReqPQ(nonce,time=Date.now()) {
  if(!Buffer.isBuffer(nonce)||nonce.length!==16||!Number.isSafeInteger(time)||time<0)throw fault('INVALID_OPTIONS');
  const frame=Buffer.alloc(42);frame[0]=0xef;frame[1]=10;
  frame.writeBigUInt64LE(((BigInt(time)<<32n)/1000n)&~3n,10);
  frame.writeUInt32LE(20,18);frame.writeUInt32LE(0xbe7e8ef1,22);nonce.copy(frame,26);
  return frame;
}

// Pinned tgnet Connection.cpp:520-619, secretless ProtocolTypeEF. The forward
// CTR stream consumes all 64 header bytes; only encrypted bytes 56..63 are
// transmitted. The reverse stream starts at counter zero (no header consumed).
export function createObfuscated2Transport(random=randomBytes) {
  let header;
  const forbidden=new Set([0x44414548,0x54534f50,0x20544547,0x4954504f,0xeeeeeeee,0xdddddddd,0x02010316]);
  for(let tries=0;tries<128;tries++){
    const candidate=random(64);
    if(!Buffer.isBuffer(candidate)||candidate.length!==64)throw fault('INVALID_OPTIONS');
    if(candidate[0]!==0xef&&!forbidden.has(candidate.readUInt32LE(0))&&candidate.readUInt32LE(4)!==0){header=Buffer.from(candidate);break;}
  }
  if(!header)throw fault('INVALID_OPTIONS');
  header.fill(0xef,56,60);header.writeInt16LE(2,60); // Diagnostic DC2; no MTProxy secret.
  const reversed=Buffer.from(header.subarray(8,56)).reverse();
  const outgoing=createCipheriv('aes-256-ctr',header.subarray(8,40),header.subarray(40,56));
  const incoming=createDecipheriv('aes-256-ctr',reversed.subarray(0,32),reversed.subarray(32,48));
  const encrypted=outgoing.update(header);encrypted.copy(header,56,56,64);
  return {header,encrypt:bytes=>outgoing.update(bytes),decrypt:bytes=>incoming.update(bytes)};
}

// null means incomplete; false means malformed/unrelated; true requires the
// full resPQ object, matching nonce and bounded RSA fingerprint vector.
export function parsePQ(bytes,nonce) {
  if(!Buffer.isBuffer(bytes)||!Buffer.isBuffer(nonce)||nonce.length!==16)return false;
  if(bytes.length<1)return null;
  if(bytes[0]>127)return false;
  const header=bytes[0]===127?4:1;
  if(bytes.length<header)return null;
  const size=(header===4?bytes.readUIntLE(1,3):bytes[0])*4;
  if(size<76||size>4096)return false;
  if(bytes.length<header+size)return null;
  const body=bytes.subarray(header,header+size);
  if(body.readBigUInt64LE(0)!==0n||body.readUInt32LE(16)!==size-20)return false;
  const object=body.subarray(20);
  if(object.readUInt32LE(0)!==0x05162463||!object.subarray(4,20).equals(nonce))return false;
  const pqLength=object[36];
  if(pqLength<1||pqLength>8)return false;
  const vector=36+((pqLength+4)&~3);
  if(vector+8>object.length||object.readUInt32LE(vector)!==0x1cb5c415)return false;
  const count=object.readUInt32LE(vector+4);
  return count>=1&&count<=64&&vector+8+count*8===object.length;
}

function delay(ms,signal) {
  return new Promise((resolve,reject)=>{
    if(signal?.aborted){reject(fault(abortCode(signal)));return;}
    const stop=()=>{clearTimeout(timer);signal?.removeEventListener('abort',stop);reject(fault(abortCode(signal)));};
    const timer=setTimeout(()=>{signal?.removeEventListener('abort',stop);resolve();},ms);
    signal?.addEventListener('abort',stop,{once:true});
  });
}

function reservePort(signal) {
  return new Promise((resolve,reject)=>{
    const server=createServer();let done=false;
    const finish=(error,port)=>{if(done)return;done=true;signal.removeEventListener('abort',stop);if(error)reject(error);else resolve(port);};
    const stop=()=>{server.close(()=>{});finish(fault(abortCode(signal)));};
    server.on('error',()=>finish(fault('BIND_FAILED')));
    server.on('listening',()=>{
      const port=server.address().port;
      server.close(error=>finish(error?fault('BIND_FAILED'):null,port));
    });
    if(signal.aborted){finish(fault(abortCode(signal)));return;}
    signal.addEventListener('abort',stop,{once:true});
    server.listen({host:'127.0.0.1',port:0,exclusive:true});
  });
}

function connectOnce(port,signal) {
  return new Promise((resolve,reject)=>{
    if(signal.aborted){reject(fault(abortCode(signal)));return;}
    const socket=createConnection({host:'127.0.0.1',port,family:4});let done=false;
    const finish=(error)=>{
      if(done)return;done=true;signal.removeEventListener('abort',stop);
      if(error){socket.destroy();reject(error);}else resolve(socket);
    };
    const stop=()=>finish(fault(abortCode(signal)));
    signal.addEventListener('abort',stop,{once:true});
    socket.once('error',error=>finish(fault(error.code==='ECONNREFUSED'?'RETRY_CONNECT':'CONNECT_FAILED')));
    socket.once('connect',()=>finish());
  });
}

async function connectReady(port,signal) {
  for(;;){
    try{return await connectOnce(port,signal);}
    catch(error){if(error.code!=='RETRY_CONNECT')throw error;await delay(35,signal);}
  }
}

function exchange(socket,signal,transportMode) {
  return new Promise((resolve,reject)=>{
    const nonce=randomBytes(16);let received=Buffer.alloc(0),done=false;
    const transport=transportMode==='obfuscated2'?createObfuscated2Transport():null;
    const finish=(error)=>{if(done)return;done=true;signal.removeEventListener('abort',stop);socket.destroy();if(error)reject(error);else resolve();};
    const stop=()=>finish(fault(abortCode(signal)));
    if(signal.aborted){finish(fault(abortCode(signal)));return;}
    signal.addEventListener('abort',stop,{once:true});
    socket.on('data',chunk=>{
      if(transport)chunk=transport.decrypt(chunk);
      if(received.length+chunk.length>8192){finish(fault('INVALID_RESPONSE'));return;}
      received=Buffer.concat([received,chunk]);const parsed=parsePQ(received,nonce);
      if(parsed!==null)finish(parsed?null:fault('INVALID_RESPONSE'));
    });
    socket.once('error',()=>finish(fault('NETWORK_ERROR')));
    socket.once('end',()=>finish(fault('EOF')));
    socket.once('close',()=>finish(fault('EOF')));
    const request=buildReqPQ(nonce);
    // The encrypted abridged stream has its marker in the 64-byte header and
    // therefore omits the standalone 0xef byte used by plaintext abridged.
    const frame=transport?Buffer.concat([transport.header,transport.encrypt(request.subarray(1))]):request;
    socket.write(frame,error=>{if(error)finish(fault('NETWORK_ERROR'));});
  });
}

async function terminate(child,closed,budgetMs) {
  const gone=()=>closed.value||child.exitCode!=null||child.signalCode!=null;
  if(!gone()){
    try{child.kill('SIGTERM');}catch{}
    await Promise.race([closed.promise,delay(Math.min(100,Math.floor(budgetMs/2)))]);
  }
  if(!gone()){
    try{child.kill('SIGKILL');}catch{}
    await Promise.race([closed.promise,delay(Math.max(1,budgetMs-Math.min(100,Math.floor(budgetMs/2))))]);
  }
  child.stdin?.destroy();child.stdout?.destroy();child.stderr?.destroy();
  return gone();
}

async function attempt(outbound,options,signal,cleanupMs) {
  const port=await reservePort(signal);
  if(signal.aborted)throw fault(abortCode(signal));
  const config={log:{loglevel:'none'},inbounds:[{listen:'127.0.0.1',port,protocol:'dokodemo-door',settings:{address:options.destination.host,port:options.destination.port,network:'tcp'}}],outbounds:[outbound]};
  const local=new AbortController(),stop=()=>local.abort(abortCode(signal));
  signal.addEventListener('abort',stop,{once:true});
  let child,socket,portBusy=false,tail='',closedResolve,clean=true,failure;
  const closed={value:false,promise:new Promise(resolve=>{closedResolve=resolve;})};
  try{
    // spawnProcess is an injection seam for tests; production callers omit it.
    try{child=options.spawnProcess(options.binary,['run','-config','stdin:'],{stdio:['pipe','pipe','pipe'],windowsHide:true});}
    catch{throw fault('SPAWN_FAILED');}
    const drain=chunk=>{
      // Keep only a tiny rolling window for the one retryable startup condition.
      // No diagnostic text, endpoint, UUID or key can escape this function.
      const diagnostic=tail+String(chunk);
      if(/address already in use|EADDRINUSE|WSAEADDRINUSE|Only one usage of each socket address/i.test(diagnostic))portBusy=true;
      tail=diagnostic.slice(-512);
    };
    child.stdout?.on('data',drain);child.stderr?.on('data',drain);
    child.on('error',()=>{local.abort('SPAWN_FAILED');});
    child.once('close',()=>{closed.value=true;closedResolve();local.abort(portBusy?'PORT_BUSY':'CHILD_EXIT');});
    if(!child.stdin)throw fault('INPUT_FAILED');
    child.stdin.on('error',()=>local.abort('INPUT_FAILED'));
    child.stdin.end(JSON.stringify(config));
    socket=await connectReady(port,local.signal);
    await exchange(socket,local.signal,options.transportMode);
    if(local.signal.aborted)throw fault(abortCode(local.signal));
  }catch(error){
    failure=error;
  }finally{
    signal.removeEventListener('abort',stop);socket?.destroy();
    if(child)clean=await terminate(child,closed,cleanupMs);
    tail='';
    if(!clean)throw fault('CLEANUP_FAILED');
  }
  if(failure)throw fault(portBusy?'PORT_BUSY':failure.code);
}

export async function probeNativeOutbound(outbound,{binary='/usr/local/bin/xray',timeoutMs=6000,destination={host:'149.154.167.51',port:443},transportMode='obfuscated2',signal,spawnProcess=spawn}={}) {
  const start=performance.now(),result=code=>({ok:code==='OK',code,elapsed_ms:Math.round(performance.now()-start)});
  if(typeof binary!=='string'||!binary||!Number.isFinite(timeoutMs)||timeoutMs<100||timeoutMs>60000||!destination||typeof destination.host!=='string'||!isIP(destination.host)||!Number.isInteger(destination.port)||destination.port<1||destination.port>65535||!['obfuscated2','plaintext'].includes(transportMode)||typeof spawnProcess!=='function'||(signal&&typeof signal.addEventListener!=='function'))return result('INVALID_OPTIONS');
  let native;
  try{
    const raw=JSON.stringify(outbound);
    if(!raw||raw.length>131072)throw new Error();
    native=JSON.parse(raw);
    if(!native||!['vless','trojan','shadowsocks'].includes(native.protocol)||!native.settings||typeof native.settings!=='object'||native.proxySettings||native.streamSettings?.sockopt?.dialerProxy)throw new Error();
    native.tag='probe-egress';
  }catch{return result('INVALID_OUTBOUND');}
  if(signal?.aborted)return result('ABORTED');
  const controller=new AbortController(),stop=()=>controller.abort('ABORTED');
  signal?.addEventListener('abort',stop,{once:true});
  const cleanupMs=Math.min(400,Math.floor(timeoutMs/4));
  const timer=setTimeout(()=>controller.abort('TIMEOUT'),timeoutMs-cleanupMs);
  try{
    for(let tries=0;tries<3;tries++){
      try{await attempt(native,{binary,destination,spawnProcess,transportMode},controller.signal,cleanupMs);return result('OK');}
      catch(error){
        const code=PROBE_CODES.includes(error.code)?error.code:'NETWORK_ERROR';
        if(code==='PORT_BUSY'&&tries<2&&!controller.signal.aborted)continue;
        return result(code);
      }
    }
  }finally{clearTimeout(timer);signal?.removeEventListener('abort',stop);}
}

export function probeReality(config,uuid,options={}) {
  const outbound={protocol:'vless',settings:{vnext:[{address:options.gatewayHost??config?.host??'127.0.0.1',port:config?.port??18443,users:[{id:uuid,encryption:'none',flow:'xtls-rprx-vision-udp443'}]}]},streamSettings:{network:'tcp',security:'reality',realitySettings:{serverName:config?.serverName??config?.server_name,fingerprint:'chrome',publicKey:config?.publicKey??config?.public_key,shortId:config?.shortId??config?.short_id}}};
  return probeNativeOutbound(outbound,options);
}
