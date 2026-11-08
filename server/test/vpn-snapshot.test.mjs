import test from 'node:test';
import assert from 'node:assert/strict';
import {buildSnapshot} from '../vpn-snapshot.mjs';
import {nodeTag,parseNodeUri} from '../vpn-nodes.mjs';
const uri=(id,label='region')=>`vless://${id}@vpn.example.com:443?security=tls&sni=vpn.example.com#${encodeURIComponent(label)}`;
const first='21b3c5d7-e9f1-4234-8567-89abcdef0123',second='31b3c5d7-e9f1-4234-8567-89abcdef0123';
test('regular nodes precede explicitly classified BS fallback; no freedom/socks outbound exists',()=>{
 const {snapshot,stats}=buildSnapshot({raw:uri(first,'БС')+'\n'+uri(second,'Netherlands')});
 assert.equal(stats.regular,1);assert.equal(stats.bs,1);
 assert.deepEqual(snapshot.candidates.map(c=>c.tier),['regular','bs']);
 assert(snapshot.candidates.every(c=>c.outbound.protocol==='vless'&&c.outbound.streamSettings.security==='tls'));
});
test('cached preferred node is reused without provider fetch; duplicate raw export is deduplicated',()=>{
 const node=parseNodeUri(uri(first));node.tag=nodeTag(node);
 const {snapshot,stats}=buildSnapshot({cached:{version:1,nodes:[node]},selection:{nodeTag:node.tag},raw:Buffer.from(uri(first)).toString('base64')});
 assert.equal(snapshot.preferredTag,node.tag);assert.equal(snapshot.candidates.length,1);assert.equal(stats.duplicates,1);
});
test('unsafe endpoints, plaintext VLESS and unsupported protocols never become egress candidates',()=>{
 const raw=[`vless://${first}@127.0.0.1:443?security=tls`, `vless://${first}@vpn.example.com:443`, 'hysteria2://example'].join('\n');
 const {snapshot}=buildSnapshot({raw});assert.equal(snapshot.candidates.length,0);
});
test('original parser default still excludes BS unless family snapshot explicitly requests it',()=>{
 assert.throws(()=>parseNodeUri(uri(first,'БС')));
 assert.equal(parseNodeUri(uri(first,'БС'),{allowBs:true}).type,'vless');
});
