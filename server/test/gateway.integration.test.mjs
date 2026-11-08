import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { Gateway } from '../gateway.mjs';
import { createConnection } from 'node:net';

test('real Xray starts valid managed configuration and closes old sessions on revoke reload', {skip:!process.env.XRAY_TEST_BINARY,timeout:15000}, async t=>{
  const dir=mkdtempSync(join(tmpdir(),'familygram-xray-lifecycle-')),k=generateKeyPairSync('x25519');
  const gateway=new Gateway({host:'127.0.0.1',serverName:'www.microsoft.com',privateKey:k.privateKey.export({format:'jwk'}).d,publicKey:k.publicKey.export({format:'jwk'}).x,shortId:randomBytes(8).toString('hex')},dir,process.env.XRAY_TEST_BINARY,{allowDirectForTests:true});
  t.after(async()=>{await gateway.stop();rmSync(dir,{recursive:true,force:true});});
  await gateway.reload([{id:'installation',uuid:randomUUID()}]);assert.equal(gateway.ready,true);
  const socket=createConnection({host:'127.0.0.1',port:18443});socket.on('error',()=>{});
  await new Promise(r=>socket.once('connect',r));
  const closed=new Promise(r=>socket.once('close',r));
  const wsSocket=createConnection({host:'127.0.0.1',port:10000});wsSocket.on('error',()=>{});await new Promise(r=>wsSocket.once('connect',r));
  const wsClosed=new Promise(r=>wsSocket.once('close',r));
  await gateway.reload([]);await Promise.all([closed,wsClosed]);assert.equal(socket.destroyed,true);assert.equal(wsSocket.destroyed,true);assert.equal(gateway.ready,true);
  gateway.binary=join(dir,'missing-xray-binary');
  await assert.rejects(gateway.reload([]));assert.equal(gateway.ready,false);
  const accepting=await new Promise(resolve=>{const s=createConnection({host:'127.0.0.1',port:18443});s.once('connect',()=>{s.destroy();resolve(true);});s.once('error',()=>resolve(false));});
  assert.equal(accepting,false,'A failed reload must leave gateway port closed');
});

test('real Xray REALITY connection succeeds for approved UUID and fails after revocation', {skip:!process.env.XRAY_TEST_BINARY, timeout:90000}, async t=>{
  const binary=process.env.XRAY_TEST_BINARY,dir=mkdtempSync(join(tmpdir(),'familygram-xray-'));
  const k=generateKeyPairSync('x25519'),config={host:'127.0.0.1',serverName:'www.microsoft.com',privateKey:k.privateKey.export({format:'jwk'}).d,publicKey:k.publicKey.export({format:'jwk'}).x,shortId:randomBytes(8).toString('hex')};
  const gateway=new Gateway(config,dir,binary,{allowDirectForTests:true}),uuid=randomUUID();
  let client;
  t.after(async()=>{if(client&&client.exitCode===null){const done=new Promise(r=>client.once('exit',r));client.kill();await done;}await gateway.stop();rmSync(dir,{recursive:true,force:true});});
  await gateway.reload([{id:'test-install',uuid}]);assert.equal(gateway.ready,true);
  const clientPath=join(dir,'client.json');
  writeFileSync(clientPath,JSON.stringify({log:{loglevel:'none'},inbounds:[{listen:'127.0.0.1',port:19080,protocol:'socks',settings:{auth:'noauth',udp:false}}],outbounds:[{protocol:'vless',settings:{vnext:[{address:'127.0.0.1',port:18443,users:[{id:uuid,encryption:'none',flow:'xtls-rprx-vision'}]}]},streamSettings:{network:'tcp',security:'reality',realitySettings:{serverName:config.serverName,fingerprint:'chrome',password:config.publicKey,shortId:config.shortId}}}]}),{mode:0o600});
  client=spawn(binary,['run','-config',clientPath],{stdio:'ignore'});await delay(500);
  const curl=process.platform==='win32'?'curl.exe':'curl';
  const request=()=>spawnSync(curl,['--silent','--fail','--max-time','15','--socks5-hostname','127.0.0.1:19080','https://example.com'],{timeout:18000,encoding:'utf8'});
  const allowed=request();assert.equal(allowed.status,0,'Approved UUID must carry real HTTPS through managed REALITY gateway');assert.match(allowed.stdout,/Example Domain/);
  await gateway.reload([]);assert.equal(gateway.ready,true);
  const revoked=request();assert.notEqual(revoked.status,0,'Revoked UUID must no longer carry HTTPS');
});
