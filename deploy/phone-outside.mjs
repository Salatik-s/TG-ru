// The authorized phone signs its normal config API request in Android Keystore.
// Only an encrypted gateway profile enters adb stdout; raw adb output is never printed.
import {spawn} from 'node:child_process';
import {generateKeyPairSync,createHash,randomBytes} from 'node:crypto';
import {readFileSync,lstatSync,writeFileSync,renameSync,unlinkSync} from 'node:fs';
import {resolve,dirname,join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {XRAY_SHA256,decryptExport} from './outside-diagnostics.mjs';
import {probeReality,PROBE_CODES} from '../server/egress-probe.mjs';

const SERIAL='320245603330';
const DEFAULT_COMPONENT='com.salatik.familygram.profilediag/com.salatik.familygram.profilediag.ProfileDiagnostic';
const codes=new Set(['INVALID_OPTIONS','BINARY_VERIFICATION_FAILED','ADB_UNAVAILABLE','ADB_FAILED','ADB_TIMEOUT','INSTRUMENTATION_FAILED','ENCRYPTED_EXPORT_INVALID','PRIVATE_DIRECTORY_INVALID','PRIVATE_WRITE_FAILED','LOCAL_FAILURE']);
const fail=code=>{throw Object.assign(new Error(code),{code});};
export function parseInstrumentation(output){
  if(typeof output!=='string'||Buffer.byteLength(output)>65536||!/\bINSTRUMENTATION_CODE:\s*-1\s*(?:\r?\n|$)/.test(output))fail('INSTRUMENTATION_FAILED');
  const lines=output.split(/\r?\n/).filter(line=>line.startsWith('INSTRUMENTATION_RESULT: envelope='));
  if(lines.length!==1)fail('ENCRYPTED_EXPORT_INVALID');
  const value=lines[0].slice('INSTRUMENTATION_RESULT: envelope='.length).trim();
  if(value.length>22000||! /^[A-Za-z0-9+/]+={0,2}$/.test(value))fail('ENCRYPTED_EXPORT_INVALID');
  try{return JSON.parse(Buffer.from(value,'base64').toString('utf8'));}catch{fail('ENCRYPTED_EXPORT_INVALID');}
}
export function instrumentationArgs(publicKey,component=DEFAULT_COMPONENT){
  if(!/^com\.salatik\.familygram\.profilediag\/(?:com\.salatik\.familygram\.profilediag)?\.[A-Za-z0-9_.$]+$/.test(component))fail('INVALID_OPTIONS');
  return ['-s',SERIAL,'shell','am','instrument','-w','-r','-e','recipient_key',publicKey.export({type:'spki',format:'der'}).toString('base64'),component];
}
async function exportFromPhone(adb,publicKey,component){
  const args=instrumentationArgs(publicKey,component);
  return new Promise((accept,reject)=>{
    const child=spawn(adb,args,{stdio:['ignore','pipe','pipe'],windowsHide:true});
    let output='',settled=false;
    const finish=(code,envelope)=>{if(settled)return;settled=true;clearTimeout(timer);output='';if(code){child.kill();reject(Object.assign(new Error(code),{code}));}else accept(envelope);};
    const timer=setTimeout(()=>finish('ADB_TIMEOUT'),90000);
    child.on('error',()=>finish('ADB_FAILED'));
    child.stderr.on('data',()=>{});
    child.stdout.on('data',chunk=>{if(settled)return;output+=chunk.toString('utf8');if(Buffer.byteLength(output)>65536)finish('ENCRYPTED_EXPORT_INVALID');});
    child.once('close',code=>{if(code!==0)return finish('ADB_FAILED');try{finish(null,parseInstrumentation(output));}catch(error){finish(codes.has(error.code)?error.code:'ENCRYPTED_EXPORT_INVALID');}});
  });
}
function storeProfile(directory,profile){
  let temp;
  try{
    if(!directory||!lstatSync(directory).isDirectory()||lstatSync(directory).isSymbolicLink())fail('PRIVATE_DIRECTORY_INVALID');
    const path=join(directory,'private-profile.json');
    temp=join(directory,`.phone-profile-${randomBytes(12).toString('hex')}.tmp`);
    writeFileSync(temp,JSON.stringify(profile),{mode:0o600,flag:'wx'});renameSync(temp,path);temp=null;
    const metadata={version:1,source:'authorized-phone-config-api',createdAt:new Date().toISOString(),expiresAt:new Date(Date.now()+15*60000).toISOString(),purpose:'External network and emulator QA. Delete after QA; this timestamp does not revoke the device credential.'};
    temp=join(directory,`.phone-metadata-${randomBytes(12).toString('hex')}.tmp`);
    writeFileSync(temp,JSON.stringify(metadata),{mode:0o600,flag:'wx'});renameSync(temp,join(directory,'metadata.json'));temp=null;
    return path;
  }catch(error){if(temp){try{unlinkSync(temp);}catch{}}fail(error.code==='PRIVATE_DIRECTORY_INVALID'?error.code:'PRIVATE_WRITE_FAILED');}
}
export async function main(args=process.argv.slice(2)){
  try{
    const mode=args.shift(),options={};
    if(!['--plan','--run'].includes(mode)||args.length%2)fail('INVALID_OPTIONS');
    while(args.length){const name=args.shift(),value=args.shift();if(!['--adb','--binary','--component'].includes(name)||options[name]||!value)fail('INVALID_OPTIONS');options[name]=value;}
    const base=dirname(fileURLToPath(import.meta.url));
    const binary=resolve(options['--binary']||join(base,'../runtime/xray-windows.exe'));
    const adb=options['--adb'];
    if(!adb||!lstatSync(adb).isFile())fail('ADB_UNAVAILABLE');
    if(createHash('sha256').update(readFileSync(binary)).digest('hex')!==XRAY_SHA256)fail('BINARY_VERIFICATION_FAILED');
    const component=options['--component']||DEFAULT_COMPONENT;
    if(mode==='--plan'){instrumentationArgs(generateKeyPairSync('rsa',{modulusLength:3072}).publicKey,component);console.log('PHONE_OUTSIDE_PLAN_OK: verified binary; no phone command, profile export or probe.');return;}
    if(process.platform!=='win32')fail('LOCAL_FAILURE');
    const directory=process.env.FAMILYGRAM_OUTSIDE_PRIVATE_DIR;
    if(!directory||!lstatSync(directory).isDirectory()||lstatSync(directory).isSymbolicLink())fail('PRIVATE_DIRECTORY_INVALID');
    console.log('Authorized phone config export starts. Instrumentation can restart FamilyGram; no Telegram account keys are requested.');
    const {publicKey,privateKey}=generateKeyPairSync('rsa',{modulusLength:3072});
    const profile=decryptExport(await exportFromPhone(adb,publicKey,component),privateKey);
    const path=storeProfile(directory,profile);
    console.log(JSON.stringify({check:'PHONE_APPROVED_PROFILE_EXPORT',ok:true}));
    for(const [check,gatewayHost] of [['PHONE_PROFILE_PUBLIC_DOMAIN_REALITY_MTPROTO','24projects.ru'],['PHONE_PROFILE_PUBLIC_IPV4_REALITY_MTPROTO','157.228.191.231']]){
      const result=await probeReality(profile,profile.uuid,{binary,gatewayHost,timeoutMs:8000});
      console.log(JSON.stringify({check,ok:result.ok===true,code:PROBE_CODES.includes(result.code)?result.code:'NETWORK_ERROR',elapsed_ms:result.elapsed_ms}));
    }
    console.log(`Private QA profile saved: ${path}`);
    console.log('Delete private QA files after testing. The 15-minute metadata timestamp does not revoke the approved device.');
  }catch(error){console.log(JSON.stringify({check:'PHONE_OUTSIDE_DIAGNOSTIC',ok:false,code:codes.has(error.code)?error.code:'LOCAL_FAILURE'}));process.exitCode=1;}
}
if(process.argv[1]&&pathToFileURL(resolve(process.argv[1])).href===import.meta.url)await main();
