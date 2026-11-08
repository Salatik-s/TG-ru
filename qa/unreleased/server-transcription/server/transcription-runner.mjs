import {spawn} from 'node:child_process';
import {statSync,readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {join} from 'node:path';

export const MODEL_SHA256='422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898';
const fault=code=>Object.assign(new Error(code),{code});
export function runBounded(binary,args,{signal,timeoutMs,maxOutput=8192,spawnProcess=spawn}={}){
  return new Promise((resolve,reject)=>{
    if(signal?.aborted)return reject(fault('cancelled'));
    let child,bytes=0,chunks=[],reason=null,killTimer;
    try{child=spawnProcess(binary,args,{stdio:['ignore','pipe','pipe'],windowsHide:true});}catch{return reject(fault('worker_failed'));}
    const stop=code=>{if(reason)return;reason=code;child.kill('SIGTERM');killTimer=setTimeout(()=>child.kill('SIGKILL'),500);killTimer.unref();};
    const abort=()=>stop('cancelled');signal?.addEventListener('abort',abort,{once:true});
    const timer=setTimeout(()=>stop('worker_timeout'),timeoutMs);timer.unref();
    child.stdout.on('data',c=>{bytes+=c.length;if(bytes>maxOutput)stop('output_too_large');else chunks.push(c);});
    child.stderr.on('data',()=>{}); // Never forward media metadata or recognized speech to logs.
    child.once('error',()=>{reason='worker_failed';});
    child.once('close',code=>{clearTimeout(timer);clearTimeout(killTimer);signal?.removeEventListener('abort',abort);if(reason||code!==0)reject(fault(reason||'worker_failed'));else resolve(Buffer.concat(chunks).toString('utf8'));chunks=[];});
  });
}
export function createWhisperRunner({whisper,ffmpeg,ffprobe,model,spawnProcess=spawn}){
  if(![whisper,ffmpeg,ffprobe,model].every(p=>typeof p==='string'&&p&&statSync(p).isFile()))throw Error('Transcription runtime missing');
  if(createHash('sha256').update(readFileSync(model)).digest('hex')!==MODEL_SHA256)throw Error('Transcription model verification failed');
  return async({path,directory,maxSeconds,signal})=>{
    const common=['-v','error','-protocol_whitelist','file,pipe','-format_whitelist','ogg,mp3,wav,mov,matroska,webm,flac,aac'];
    let metadata;
    try{metadata=JSON.parse(await runBounded(ffprobe,[...common,'-probesize','4000000','-analyzeduration','5000000','-show_entries','format=duration:stream=codec_type,duration','-of','json',path],{signal,timeoutMs:15000,spawnProcess}));}catch(error){if(error.code==='cancelled')throw error;throw fault('media_invalid');}
    const duration=Number(metadata.format?.duration);
    if(!metadata.streams?.some(s=>s.codec_type==='audio')||!Number.isFinite(duration)||duration<=0)throw fault('media_invalid');
    if(duration>maxSeconds)throw fault('media_too_long');
    const wav=join(directory,'audio.wav'),prefix=join(directory,'transcript');
    try{await runBounded(ffmpeg,[...common,'-nostdin','-y','-threads','1','-i',path,'-map','0:a:0','-vn','-sn','-dn','-t',String(maxSeconds+1),'-ac','1','-ar','16000','-c:a','pcm_s16le','-fs',String((maxSeconds+1)*32000+4096),'-f','wav',wav],{signal,timeoutMs:30000,spawnProcess});}catch(error){if(error.code==='cancelled')throw error;throw fault('decode_failed');}
    if(statSync(wav).size>maxSeconds*32000+128)throw fault('media_too_long');
    await runBounded(whisper,['-m',model,'-f',wav,'-l','auto','-t','2','-p','1','-bs','1','-bo','1','-ng','-np','-nt','-otxt','-of',prefix],{signal,timeoutMs:600000,maxOutput:65536,spawnProcess});
    const textPath=prefix+'.txt';if(statSync(textPath).size>65536)throw fault('output_too_large');
    return readFileSync(textPath,'utf8');
  };
}
