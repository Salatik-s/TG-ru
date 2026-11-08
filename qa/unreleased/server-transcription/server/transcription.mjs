import {createHash,randomBytes,randomUUID,timingSafeEqual} from 'node:crypto';
import {mkdirSync,mkdtempSync,chmodSync,rmSync,lstatSync} from 'node:fs';
import {open} from 'node:fs/promises';
import {join} from 'node:path';
import {ApiError} from './access.mjs';
import {transcriptionPayload,MAX_UPLOAD,MAX_SECONDS} from './transcription-policy.mjs';

const digest=value=>createHash('sha256').update(value).digest();
const terminal=new Set(['done','failed','cancelled']);
const publicErrors=new Set(['media_invalid','media_too_long','decode_failed','worker_failed','worker_timeout','output_too_large','upload_invalid','upload_timeout','content_mismatch','revoked','cancelled','expired']);
export class Transcription {
  constructor(access,{directory,runner,now=()=>Math.floor(Date.now()/1000),uploadTimeoutMs=90000}={}){
    if(typeof runner!=='function'||!directory)throw Error('Transcription runtime unavailable');
    this.access=access;this.runner=runner;this.now=now;this.uploadTimeoutMs=uploadTimeoutMs;this.jobs=new Map();this.active=null;this.closed=false;
    mkdirSync(directory,{recursive:true,mode:0o700});if(lstatSync(directory).isSymbolicLink())throw Error('Invalid transcription directory');chmodSync(directory,0o700);
    // A per-process directory isolates cleanup from other services and prior runs.
    this.directory=mkdtempSync(join(directory,'worker-'));chmodSync(this.directory,0o700);
    this.timer=setInterval(()=>this.sweep(),1000);this.timer.unref();
  }
  approved(id){if(this.access.device(id)?.status!=='approved')throw new ApiError(403,'not_approved');}
  reserve(id,payload){
    this.approved(id);this.sweep();if(this.closed)throw new ApiError(503,'transcription_unavailable');
    const clean=transcriptionPayload('transcription',payload);
    if(this.active)throw new ApiError(429,'transcription_busy',10);
    this.access.rate(id,'transcription',30,3600);
    while(this.jobs.size>=20){const oldest=[...this.jobs.values()].find(j=>terminal.has(j.state));if(!oldest)throw new ApiError(429,'transcription_busy',10);this.jobs.delete(oldest.id);}
    const token=randomBytes(32).toString('base64url'),job={id:randomUUID(),device:id,payload:clean,state:'uploading',expires:this.now()+120,tokenHash:digest(token),abort:new AbortController(),request:null,operation:null,text:null,error:null};
    job.directory=mkdtempSync(join(this.directory,'job-'));chmodSync(job.directory,0o700);this.jobs.set(job.id,job);this.active=job.id;
    return {job_id:job.id,state:job.state,upload_url:`/v1/transcriptions/${job.id}`,upload_token:token,expires_at:job.expires,max_duration_seconds:MAX_SECONDS};
  }
  owned(id,jobId){this.approved(id);const job=this.jobs.get(jobId);if(!job||job.device!==id||job.expires<=this.now())throw new ApiError(404,'transcription_not_found');return job;}
  status(id,jobId){this.sweep();return this.result(this.owned(id,jobId));}
  result(job){return {job_id:job.id,state:job.state,expires_at:job.expires,...(job.state==='done'?{text:job.text}:{}),...(job.error?{error:job.error}:{})};}
  cancel(id,jobId){const job=this.owned(id,jobId);this.abort(job,'cancelled');return this.result(job);}
  abort(job,reason){
    job.tokenHash=null;job.text=null;job.state=reason==='cancelled'?'cancelled':'failed';job.error=reason;job.expires=this.now()+600;job.abort.abort();job.request?.destroy();
    if(!job.operation)this.cleanup(job);
  }
  cleanup(job){rmSync(job.directory,{recursive:true,force:true});if(this.active===job.id)this.active=null;}
  revoke(id){for(const job of this.jobs.values())if(job.device===id){this.abort(job,'revoked');this.jobs.delete(job.id);}}
  sweep(){
    for(const job of this.jobs.values()){
      if(this.access.device(job.device)?.status!=='approved'){this.revoke(job.device);continue;}
      if(job.expires<=this.now()){if(!terminal.has(job.state))this.abort(job,'expired');this.jobs.delete(job.id);}
    }
  }
  async upload(req,jobId){
    const job=this.jobs.get(jobId),token=String(req.headers.authorization||'').replace(/^Bearer /,'');
    if(!job||!job.tokenHash||job.state!=='uploading'||job.expires<=this.now()||! /^[A-Za-z0-9_-]{43}$/.test(token)||!timingSafeEqual(job.tokenHash,digest(token)))throw new ApiError(401,'invalid_upload_token');
    this.approved(job.device);job.tokenHash=null;job.request=req;
    let release;job.operation=new Promise(r=>release=r);
    const timer=setTimeout(()=>{job.error='upload_timeout';job.abort.abort();req.destroy();},this.uploadTimeoutMs);timer.unref();
    let file,bytes=0;const hash=createHash('sha256'),path=join(job.directory,'upload.bin');
    try{
      if(req.headers['content-type']!==job.payload.mime||String(req.headers['content-length'])!==String(job.payload.size)||req.headers['content-encoding']&&req.headers['content-encoding']!=='identity')throw new ApiError(400,'upload_invalid');
      file=await open(path,'wx',0o600);
      for await(const chunk of req){
        if(job.abort.signal.aborted)throw new ApiError(410,job.error||'cancelled');
        bytes+=chunk.length;if(bytes>job.payload.size||bytes>MAX_UPLOAD)throw new ApiError(413,'upload_invalid');
        hash.update(chunk);await file.writeFile(chunk);
      }
      await file.close();file=null;this.approved(job.device);
      if(job.abort.signal.aborted)throw new ApiError(410,job.error||'cancelled');
      if(bytes!==job.payload.size||hash.digest('hex')!==job.payload.sha256)throw new ApiError(400,'content_mismatch');
      job.state='processing';job.expires=this.now()+660;
      clearTimeout(timer);job.request=null;release();job.operation=this.process(job,path);
      return {job_id:job.id,state:'processing'};
    }catch(error){
      if(file)await file.close().catch(()=>{});
      job.state='failed';job.error=publicErrors.has(error.code)?error.code:job.error||'upload_invalid';job.expires=this.now()+600;job.text=null;this.cleanup(job);release();job.operation=null;
      throw new ApiError(error instanceof ApiError?error.status:400,job.error);
    }finally{clearTimeout(timer);job.request=null;}
  }
  async process(job,path){
    try{
      const text=await this.runner({path,directory:job.directory,mime:job.payload.mime,maxSeconds:MAX_SECONDS,signal:job.abort.signal});
      this.approved(job.device);
      if(job.abort.signal.aborted)return;
      if(typeof text!=='string'||Buffer.byteLength(text)>65536)throw Object.assign(Error(),{code:'output_too_large'});
      job.text=text.trim();job.state='done';job.error=null;
    }catch(error){if(!job.abort.signal.aborted){job.state='failed';job.error=publicErrors.has(error.code)?error.code:'worker_failed';job.text=null;}}
    finally{job.expires=this.now()+600;this.cleanup(job);job.operation=null;}
  }
  async close(){this.closed=true;clearInterval(this.timer);const pending=[];for(const job of this.jobs.values()){this.abort(job,'cancelled');if(job.operation)pending.push(job.operation);}await Promise.allSettled(pending);this.jobs.clear();rmSync(this.directory,{recursive:true,force:true});}
}
