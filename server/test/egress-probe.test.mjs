import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {createServer,createConnection} from 'node:net';
import {spawn} from 'node:child_process';
import {randomBytes,createCipheriv,createDecipheriv} from 'node:crypto';
import {buildReqPQ,parsePQ,createObfuscated2Transport,probeNativeOutbound,probeReality,PROBE_CODES} from '../egress-probe.mjs';

const outbound={protocol:'vless',settings:{vnext:[{address:'example.invalid',port:443,users:[{id:'private-profile-marker',encryption:'none'}]}]},streamSettings:{network:'tcp',security:'reality',realitySettings:{publicKey:'private-key-marker'}}};
function resPQ(nonce,{longHeader=false}={}) {
  const body=Buffer.alloc(84);body.writeBigUInt64LE(1n,8);body.writeUInt32LE(64,16);
  body.writeUInt32LE(0x05162463,20);nonce.copy(body,24);randomBytes(16).copy(body,40);
  body[56]=8;randomBytes(8).copy(body,57);body.writeUInt32LE(0x1cb5c415,68);
  body.writeUInt32LE(1,72);body.writeBigUInt64LE(123n,76);
  return Buffer.concat([longHeader?Buffer.from([127,21,0,0]):Buffer.from([21]),body]);
}

// Independent server-side decoding: derive keys from the received plaintext
// prefix, consume the incoming CTR header, and begin outgoing CTR at zero.
function decodeRequest(wire) {
  if(!wire.length)return null;
  if(wire[0]===0xef)return wire.length<42?null:{request:wire.subarray(0,42),reply:bytes=>bytes};
  if(wire.length<105)return null;
  const prefix=wire.subarray(0,64),reverse=Buffer.from(prefix.subarray(8,56)).reverse();
  const incoming=createDecipheriv('aes-256-ctr',prefix.subarray(8,40),prefix.subarray(40,56));
  const header=incoming.update(prefix);
  assert.equal(header.readUInt32LE(56),0xefefefef);assert.equal(header.readInt16LE(60),2);
  const request=Buffer.concat([Buffer.from([0xef]),incoming.update(wire.subarray(64,105))]);
  const outgoing=createCipheriv('aes-256-ctr',reverse.subarray(0,32),reverse.subarray(32,48));
  return {request,reply:bytes=>outgoing.update(bytes)};
}

// Real loopback sockets, simulated Xray process. The child reads only stdin;
// it does not run a shell or receive secrets through argv/environment/files.
function fakeXray(modes=['ok']) {
  const calls=[];
  const spawnProcess=(binary,args,options)=>{
    const mode=modes[Math.min(calls.length,modes.length-1)];
    const child=new EventEmitter();child.stdin=new PassThrough();child.stdout=new PassThrough();child.stderr=new PassThrough();child.exitCode=null;child.signalCode=null;
    const call={binary,args,options,signals:[],config:null,child,sockets:new Set(),server:null};calls.push(call);
    let text='',ended=false;
    const close=(code=0,signal=null)=>{
      if(ended)return;ended=true;child.exitCode=code;child.signalCode=signal;
      for(const s of call.sockets)s.destroy();
      const emit=()=>child.emit('close',code,signal);
      if(call.server?.listening)call.server.close(emit);else queueMicrotask(emit);
    };
    child.kill=signal=>{call.signals.push(signal);if(mode!=='stubborn'||signal==='SIGKILL')close(null,signal);return true;};
    child.stdin.on('data',chunk=>{text+=chunk;});
    child.stdin.on('finish',()=>{
      if(ended)return;
      call.config=JSON.parse(text);
      if(mode==='spawnError'){child.emit('error',new Error('private-profile-marker'));close(1);return;}
      if(mode==='stdinError'){child.stdin.emit('error',new Error('private-key-marker'));return;}
      if(mode==='exit'){child.stderr.write('config contains private-profile-marker');close(1);return;}
      if(mode==='busy'){child.stderr.write('private-key-marker address already in use');close(1);return;}
      if(mode==='notListening'||mode==='stubborn')return;
      const server=createServer(socket=>{
        call.sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>call.sockets.delete(socket));
        let received=Buffer.alloc(0);
        socket.on('data',chunk=>{
          received=Buffer.concat([received,chunk]);const decoded=decodeRequest(received);if(!decoded)return;
          assert.equal(decoded.request[0],0xef);assert.equal(decoded.request.readUInt32LE(22),0xbe7e8ef1);
          const nonce=decoded.request.subarray(26,42);
          if(mode==='silent')return;
          if(mode==='eof'){socket.end();return;}
          if(mode==='childExit'){close(1);return;}
          if(mode==='oversize'){socket.write(Buffer.alloc(9000));return;}
          const reply=decoded.reply(resPQ(mode==='badNonce'?randomBytes(16):nonce));
          if(mode==='fragmented'){
            socket.write(reply.subarray(0,3));setTimeout(()=>{if(!socket.destroyed)socket.write(reply.subarray(3));},10);
          }else socket.write(reply);
        });
      });
      call.server=server;server.on('error',error=>{child.stderr.write(error.code??'');close(1);});
      server.listen({host:'127.0.0.1',port:call.config.inbounds[0].port});
    });
    return child;
  };
  return {spawnProcess,calls};
}

test('MTProto request has a real unencrypted req_pq_multi envelope and bounded nonce-matched parser',()=>{
  const nonce=randomBytes(16),request=buildReqPQ(nonce,1700000000123);
  assert.equal(request.length,42);assert.equal(request[0],0xef);assert.equal(request[1],10);
  assert.equal(request.readBigUInt64LE(2),0n);assert.equal(request.readBigUInt64LE(10)&3n,0n);
  assert.equal(request.readUInt32LE(18),20);assert.equal(request.readUInt32LE(22),0xbe7e8ef1);assert.deepEqual(request.subarray(26),nonce);
  for(const longHeader of [false,true]){
    const reply=resPQ(nonce,{longHeader});
    for(let i=0;i<reply.length;i++)assert.equal(parsePQ(reply.subarray(0,i),nonce),null);
    assert.equal(parsePQ(reply,nonce),true);assert.equal(parsePQ(reply,randomBytes(16)),false);
    const corrupt=Buffer.from(reply);corrupt[longHeader?24:21]=0;assert.equal(parsePQ(corrupt,nonce),false);
  }
  assert.equal(parsePQ(Buffer.from([255]),nonce),false);
  assert.equal(parsePQ(Buffer.from([127,255,255,255]),nonce),false);
  assert.equal(parsePQ(Buffer.from([0]),nonce),false);
  assert.throws(()=>buildReqPQ(Buffer.alloc(15)),/INVALID_OPTIONS/);
});

test('Obfuscated2 matches pinned secretless header/key direction and CTR positions',()=>{
  const seed=Buffer.from(Array.from({length:64},(_,i)=>i+1));
  const transport=createObfuscated2Transport(()=>seed),nonce=Buffer.alloc(16,0x42);
  const request=buildReqPQ(nonce,1700000000123);
  const plain=Buffer.from(seed);plain.fill(0xef,56,60);plain.writeInt16LE(2,60);
  const cipher=createCipheriv('aes-256-ctr',seed.subarray(8,40),seed.subarray(40,56));
  const expectedHeader=cipher.update(plain);const expectedWire=Buffer.concat([plain.subarray(0,56),expectedHeader.subarray(56),cipher.update(request.subarray(1))]);
  const wire=Buffer.concat([transport.header,transport.encrypt(request.subarray(1))]);
  assert.deepEqual(wire,expectedWire);assert.deepEqual(seed,Buffer.from(Array.from({length:64},(_,i)=>i+1)));
  // Independently reproduced using Python cryptography AES/CTR and struct.pack
  // against the pinned C++ byte/key offsets, not generated by this helper.
  assert.equal(wire.toString('hex'),'0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f30313233343536373832bc9585f1fb4e3ad7004d2a9d4aeedc3c090286745688a9468a483f55619b6a743f15a4e8320c1415466c18e67a9c122e');
  const server=decodeRequest(wire);assert.deepEqual(server.request.subarray(26),nonce);
  const reply=server.reply(resPQ(nonce));
  const decoded=Buffer.concat([transport.decrypt(reply.subarray(0,7)),transport.decrypt(reply.subarray(7))]);
  assert.equal(parsePQ(decoded,nonce),true);
  let tries=0;const retried=createObfuscated2Transport(()=>{tries++;return tries===1?Buffer.alloc(64,0xef):seed;});
  assert.equal(tries,2);assert.equal(retried.header.length,64);
});

test('plaintext abridged remains explicitly available for diagnostics',async()=>{
  const mock=fakeXray();assert.equal((await probeNativeOutbound(outbound,{...mock,transportMode:'plaintext',timeoutMs:1000})).code,'OK');
  assert.equal((await probeNativeOutbound(outbound,{...mock,transportMode:'unknown'})).code,'INVALID_OPTIONS');
});

test('probe validates encrypted outbound and options before spawning',async()=>{
  const mock=fakeXray();
  for(const protocol of ['freedom','socks','http','blackhole'])assert.equal((await probeNativeOutbound({protocol,settings:{}},mock)).code,'INVALID_OUTBOUND');
  assert.equal((await probeNativeOutbound({...outbound,proxySettings:{tag:'other'}},mock)).code,'INVALID_OUTBOUND');
  assert.equal((await probeNativeOutbound(outbound,{...mock,destination:{}})).code,'INVALID_OPTIONS');
  assert.equal((await probeNativeOutbound(outbound,{...mock,timeoutMs:1})).code,'INVALID_OPTIONS');
  assert.equal(mock.calls.length,0);
});

test('actual TCP fragmented resPQ succeeds; config stays on stdin and child is terminated',async()=>{
  const mock=fakeXray(['fragmented']),original=JSON.stringify(outbound);
  const result=await probeNativeOutbound(outbound,{...mock,timeoutMs:1000,binary:'trusted-xray'});
  assert.deepEqual(Object.keys(result).sort(),['code','elapsed_ms','ok']);assert.equal(result.code,'OK');assert.equal(result.ok,true);
  const call=mock.calls[0];assert.deepEqual(call.args,['run','-config','stdin:']);assert.deepEqual(call.options.stdio,['pipe','pipe','pipe']);assert.equal(call.options.windowsHide,true);
  assert.equal(call.config.inbounds[0].listen,'127.0.0.1');assert.equal(call.config.inbounds[0].protocol,'dokodemo-door');
  assert.deepEqual(call.config.inbounds[0].settings,{address:'149.154.167.51',port:443,network:'tcp'});
  assert.equal(call.config.outbounds.length,1);assert.equal(call.config.outbounds[0].tag,'probe-egress');
  assert.equal(JSON.stringify(outbound),original);assert.deepEqual(call.signals,['SIGTERM']);assert.ok([...call.sockets].every(socket=>socket.destroyed));assert.equal(call.server.listening,false);
  assert.equal(JSON.stringify(result).includes('private-'),false);
});

test('port reservation race retries on fresh port without a persistent listener',async()=>{
  const mock=fakeXray(['busy','ok']);const result=await probeNativeOutbound(outbound,{...mock,timeoutMs:1000});
  assert.equal(result.code,'OK');assert.equal(mock.calls.length,2);
  assert.notEqual(mock.calls[0].config.inbounds[0].port,mock.calls[1].config.inbounds[0].port);
  const busy=fakeXray(['busy']);assert.equal((await probeNativeOutbound(outbound,{...busy,timeoutMs:1000})).code,'PORT_BUSY');assert.equal(busy.calls.length,3);
});

test('four concurrent candidates keep independent listeners, responses and cleanup',async()=>{
  const mocks=Array.from({length:4},()=>fakeXray(['fragmented']));
  const results=await Promise.all(mocks.map(mock=>probeNativeOutbound(outbound,{...mock,timeoutMs:1000})));
  assert.ok(results.every(result=>result.ok));
  assert.equal(new Set(mocks.map(mock=>mock.calls[0].config.inbounds[0].port)).size,4);
  for(const mock of mocks){assert.equal(mock.calls[0].server.listening,false);assert.deepEqual(mock.calls[0].signals,['SIGTERM']);}
});

test('bad nonce, oversized responses, EOF and child errors never report success or expose output',async t=>{
  for(const [mode,expected] of [['badNonce','INVALID_RESPONSE'],['oversize','INVALID_RESPONSE'],['eof','EOF'],['spawnError','SPAWN_FAILED'],['stdinError','INPUT_FAILED'],['exit','CHILD_EXIT'],['childExit','CHILD_EXIT']]){
    await t.test(mode,async()=>{
      const mock=fakeXray([mode]),result=await probeNativeOutbound(outbound,{...mock,timeoutMs:1000});
      assert.equal(result.ok,false);assert.equal(result.code,expected);assert.equal(JSON.stringify(result).includes('private-'),false);
      assert.ok(mock.calls[0].child.exitCode!==null||mock.calls[0].child.signalCode!==null);
    });
  }
});

test('silent connection and missing listener expire; SIGTERM-resistant child receives SIGKILL',async t=>{
  for(const mode of ['silent','notListening','stubborn'])await t.test(mode,async()=>{
    const mock=fakeXray([mode]);const result=await probeNativeOutbound(outbound,{...mock,timeoutMs:250});
    assert.equal(result.code,'TIMEOUT');assert.ok(result.elapsed_ms<1000);
    assert.deepEqual(mock.calls[0].signals,mode==='stubborn'?['SIGTERM','SIGKILL']:['SIGTERM']);
  });
});

test('AbortSignal works before spawn and while waiting, with process cleanup',async()=>{
  const aborted=new AbortController();aborted.abort();const before=fakeXray();
  assert.equal((await probeNativeOutbound(outbound,{...before,signal:aborted.signal})).code,'ABORTED');assert.equal(before.calls.length,0);
  const controller=new AbortController(),mock=fakeXray(['silent']);const timer=setTimeout(()=>controller.abort(),70);
  try{assert.equal((await probeNativeOutbound(outbound,{...mock,signal:controller.signal,timeoutMs:1000})).code,'ABORTED');}
  finally{clearTimeout(timer);}
  assert.deepEqual(mock.calls[0].signals,['SIGTERM']);
});

test('real missing executable is classified and never leaves a child',async()=>{
  const result=await probeNativeOutbound(outbound,{binary:'/familygram-nonexistent-xray-for-test',timeoutMs:1000});
  assert.equal(result.code,'SPAWN_FAILED');assert.equal(result.ok,false);assert.ok(PROBE_CODES.includes(result.code));
});

test('REALITY gateway probe preserves secrets only inside child stdin',async()=>{
  const mock=fakeXray();const result=await probeReality({host:'public.example',port:18443,serverName:'www.microsoft.com',publicKey:'private-key-marker',shortId:'0123'},'private-profile-marker',{...mock,gatewayHost:'127.0.0.1',timeoutMs:1000});
  assert.equal(result.code,'OK');const out=mock.calls[0].config.outbounds[0];
  assert.equal(out.settings.vnext[0].address,'127.0.0.1');assert.equal(out.streamSettings.security,'reality');
  assert.equal(out.settings.vnext[0].users[0].id,'private-profile-marker');assert.equal(JSON.stringify(result).includes('private-'),false);
});

test('actual Xray stdin client carries nonce-matched MTProto through encrypted Shadowsocks', {skip:!process.env.XRAY_TEST_BINARY,timeout:12000},async t=>{
  const sockets=new Set();
  const endpoint=createServer(socket=>{
    sockets.add(socket);socket.on('close',()=>sockets.delete(socket));socket.on('error',()=>{});
    let bytes=Buffer.alloc(0);
    socket.on('data',chunk=>{bytes=Buffer.concat([bytes,chunk]);const decoded=decodeRequest(bytes);if(decoded)socket.end(decoded.reply(resPQ(decoded.request.subarray(26,42))));});
  });
  await new Promise((resolve,reject)=>{endpoint.once('error',reject);endpoint.listen(0,'127.0.0.1',resolve);});
  t.after(async()=>{for(const socket of sockets)socket.destroy();await new Promise(resolve=>endpoint.close(resolve));});
  const reservation=createServer();
  await new Promise((resolve,reject)=>{reservation.once('error',reject);reservation.listen(0,'127.0.0.1',resolve);});
  const port=reservation.address().port;await new Promise(resolve=>reservation.close(resolve));
  const password=randomBytes(32).toString('base64');
  const config={log:{loglevel:'none'},inbounds:[{listen:'127.0.0.1',port,protocol:'shadowsocks',settings:{method:'aes-128-gcm',password,network:'tcp'}}],outbounds:[{protocol:'freedom',settings:{finalRules:[{action:'allow',ip:['127.0.0.1/32']}]}}]};
  const child=spawn(process.env.XRAY_TEST_BINARY,['run','-config','stdin:'],{stdio:['pipe','ignore','ignore'],windowsHide:true});
  let exited=false,failed=false;const closed=new Promise(resolve=>child.once('close',()=>{exited=true;resolve();}));
  child.on('error',()=>{failed=true;});child.stdin.on('error',()=>{failed=true;});
  t.after(async()=>{
    if(!exited){child.kill('SIGTERM');await Promise.race([closed,new Promise(resolve=>setTimeout(resolve,200))]);}
    if(!exited){child.kill('SIGKILL');await closed;}
  });
  child.stdin.end(JSON.stringify(config));
  let listening=false;
  for(let i=0;i<60&&!failed&&!exited;i++){
    listening=await new Promise(resolve=>{const socket=createConnection({host:'127.0.0.1',port});socket.once('connect',()=>{socket.destroy();resolve(true);});socket.once('error',()=>resolve(false));});
    if(listening)break;await new Promise(resolve=>setTimeout(resolve,30));
  }
  assert.equal(listening,true,'Local encrypted Xray fixture must start from stdin');
  const native={protocol:'shadowsocks',settings:{servers:[{address:'127.0.0.1',port,method:'aes-128-gcm',password}]}};
  const options={binary:process.env.XRAY_TEST_BINARY,timeoutMs:3500,destination:{host:'127.0.0.1',port:endpoint.address().port}};
  const approved=await probeNativeOutbound(native,options);assert.equal(approved.code,'OK',`Encrypted MTProto probe: ${approved.code}`);
  const denied=structuredClone(native);denied.settings.servers[0].password=randomBytes(32).toString('base64');
  const rejected=await probeNativeOutbound(denied,{...options,timeoutMs:1000});assert.equal(rejected.ok,false,'Wrong encryption key must not reach MTProto fixture');
});
