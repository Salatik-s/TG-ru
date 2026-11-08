export const MAX_UPLOAD=16*1024*1024;
export const MAX_SECONDS=300;
export const TRANSCRIPTION_ACTIONS=['transcription','transcription_status','transcription_cancel'];
export const MEDIA_TYPES=['audio/ogg','audio/opus','audio/mpeg','audio/mp4','audio/wav','audio/x-wav','audio/flac','audio/webm','video/mp4','video/webm'];
export function transcriptionPayload(action,payload){
  if(!payload||typeof payload!=='object'||Array.isArray(payload))throw Error();
  const keys=Object.keys(payload).sort().join(',');
  if(action==='transcription'){
    if(keys!=='mime,sha256,size'||! /^[a-f0-9]{64}$/.test(payload.sha256||'')||!Number.isSafeInteger(payload.size)||payload.size<1||payload.size>MAX_UPLOAD||!MEDIA_TYPES.includes(payload.mime))throw Error();
    return {sha256:payload.sha256,size:payload.size,mime:payload.mime};
  }
  if(!TRANSCRIPTION_ACTIONS.includes(action)||keys!=='job_id'||typeof payload.job_id!=='string'||! /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(payload.job_id))throw Error();
  return {job_id:payload.job_id};
}
