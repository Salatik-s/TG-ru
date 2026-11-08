import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { here, lock, verifySource, gitBlob, patchSocket } from '../prepare.mjs';
const read=name=>readFileSync(join(here,name),'utf8');
const original=read('upstream/TMessagesProj/jni/tgnet/ConnectionSocket.cpp');
const patched=patchSocket(original);

test('all source, crypto headers and ARM64 crypto archive match exact pinned Git blobs',()=>{
  assert.equal(verifySource(join(here,'upstream')),144);
  const entry=lock.files.find(x=>x.path.endsWith('/ConnectionSocket.cpp'));
  assert.equal(gitBlob(Buffer.from(original)),entry.blob);
  assert.notEqual(gitBlob(Buffer.from(original+'\n')),entry.blob);
});
test('x86_64 uses its separately pinned crypto without weakening ARM64 input validation',()=>{
  assert.equal(verifySource(join(here,'upstream'),{abi:'x86_64'}),144);
  assert.equal(lock.files.find(x=>x.path.includes('/lib/x86_64/')).blob,'7681f79fe6f963be6b2d92d69429416f5aafed8f');
  assert.throws(()=>verifySource(join(here,'upstream'),{abi:'x86'}),/Unsupported/);
});
test('socket patch removes every legacy TCP/DNS creation path including proxy resolution',()=>{
  assert.match(original,/socket\(AF_INET6/);
  assert.match(original,/gethostbyname\(/);
  assert.doesNotMatch(patched,/\b(socket|connect|gethostbyname|getaddrinfo|setsockopt)\s*\(/);
  assert.match(patched,/FamilyTransportDial\(endpoint.c_str\(\), endpointPort\)/);
  assert.match(patched,/EPOLLOUT \| EPOLLIN \| EPOLLRDHUP \| EPOLLERR \| EPOLLET/);
  assert.match(patched,/if \(!FamilyTransportReady\(\)\) \{\s+closeSocket\(1, EACCES\);\s+return;/);
  assert.match(patched,/if \(socketFd < 0\) \{\s+closeSocket\(1, ECONNREFUSED\);\s+return;/);
});
test('socket replacement preserves receive/write/encryption logic and optional nested proxy semantics',()=>{
  const start='int32_t ConnectionSocket::checkSocketError(',end='void ConnectionSocket::onHostNameResolved(';
  const retained=patched.slice(patched.indexOf(start),patched.indexOf(end)).replace('        FamilyTransportRelease(socketFd);\n','');
  assert.equal(retained,original.slice(original.indexOf(start),original.indexOf(end)));
  assert.match(patched,/useProxy \? \*proxyAddress : address/);
  assert.match(patched,/transportSecret.substr\(1, 16\)/);
  assert.match(patched,/proxyAuthState = 10/);
  assert.match(patched,/proxyAuthState = 1/);
});
test('JNI load refuses missing engine and ABI mismatch before re-registration',()=>{
  const jni=read('FamilyJni.cpp'),entry=jni.slice(jni.indexOf('extern "C" JNIEXPORT jint JNICALL JNI_OnLoad'));
  assert(entry.indexOf('verifyOriginalBufferAbi(env)')<entry.indexOf('registerNativeTgNetFunctions(vm, env)'));
  assert.match(entry,/if \(!FamilyTransportLoad\(\) \|\| !verifyOriginalBufferAbi\(env\)\) return JNI_ERR/);
  assert.match(jni,/GetDirectBufferAddress/);assert.match(jni,/buffer->position\(7\)/);
  assert.match(jni,/native_test_AuthAuthorization/);
  for(const name of ['nativeStart','nativeStop','nativeReady','nativeLastError','nativeRenewLease']) assert(jni.includes(name));
});
test('transport has no fallback, uses exact lease-aware tunnel ABI, exposes generic errors only',()=>{
  const bridge=read('FamilyTransport.cpp');
  for(const symbol of ['FGStart','FGReady','FGDial','FGStop','FGRefreshLease','FGRelease']) assert(bridge.includes(`"${symbol}"`));
  assert.doesNotMatch(bridge,/\b(socket|connect|gethostbyname|getaddrinfo|printf|fprintf|__android_log_print)\s*\(/);
  assert.match(bridge,/seconds > 75/);
  assert.match(bridge,/!FamilyTransportReady\(\)/);
  assert.match(read('exports.map'),/global: JNI_OnLoad;\s+local: \*;/);
  assert.match(read('CMakeLists.txt'),/-Wl,-Bsymbolic/);
  assert.match(read('CMakeLists.txt'),/-Wl,--no-undefined/);
});
test('normal close and destructor release the Go peer before closing/reusing native fd',()=>{
  assert.equal((patched.match(/FamilyTransportRelease\(socketFd\)/g)||[]).length,2);
  assert.match(patched,/FamilyTransportRelease\(socketFd\);\s+if \(close\(socketFd\)/);
  assert.match(patched,/FamilyTransportRelease\(socketFd\);\s+close\(socketFd\);\s+socketFd = -1/);
});
