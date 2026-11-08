// Read-only diagnostics. Never prints a profile, UUID, key, invitation or account data.
import {readFileSync} from 'node:fs';
import {createConnection} from 'node:net';
import {connect as tlsConnect} from 'node:tls';
import {request as httpsRequest} from 'node:https';
import {createPrivateKey,createPublicKey,randomBytes} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';

const errorCode=e=>typeof e?.code==='string'&&/^[A-Z_0-9]{2,48}$/.test(e.code)?e.code:'FAILED';
const report=(name,result)=>console.log(JSON.stringify({check:name,...result}));

export function buildReqPQ(nonce,time=Date.now()) {
  if(!Buffer.isBuffer(nonce)||nonce.length!==16)throw new Error('Invalid nonce');
  const frame=Buffer.alloc(42);frame[0]=0xef;frame[1]=10;
  const msgId=(BigInt(Math.floor(time/1000))<<32n)+(BigInt(time%1000)*4294967n&~3n);
  frame.writeBigUInt64LE(msgId,10);frame.writeUInt32LE(20,18);
  frame.writeUInt32LE(0xbe7e8ef1,22);nonce.copy(frame,26);return frame;
}
export function parsePQ(bytes,nonce) {
  if(bytes.length<1)return null;
  const header=bytes[0]===127?4:1;if(bytes.length<header)return null;
  const size=(header===4?bytes.readUIntLE(1,3):bytes[0])*4;
  if(size<40||size>4096)return false;
  if(bytes.length<header+size)return null;
  const body=bytes.subarray(header,header+size);
  return body.readBigUInt64LE(0)===0n&&body.readUInt32LE(16)===size-20
    &&body.readUInt32LE(20)===0x05162463&&body.subarray(24,40).equals(nonce);
}
function mtproto(host,family) {
  return new Promise(resolve=>{
    const start=Date.now(),nonce=randomBytes(16);let done=false,received=Buffer.alloc(0),connected=false;
    const socket=createConnection({host,port:443,family});
    const finish=result=>{if(done)return;done=true;clearTimeout(timer);socket.destroy();resolve({tcp:connected,elapsed_ms:Date.now()-start,...result});};
    const timer=setTimeout(()=>finish({mtproto:false,error:'TIMEOUT'}),6000);
    socket.once('connect',()=>{connected=true;socket.write(buildReqPQ(nonce));});
    socket.on('data',chunk=>{
      if(received.length+chunk.length>8192){finish({mtproto:false,error:'UNEXPECTED_RESPONSE'});return;}
      received=Buffer.concat([received,chunk]);const result=parsePQ(received,nonce);
      if(result!==null)finish({mtproto:result,...(!result?{error:'UNEXPECTED_RESPONSE'}:{})});
    });
    socket.once('error',e=>finish({mtproto:false,error:errorCode(e)}));
    socket.once('end',()=>finish({mtproto:false,error:'EOF'}));
  });
}
function targetTLS(host) {
  return new Promise(resolve=>{
    let done=false;const start=Date.now();
    const socket=tlsConnect({host,port:443,servername:host,minVersion:'TLSv1.3',maxVersion:'TLSv1.3',ALPNProtocols:['h2','http/1.1'],rejectUnauthorized:true});
    const finish=result=>{if(done)return;done=true;clearTimeout(timer);socket.destroy();resolve({elapsed_ms:Date.now()-start,...result});};
    const timer=setTimeout(()=>finish({ok:false,error:'TIMEOUT'}),6000);
    socket.once('secureConnect',()=>finish({ok:true,protocol:socket.getProtocol(),alpn:socket.alpnProtocol||'none'}));
    socket.once('error',e=>finish({ok:false,error:errorCode(e)}));
    socket.once('end',()=>finish({ok:false,error:'EOF'}));
  });
}
function telegramHTTPS() {
  return new Promise(resolve=>{
    let done=false;const start=Date.now();
    const req=httpsRequest({hostname:'telegram.org',port:443,path:'/',method:'HEAD',rejectUnauthorized:true,agent:false},res=>{res.resume();finish({ok:true,status:res.statusCode});});
    const finish=result=>{if(done)return;done=true;clearTimeout(timer);req.destroy();resolve({elapsed_ms:Date.now()-start,...result});};
    const timer=setTimeout(()=>finish({ok:false,error:'TIMEOUT'}),6000);
    req.once('error',e=>finish({ok:false,error:errorCode(e)}));req.end();
  });
}
function connectionCounts() {
  const counts={established:0,listen:0,other:0};
  for(const name of ['/proc/net/tcp','/proc/net/tcp6']){
    let source;try{source=readFileSync(name,'utf8');}catch{continue;}
    for(const line of source.split('\n').slice(1)){
      const p=line.trim().split(/\s+/);if(!p[1]?.endsWith(':480B'))continue;
      if(p[3]==='01')counts.established++;else if(p[3]==='0A')counts.listen++;else counts.other++;
    }
  }
  return counts;
}
export async function main() {
  console.log('Read-only FamilyGram diagnostics. Xray child stdout/stderr are discarded by the current service; historical handshake errors are unavailable.');
  console.log('Network probes use no Telegram account, phone, code or session. Direct egress probes do not prove Android-to-host REALITY connectivity.');
  let server,approvedUuid;
  try{
    server=JSON.parse(readFileSync('/data/server.json','utf8'));
    const active=JSON.parse(readFileSync('/data/xray.json','utf8'));
    const db=new DatabaseSync('/data/access.sqlite',{readOnly:true});
    let approved;try{approved=db.prepare("SELECT uuid FROM devices WHERE status='approved'").all();}finally{db.close();}
    approvedUuid=approved[0]?.uuid;
    const inbound=active.inbounds?.find(i=>i.port===18443),clients=inbound?.settings?.clients||[];
    const expected=new Set(approved.map(d=>d.uuid)),actual=new Set(clients.map(d=>d.id));
    let derived=false;
    try{const der=Buffer.concat([Buffer.from('302e020100300506032b656e04220420','hex'),Buffer.from(server.privateKey,'base64url')]);derived=createPublicKey(createPrivateKey({key:der,format:'der',type:'pkcs8'})).export({format:'jwk'}).x===server.publicKey;}catch{}
    const reality=inbound?.streamSettings?.realitySettings||{};
    report('configuration',{approved_devices:approved.length,active_clients:clients.length,client_sets_match:expected.size===actual.size&&[...expected].every(id=>actual.has(id)),key_pair_matches:derived,active_private_key_matches:reality.privateKey===server.privateKey,short_id_present:Array.isArray(reality.shortIds)&&reality.shortIds.includes(server.shortId),target_matches:reality.target===`${server.serverName}:443`||reality.dest===`${server.serverName}:443`,server_name_present:Array.isArray(reality.serverNames)&&reality.serverNames.includes(server.serverName),vless_tcp_reality:inbound?.protocol==='vless'&&inbound?.streamSettings?.network==='tcp'&&inbound?.streamSettings?.security==='reality',all_clients_vision:clients.every(c=>c.flow==='xtls-rprx-vision'),outbound_protocols:(active.outbounds||[]).map(o=>['freedom','blackhole','socks','vless','vmess','trojan','shadowsocks'].includes(o.protocol)?o.protocol:'other')});
    report('gateway_connections',connectionCounts());
  }catch(e){report('configuration',{ok:false,error:errorCode(e)});}
  const probes=[['DC1_IPv4_443',()=>mtproto('149.154.175.50',4)],['DC2_IPv4_443',()=>mtproto('149.154.167.51',4)],['DC4_IPv4_443',()=>mtproto('149.154.167.91',4)],['DC2_IPv6_443',()=>mtproto('2001:67c:4e8:f002::a',6)],['telegram_org_HTTPS',telegramHTTPS]];
  if(typeof server?.serverName==='string'&&server.serverName.length<254&&/^[a-zA-Z0-9.-]+$/.test(server.serverName))probes.push(['REALITY_target_TLS13',()=>targetTLS(server.serverName)]);
  else report('REALITY_target_TLS13',{ok:false,error:'INVALID_TARGET'});
  await Promise.all(probes.map(async([name,fn])=>{try{report(name,await fn());}catch(e){report(name,{ok:false,error:errorCode(e)});}}));
  if(server&&approvedUuid){
    try{
      const {probeReality}=await import('/app/egress-probe.mjs');
      report('REALITY_selected_VPN_MTPROTO',await probeReality(server,approvedUuid,{gatewayHost:'127.0.0.1',timeoutMs:20000}));
    }catch{report('REALITY_selected_VPN_MTPROTO',{ok:false,error:'PROBE_NOT_AVAILABLE'});}
  }
  console.log('A failed plaintext MTProto probe can reflect transport filtering; a successful nonce-matched resPQ proves that direct DC path answered MTProto.');
  console.log('Direct egress failures can remain expected after VPN repair; the REALITY_selected_VPN_MTPROTO result tests the selected server route.');
}
if(process.argv.includes('--familygram-run'))await main();
