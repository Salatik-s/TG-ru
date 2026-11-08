import { readFileSync, writeFileSync, mkdirSync, existsSync, realpathSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
export const here=dirname(fileURLToPath(import.meta.url));
export const lock=JSON.parse(readFileSync(join(here,'upstream-lock.json'),'utf8').replace(/^\uFEFF/,''));
export function gitBlob(data) {return createHash('sha1').update(`blob ${data.length}\0`).update(data).digest('hex');}
export function verifySource(source, {allowMissingCrypto=false,abi='arm64-v8a'}={}) {
  if(!['arm64-v8a','x86_64'].includes(abi))throw new Error('Unsupported native ABI');
  if(!lock.files.some(f=>f.path===`TMessagesProj/jni/boringssl/lib/${abi}/libcrypto.a`))throw new Error('Missing architecture-specific crypto pin');
  let count=0;
  for(const entry of lock.files) {
    if(entry.path.includes('/boringssl/lib/')&&!entry.path.includes(`/boringssl/lib/${abi}/`))continue;
    const file=join(source,entry.path);
    if(allowMissingCrypto && entry.path.endsWith('.a') && !existsSync(file)) continue;
    if(!existsSync(file)) throw new Error(`Missing pinned input: ${entry.path}`);
    if(gitBlob(readFileSync(file))!==entry.blob) throw new Error(`Pinned source hash mismatch: ${entry.path}`);
    count++;
  }
  return count;
}
export function patchSocket(source) {
  const start=source.indexOf('void ConnectionSocket::openConnection('),end=source.indexOf('int32_t ConnectionSocket::checkSocketError(');
  if(start<0||end<start) throw new Error('ConnectionSocket open anchors changed');
  let patched=source.slice(0,start)+readFileSync(join(here,'ConnectionSocket.transport.inc'),'utf8')+'\n'+source.slice(end);
  const resolver=patched.indexOf('void ConnectionSocket::onHostNameResolved(');
  if(resolver<0) throw new Error('ConnectionSocket resolver anchor changed');
  patched=patched.slice(0,resolver)+`void ConnectionSocket::onHostNameResolved(std::string host, std::string ip, bool ipv6) {
    // Family transport never delegates endpoint resolution to this legacy path.
    (void) host;
    (void) ip;
    (void) ipv6;
}
`;
  patched=patched.replace('#include "ConnectionSocket.h"','#include "ConnectionSocket.h"\n#include "FamilyTransport.h"');
  patched=patched.replace('        if (close(socketFd) != 0) {','        FamilyTransportRelease(socketFd);\n        if (close(socketFd) != 0) {');
  patched=patched.replace('ConnectionSocket::~ConnectionSocket() {',`ConnectionSocket::~ConnectionSocket() {
    if (socketFd >= 0) {
        ConnectionsManager::getInstance(instanceNum).detachConnection(this);
        epoll_ctl(ConnectionsManager::getInstance(instanceNum).epolFd, EPOLL_CTL_DEL, socketFd, nullptr);
        FamilyTransportRelease(socketFd);
        close(socketFd);
        socketFd = -1;
    }`);
  if(/\b(socket|connect|gethostbyname|getaddrinfo|setsockopt)\s*\(/.test(patched)) throw new Error('Direct-network fallback survived socket patch');
  return patched;
}
export function prepare(source=join(here,'upstream'),out=join(here,'generated'),abi='arm64-v8a') {
  const verified=verifySource(source,{abi});
  const input=readFileSync(join(source,'TMessagesProj/jni/tgnet/ConnectionSocket.cpp'),'utf8');
  const patched=patchSocket(input);
  mkdirSync(out,{recursive:true});
  writeFileSync(join(out,'ConnectionSocket.cpp'),patched);
  writeFileSync(join(out,'provenance.json'),JSON.stringify({repository:lock.repository,commit:lock.commit,abi,verified_files:verified,patched_socket_sha256:createHash('sha256').update(patched).digest('hex')},null,2)+'\n');
  console.log(`Verified ${verified} pinned inputs; prepared fail-closed tgnet socket source.`);
}
if(process.argv[1] && realpathSync(resolve(process.argv[1]))===realpathSync(fileURLToPath(import.meta.url))) {
  const args=process.argv.slice(2);
  const value=(name,fallback)=>args.includes(name)?resolve(args[args.indexOf(name)+1]):fallback;
  prepare(value('--source',join(here,'upstream')),value('--out',join(here,'generated')),args.includes('--abi')?args[args.indexOf('--abi')+1]:'arm64-v8a');
}
