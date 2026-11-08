// Owner-authorized read-only export: only this installation's public REALITY
// parameters and one approved device UUID cross SSH, encrypted to an ephemeral PC key.
import {spawn} from 'node:child_process';
import {generateKeyPairSync,createHash,privateDecrypt,createDecipheriv,constants,randomBytes} from 'node:crypto';
import {readFileSync,lstatSync,writeFileSync,renameSync,unlinkSync} from 'node:fs';
import {resolve,dirname,join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {probeReality,PROBE_CODES} from '../server/egress-probe.mjs';

export const XRAY_SHA256='d5b558191cccb1e92a5772b68b8cab67717b26f634b0f15153215f65ae982a7e';
const fail=code=>{throw Object.assign(new Error(code),{code});};
const known=new Set(['BINARY_VERIFICATION_FAILED','PRIVATE_DIRECTORY_INVALID','SSH_FAILED','SSH_TIMEOUT','ENCRYPTED_EXPORT_INVALID','PROFILE_INVALID','PRIVATE_WRITE_FAILED','LOCAL_FAILURE']);
export function validateProfile(p){
  const keys=['host','port','uuid','publicKey','shortId','serverName'];
  if(!p||Object.keys(p).sort().join(',')!==keys.sort().join(',')||p.host!=='24projects.ru'||p.port!==18443||!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(p.uuid||'')||!/^[A-Za-z0-9_-]{43}$/.test(p.publicKey||'')||!/^(?:[a-f0-9]{2}){1,8}$/i.test(p.shortId||'')||typeof p.serverName!=='string'||p.serverName.length>253||!p.serverName.includes('.')||!p.serverName.split('.').every(v=>/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(v)))fail('PROFILE_INVALID');
  return p;
}
export function decryptExport(envelope,privateKey){
  let key,plain;
  try{
    if(!envelope||envelope.version!==1)fail('ENCRYPTED_EXPORT_INVALID');
    const decode=(name,size)=>{if(typeof envelope[name]!=='string'||envelope[name].length>8192||! /^[A-Za-z0-9+/]+={0,2}$/.test(envelope[name]))throw Error();const b=Buffer.from(envelope[name],'base64');if(size&&b.length!==size)throw Error();return b;};
    key=privateDecrypt({key:privateKey,oaepHash:'sha256',padding:constants.RSA_PKCS1_OAEP_PADDING},decode('wrappedKey',384));
    if(key.length!==32)throw Error();
    const decipher=createDecipheriv('aes-256-gcm',key,decode('iv',12));
    decipher.setAAD(Buffer.from('FamilyGram outside profile v1'));
    decipher.setAuthTag(decode('tag',16));
    plain=Buffer.concat([decipher.update(decode('ciphertext')),decipher.final()]);
    return validateProfile(JSON.parse(plain.toString('utf8')));
  }catch{fail('ENCRYPTED_EXPORT_INVALID');}
  finally{key?.fill(0);plain?.fill(0);}
}
export function remoteProgram(publicKey){
  const pem=publicKey.export({type:'spki',format:'pem'});
  const remote=`import {readFileSync} from 'node:fs';
import {DatabaseSync} from 'node:sqlite';
import {randomBytes,createCipheriv,publicEncrypt,constants} from 'node:crypto';
try {
 const db=new DatabaseSync('/data/access.sqlite',{readOnly:true});
 let device;try{device=db.prepare("SELECT uuid FROM devices WHERE status='approved' AND uuid IS NOT NULL LIMIT 1").get();}finally{db.close();}
 if(!device)throw Error();
 const c=JSON.parse(readFileSync('/data/server.json','utf8'));
 const p={host:c.host,port:18443,uuid:device.uuid,publicKey:c.publicKey,shortId:c.shortId,serverName:c.serverName};
 const key=randomBytes(32),iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);
 cipher.setAAD(Buffer.from('FamilyGram outside profile v1'));
 const ciphertext=Buffer.concat([cipher.update(JSON.stringify(p),'utf8'),cipher.final()]);
 const wrappedKey=publicEncrypt({key:${JSON.stringify(pem)},oaepHash:'sha256',padding:constants.RSA_PKCS1_OAEP_PADDING},key);key.fill(0);
 process.stdout.write(JSON.stringify({version:1,wrappedKey:wrappedKey.toString('base64'),iv:iv.toString('base64'),tag:cipher.getAuthTag().toString('base64'),ciphertext:ciphertext.toString('base64')}));
}catch{process.exitCode=1;}`;
  const encoded=Buffer.from(remote).toString('base64');
  return `import base64,os,pathlib,re,stat,subprocess,sys
try:
 if os.geteuid()!=0: raise RuntimeError()
 root=pathlib.Path('/opt/familygram')
 if root.is_symlink() or root.stat().st_uid!=0 or (root/'.familygram-owned').read_text().strip()!='familygram-v1': raise RuntimeError()
 current=(root/'current').resolve(strict=True)
 if not current.is_relative_to(root/'releases') or current==root/'releases' or current.stat().st_uid!=0: raise RuntimeError()
 compose=['docker','compose','--project-directory',str(current/'deploy'),'--env-file',str(current/'deploy/.env'),'-f',str(current/'deploy/compose.yaml')]
 def run(args,data=None):
  return subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=20)
 result=run(compose+['ps','-q','access'])
 container=result.stdout.decode().strip()
 if result.returncode or not re.fullmatch('[a-f0-9]{12,64}',container): raise RuntimeError()
 result=run(['docker','inspect','-f','{{index .Config.Labels "com.docker.compose.project"}} {{index .Config.Labels "com.docker.compose.service"}}',container])
 if result.returncode or result.stdout.strip()!=b'familygram access': raise RuntimeError()
 result=run(['docker','exec','-i',container,'node','--input-type=module','-'],base64.b64decode('${encoded}'))
 if result.returncode or len(result.stdout)>16384: raise RuntimeError()
 sys.stdout.buffer.write(result.stdout)
except BaseException:
 sys.stderr.write('FamilyGram encrypted export failed.\\n')
 sys.exit(1)
`;
}
async function encryptedExport(publicKey){
  return new Promise((accept,reject)=>{
    const child=spawn('ssh',['-T','-o','ConnectTimeout=15','-o','NumberOfPasswordPrompts=1','root@157.228.191.231','python3 -'],{stdio:['pipe','pipe','inherit'],windowsHide:true});
    let output='',settled=false;
    const finish=(code,value)=>{if(settled)return;settled=true;clearTimeout(timer);if(code){child.kill();reject(Object.assign(new Error(code),{code}));}else accept(value);};
    const timer=setTimeout(()=>finish('SSH_TIMEOUT'),180000);
    child.on('error',()=>finish('SSH_FAILED'));
    child.stdout.on('data',chunk=>{output+=chunk.toString('utf8');if(Buffer.byteLength(output)>16384)finish('ENCRYPTED_EXPORT_INVALID');});
    child.stdin.on('error',()=>finish('SSH_FAILED'));
    child.once('close',code=>{if(code!==0)return finish('SSH_FAILED');try{finish(null,JSON.parse(output));}catch{finish('ENCRYPTED_EXPORT_INVALID');}output='';});
    child.stdin.end(remoteProgram(publicKey));
  });
}
function savePrivate(directory,profile){
  let temp;
  try{
    if(!directory||!lstatSync(directory).isDirectory()||lstatSync(directory).isSymbolicLink())fail('PRIVATE_DIRECTORY_INVALID');
    const destination=join(directory,'private-profile.json');
    temp=join(directory,`.profile-${randomBytes(12).toString('hex')}.tmp`);
    writeFileSync(temp,JSON.stringify(profile),{mode:0o600,flag:'wx'});renameSync(temp,destination);temp=null;
    const metadata={version:1,createdAt:new Date().toISOString(),expiresAt:new Date(Date.now()+15*60000).toISOString(),purpose:'Owner-authorized external network and Android QA; delete after testing. Expiry is local metadata, not credential revocation.'};
    temp=join(directory,`.metadata-${randomBytes(12).toString('hex')}.tmp`);
    writeFileSync(temp,JSON.stringify(metadata),{mode:0o600,flag:'wx'});renameSync(temp,join(directory,'metadata.json'));temp=null;
    return destination;
  }catch(error){if(temp){try{unlinkSync(temp);}catch{}}fail(error.code==='PRIVATE_DIRECTORY_INVALID'?error.code:'PRIVATE_WRITE_FAILED');}
}
export async function main(args=process.argv.slice(2)){
  const base=dirname(fileURLToPath(import.meta.url)),binary=resolve(base,'../runtime/xray-windows.exe');
  try{
    if(createHash('sha256').update(readFileSync(binary)).digest('hex')!==XRAY_SHA256)fail('BINARY_VERIFICATION_FAILED');
    if(args[0]==='--plan'){console.log('OUTSIDE_DIAGNOSTIC_PLAN_OK: verified Xray; no SSH, profile export or probes.');return;}
    if(args.length!==1||args[0]!=='--run'||process.platform!=='win32')fail('LOCAL_FAILURE');
    const directory=process.env.FAMILYGRAM_OUTSIDE_PRIVATE_DIR;
    if(!directory||!lstatSync(directory).isDirectory()||lstatSync(directory).isSymbolicLink())fail('PRIVATE_DIRECTORY_INVALID');
    console.log('One SSH password authorizes encrypted device-profile retrieval. No server private key or Telegram account data is exported.');
    const {publicKey,privateKey}=generateKeyPairSync('rsa',{modulusLength:3072});
    const profile=decryptExport(await encryptedExport(publicKey),privateKey);
    const location=savePrivate(directory,profile);
    for(const [check,gatewayHost] of [['PUBLIC_DOMAIN_REALITY_MTPROTO','24projects.ru'],['PUBLIC_IPV4_REALITY_MTPROTO','157.228.191.231']]){
      const result=await probeReality(profile,profile.uuid,{binary,gatewayHost,timeoutMs:8000});
      console.log(JSON.stringify({check,ok:result.ok===true,code:PROBE_CODES.includes(result.code)?result.code:'NETWORK_ERROR',elapsed_ms:result.elapsed_ms}));
    }
    console.log(`Private QA profile saved: ${location}`);
    console.log('Local QA metadata expires in 15 minutes; the device credential itself remains valid until owner revocation. Delete the private files after QA.');
  }catch(error){console.log(JSON.stringify({check:'OUTSIDE_DIAGNOSTIC',ok:false,code:known.has(error.code)?error.code:'LOCAL_FAILURE'}));process.exitCode=1;}
}
if(process.argv[1]&&pathToFileURL(resolve(process.argv[1])).href===import.meta.url)await main();
