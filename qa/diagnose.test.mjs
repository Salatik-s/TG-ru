import test from 'node:test';
import assert from 'node:assert/strict';
import {buildReqPQ,parsePQ} from '../deploy/diagnose.mjs';
test('MTProto probe uses unauthenticated abridged req_pq_multi with exact nonce and valid message ID',()=>{
 const nonce=Buffer.alloc(16,0x5a),packet=buildReqPQ(nonce,1700000000123);
 assert.equal(packet.length,42);assert.equal(packet[0],0xef);assert.equal(packet[1]*4,40);
 assert.equal(packet.readBigUInt64LE(2),0n);assert.equal(packet.readBigUInt64LE(10)&3n,0n);
 assert.equal(packet.readBigUInt64LE(10)>>32n,1700000000n);
 assert.equal(packet.readUInt32LE(18),20);assert.equal(packet.readUInt32LE(22),0xbe7e8ef1);
 assert.deepEqual(packet.subarray(26),nonce);assert.throws(()=>buildReqPQ(Buffer.alloc(15)));
});
test('response parser requires full bounded frame, resPQ constructor and matching random nonce',()=>{
 const nonce=Buffer.alloc(16,7),frame=Buffer.alloc(85);frame[0]=21;
 frame.writeUInt32LE(64,17);frame.writeUInt32LE(0x05162463,21);nonce.copy(frame,25);
 assert.equal(parsePQ(frame.subarray(0,30),nonce),null);
 assert.equal(parsePQ(frame,nonce),true);assert.equal(parsePQ(frame,Buffer.alloc(16,8)),false);
 const bad=Buffer.from(frame);bad.writeUInt32LE(0,21);assert.equal(parsePQ(bad,nonce),false);
 assert.equal(parsePQ(Buffer.from([127,255,255,255]),nonce),false);
 assert.equal(parsePQ(Buffer.from([127,1]),nonce),null);
});
