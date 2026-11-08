// Local-only server: REALITY decrypts actual APK traffic and forwards it to a
// minimal MTProto handshake fixture. It never authenticates a Telegram account.
import {createServer as tcpServer} from 'node:net';
import {createServer as tlsServer} from 'node:tls';
import {createServer as httpServer} from 'node:http';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {spawn,spawnSync} from 'node:child_process';
import {generateKeyPairSync,randomBytes,createCipheriv,createDecipheriv} from 'node:crypto';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
const folder=join(tmpdir(),'familygram-tgnet-diagnostic');mkdirSync(folder,{recursive:true});
const generated=spawnSync('C:/Program Files/Git/usr/bin/openssl.exe',['req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256','-nodes','-keyout',join(folder,'key.pem'),'-out',join(folder,'cert.pem'),'-days','1','-subj','/CN=localhost'],{stdio:'ignore',windowsHide:true});
if(generated.status!==0)throw Error('Test certificate failed');
const stats={connections:0,validHeaders:0,reqPQ:0,resPQ:0,reqDH:0,other:0,invalid:0};
const sockets=new Set();const own=s=>{sockets.add(s);s.on('close',()=>sockets.delete(s));s.on('error',()=>{});return s;};
const target=tlsServer({key:readFileSync(join(folder,'key.pem')),cert:readFileSync(join(folder,'cert.pem')),minVersion:'TLSv1.3',ALPNProtocols:['h2','http/1.1']},s=>own(s));
await new Promise(r=>target.listen(0,'127.0.0.1',r));
const dc=tcpServer(socket=>{
  own(socket);stats.connections++;let raw=Buffer.alloc(0),plain=Buffer.alloc(0),incoming,outgoing;
  socket.on('data',chunk=>{
    try{
      if(!incoming){raw=Buffer.concat([raw,chunk]);if(raw.length<64)return;
        const header=raw.subarray(0,64),reverse=Buffer.from(header.subarray(8,56)).reverse();
        incoming=createDecipheriv('aes-256-ctr',header.subarray(8,40),header.subarray(40,56));
        const decoded=incoming.update(header);if(decoded.readUInt32LE(56)!==0xefefefef){stats.invalid++;socket.destroy();return;}
        outgoing=createCipheriv('aes-256-ctr',reverse.subarray(0,32),reverse.subarray(32,48));stats.validHeaders++;chunk=raw.subarray(64);raw=Buffer.alloc(0);
      }
      plain=Buffer.concat([plain,incoming.update(chunk)]);
      while(plain.length){const h=(plain[0]&127)===127?4:1;if(plain.length<h)return;const length=(h===4?plain.readUIntLE(1,3):plain[0]&127)*4;if(length<24||length>4096){stats.invalid++;socket.destroy();return;}if(plain.length<h+length)return;
        const packet=plain.subarray(h,h+length);plain=plain.subarray(h+length);const ctor=packet.readUInt32LE(20);
        if(ctor===0xbe7e8ef1){stats.reqPQ++;const reply=Buffer.alloc(85);reply[0]=21;reply.writeBigUInt64LE((BigInt(Math.floor(Date.now()/1000))<<32n)|1n,9);reply.writeUInt32LE(64,17);reply.writeUInt32LE(0x05162463,21);packet.copy(reply,25,24,40);randomBytes(16).copy(reply,41);reply[57]=8;reply.writeBigUInt64BE(323n,58);reply.writeUInt32LE(0x1cb5c415,69);reply.writeUInt32LE(1,73);reply.writeBigUInt64LE(0xd09d1d85de64fd85n,77);socket.write(outgoing.update(reply));stats.resPQ++;}
        else if(ctor===0xd712e4be){stats.reqDH++;console.log('STAGE req_DH_params reached');}
        else stats.other++;
      }
    }catch{stats.invalid++;socket.destroy();}
  });
});
await new Promise(r=>dc.listen(0,'127.0.0.1',r));
const pair=generateKeyPairSync('x25519'),id='66d7ff88-566c-4dcc-aea0-95694b03c1ef',short='0123456789abcdef';
const config={log:{loglevel:'none'},inbounds:[{listen:'127.0.0.1',port:31888,protocol:'vless',settings:{clients:[{id,flow:'xtls-rprx-vision'}],decryption:'none'},streamSettings:{network:'tcp',security:'reality',realitySettings:{target:`127.0.0.1:${target.address().port}`,serverNames:['localhost'],privateKey:pair.privateKey.export({format:'jwk'}).d,shortIds:[short]}}}],outbounds:[{protocol:'freedom',settings:{redirect:`127.0.0.1:${dc.address().port}`,finalRules:[{action:'allow',ip:['127.0.0.1/32']}]}}]};
const xray=spawn(join(tmpdir(),'familygram-egress-probe/xray.exe'),['run','-config','stdin:'],{stdio:['pipe','ignore','ignore'],windowsHide:true});xray.stdin.end(JSON.stringify(config));
const profile={protocol:'vless',host:'127.0.0.1',port:31888,uuid:id,security:'reality',public_key:pair.publicKey.export({format:'jwk'}).x,short_id:short,server_name:'localhost',flow:'xtls-rprx-vision',fingerprint:'chrome'};
writeFileSync(join(folder,'test-profile.json'),JSON.stringify(profile));
const status=httpServer((req,res)=>{res.setHeader('content-type','application/json');res.end(JSON.stringify(stats));});await new Promise(r=>status.listen(31889,'127.0.0.1',r));
console.log('READY local native tgnet fixture; account-free, expiry five minutes');
function stop(){xray.kill();for(const s of sockets)s.destroy();target.close();dc.close();status.close();setTimeout(()=>process.exit(0),200);}
process.on('SIGINT',stop);process.on('SIGTERM',stop);setTimeout(stop,300000);
