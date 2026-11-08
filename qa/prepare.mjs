import {mkdirSync,existsSync,writeFileSync,readFileSync} from 'node:fs';
import {homedir} from 'node:os';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import {randomBytes,generateKeyPairSync} from 'node:crypto';
process.umask(0o077);
const dir=join(homedir(),'.codex/private/familygram-qa');
mkdirSync(dir,{recursive:true,mode:0o700});
const args=process.argv.slice(2),openssl=args.includes('--openssl')?args[args.indexOf('--openssl')+1]:'openssl';
const path=name=>join(dir,name);
const run=(...args)=>execFileSync(openssl,args,{cwd:dir,encoding:'utf8',stdio:['ignore','pipe','pipe']});
if(!existsSync(path('settings.json'))){
 const pair=generateKeyPairSync('x25519');
 writeFileSync(path('settings.json'),JSON.stringify({pepper:randomBytes(32).toString('hex'),ownerToken:randomBytes(32).toString('base64url'),publicKey:pair.publicKey.export({format:'jwk'}).x,shortId:randomBytes(8).toString('hex')}),{flag:'wx',mode:0o600});
}
writeFileSync(path('ca.cnf'),'[req]\ndistinguished_name=dn\nx509_extensions=v3_ca\n[dn]\n[v3_ca]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n');
writeFileSync(path('server.cnf'),'[req]\ndistinguished_name=dn\n[dn]\n[server]\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:24projects.ru\n');
if(!existsSync(path('ca.pem')))run('req','-x509','-newkey','rsa:2048','-nodes','-days','3','-subj','/CN=FamilyGram Isolated QA CA','-config',path('ca.cnf'),'-extensions','v3_ca','-keyout',path('ca.key'),'-out',path('ca.pem'));
if(!existsSync(path('server.pem'))){
 run('req','-new','-newkey','rsa:2048','-nodes','-subj','/CN=24projects.ru','-config',path('server.cnf'),'-keyout',path('server.key'),'-out',path('server.csr'));
 run('x509','-req','-in',path('server.csr'),'-CA',path('ca.pem'),'-CAkey',path('ca.key'),'-CAcreateserial','-days','2','-extfile',path('server.cnf'),'-extensions','server','-out',path('server.pem'));
}
run('verify','-CAfile',path('ca.pem'),'-verify_hostname','24projects.ru',path('server.pem'));
run('x509','-in',path('server.pem'),'-checkend','3600','-noout');
const hash=run('x509','-in',path('ca.pem'),'-subject_hash_old','-noout').trim();
writeFileSync(path(`${hash}.0`),readFileSync(path('ca.pem')));
writeFileSync(path('public-info.json'),JSON.stringify({ca:path('ca.pem'),androidCa:path(`${hash}.0`),subjectHash:hash,api:'https://24projects.ru:8443',emulatorHostsLine:'10.0.2.2 24projects.ru',gatewayMode:'configuration-only-no-listener'},null,2));
console.log(JSON.stringify({privateDirectory:dir,ca:path('ca.pem'),androidCa:path(`${hash}.0`),subjectHash:hash}));
