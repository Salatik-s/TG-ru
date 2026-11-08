import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { ApiError } from './access.mjs';

const digest=value=>createHash('sha256').update(value).digest('hex');
const deny=(status,error)=>{throw new ApiError(status,error);};
export class PushRelay {
  constructor(access,{base='https://24projects.ru:8443',heartbeatMs=15000}={}) {
    this.access=access;this.base=base;this.tokens=new Map();this.streams=new Map();
    access.db.exec(`CREATE TABLE IF NOT EXISTS push_state(device TEXT PRIMARY KEY,sequence INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS push_signals(device TEXT NOT NULL,sequence INTEGER NOT NULL,version_hash TEXT NOT NULL,PRIMARY KEY(device,sequence),UNIQUE(device,version_hash));`);
    this.timer=setInterval(()=>this.tick(),heartbeatMs);this.timer.unref();
  }
  secret(id) {return createHmac('sha256',this.access.pepper).update(`familygram-simplepush-v1:${id}`).digest('base64url');}
  requireApproved(id) {if(this.access.device(id)?.status!=='approved') deny(403,'push_denied');}
  mint(id) {
    this.requireApproved(id);this.tick(false);
    const previous=[...this.tokens].filter(([,t])=>t.id===id);
    for(const [hash] of previous.slice(0,Math.max(0,previous.length-1)))this.tokens.delete(hash);
    const token=randomBytes(32).toString('base64url'),expires=this.access.now()+75;
    this.tokens.set(digest(token),{id,expires});
    return {endpoint:`${this.base}/v1/push/${id}/${this.secret(id)}`,stream_url:`${this.base}/v1/events`,token,expires_at:expires};
  }
  webhookIdentity(path,ip) {
    this.access.rate(ip,'push-ip',1200,60);
    const match=/^\/v1\/push\/([a-f0-9]{64})\/([A-Za-z0-9_-]{43})$/.exec(path);
    if(!match) deny(404,'not_found');
    const [,id,secret]=match;
    if(!timingSafeEqual(Buffer.from(secret),Buffer.from(this.secret(id))))deny(404,'not_found');
    this.requireApproved(id);this.access.rate(id,'push-device',120,60);
    return id;
  }
  signal(id,raw) {
    this.requireApproved(id);
    if(!Buffer.isBuffer(raw)||raw.length>128)deny(413,'push_too_large');
    const text=raw.toString('utf8');
    if(!/^version=[0-9]{1,40}$/.test(text))deny(400,'invalid_push');
    // Only an HMAC of the opaque version and a monotonic local sequence persist.
    const versionHash=createHmac('sha256',this.access.pepper).update(`push-version:${id}:${text}`).digest('hex');
    const result=this.access.tx(()=>{
      const old=this.access.db.prepare('SELECT sequence FROM push_signals WHERE device=? AND version_hash=?').get(id,versionHash);
      if(old)return {duplicate:true,sequence:old.sequence};
      this.access.db.prepare('INSERT INTO push_state VALUES (?,1) ON CONFLICT(device) DO UPDATE SET sequence=sequence+1').run(id);
      const sequence=this.access.db.prepare('SELECT sequence FROM push_state WHERE device=?').get(id).sequence;
      this.access.db.prepare('INSERT INTO push_signals VALUES (?,?,?)').run(id,sequence,versionHash);
      this.access.db.prepare('DELETE FROM push_signals WHERE device=? AND sequence<=?').run(id,sequence-64);
      return {duplicate:false,sequence};
    });
    if(!result.duplicate)for(const stream of this.streams.get(id)||[])this.write(stream,result.sequence,'wake');
    return result;
  }
  authenticate(header) {
    const match=/^Bearer ([A-Za-z0-9_-]{43})$/.exec(header||'');
    const token=match?this.tokens.get(digest(match[1])):null;
    if(!token||token.expires<=this.access.now())deny(401,'push_token_expired');
    this.requireApproved(token.id);return token;
  }
  open(req,res) {
    const token=this.authenticate(req.headers.authorization);
    const header=req.headers['last-event-id']||'0';
    if(typeof header!=='string'||!/^\d{1,15}$/.test(header))deny(400,'invalid_event_id');
    const cursor=Number(header);if(!Number.isSafeInteger(cursor))deny(400,'invalid_event_id');
    let streams=this.streams.get(token.id);if(!streams){streams=new Set();this.streams.set(token.id,streams);}
    if(streams.size>=2)deny(429,'too_many_streams');
    res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-store','X-Accel-Buffering':'no','Connection':'keep-alive','X-Content-Type-Options':'nosniff'});
    res.flushHeaders();res.write(': ready\n\n');
    const stream={res,id:token.id,expires:token.expires};streams.add(stream);
    const expiry=setTimeout(()=>res.end(),Math.max(0,(token.expires-this.access.now())*1000));expiry.unref();
    res.once('close',()=>{clearTimeout(expiry);streams.delete(stream);if(!streams.size)this.streams.delete(token.id);});
    const rows=this.access.db.prepare('SELECT sequence FROM push_signals WHERE device=? ORDER BY sequence').all(token.id);
    const latest=this.access.db.prepare('SELECT sequence FROM push_state WHERE device=?').get(token.id)?.sequence||0;
    if(rows.length && (cursor<rows[0].sequence-1||cursor>latest))this.write(stream,latest,'reset');
    else for(const row of rows)if(row.sequence>cursor)this.write(stream,row.sequence,'wake');
    if(!latest && cursor)this.write(stream,0,'cursor');
  }
  write(stream,sequence,event) {
    if(stream.expires<=this.access.now()||this.access.device(stream.id)?.status!=='approved'){stream.res.end();return;}
    if(!stream.res.write(`id: ${sequence}\nevent: ${event}\ndata: {"kind":"wake"}\n\n`))stream.res.destroy();
  }
  tick(heartbeat=true) {
    const now=this.access.now();
    for(const [key,token] of this.tokens)if(token.expires<=now||this.access.device(token.id)?.status!=='approved')this.tokens.delete(key);
    for(const [id,streams] of this.streams)for(const stream of streams) {
      if(stream.expires<=now||this.access.device(id)?.status!=='approved')stream.res.end();
      else if(heartbeat&&!stream.res.write(': heartbeat\n\n'))stream.res.destroy();
    }
  }
  revoke(id) {
    for(const [key,t] of this.tokens)if(t.id===id)this.tokens.delete(key);
    for(const stream of this.streams.get(id)||[])stream.res.end();
  }
  close() {clearInterval(this.timer);for(const streams of this.streams.values())for(const stream of streams)stream.res.end();this.tokens.clear();}
}
