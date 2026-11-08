// Shared by inline and editor. Loading Python never consumes the execution budget.
const BOOT_MS=30000,RUN_MS=10000;
let worker=null,timer=null;
function stopRun(){clearTimeout(timer);timer=null;const old=worker;worker=null;if(old)old.terminate();}
function executePython(payload){
  stopRun();const id=payload.id;let phase='bootstrap',w;
  const emit=(type,text='',code='')=>console.log((globalThis.SAA_PREFIX||'SAA_RUNNER:')+JSON.stringify({id,type,phase,code,text:String(text).slice(0,65536)}));
  const fail=(code,text)=>{stopRun();emit('error',text,code);};
  emit('loading');
  try{w=new Worker('/runner/worker.mjs',{type:'module'});worker=w;}
  catch(error){fail('bootstrap_failed','Не удалось запустить среду Python: '+String(error.message||error));return;}
  timer=setTimeout(()=>{if(worker===w)fail('bootstrap_timeout','Не удалось запустить среду Python: загрузка заняла больше 30 секунд.');},BOOT_MS);
  w.onmessage=({data})=>{
    if(worker!==w||!data)return;
    if(data.type==='ready'&&phase==='bootstrap'){
      clearTimeout(timer);phase='run';
      timer=setTimeout(()=>{if(worker===w)fail('execution_timeout','Выполнение остановлено: превышен лимит 10 секунд.');},RUN_MS);
      emit('running');w.postMessage({code:payload.code,input:payload.input||''});
    }else if(phase==='run'&&(data.type==='result'||data.type==='error')){
      stopRun();emit(data.type,data.text,data.code||'');
    }
  };
  w.onerror=event=>{
    if(worker!==w)return;
    if(event&&event.preventDefault)event.preventDefault();
    const detail=event&&event.message?String(event.message):'ошибка WebAssembly или Worker';
    fail(phase==='bootstrap'?'bootstrap_failed':'worker_failed',(phase==='bootstrap'?'Не удалось запустить среду Python: ':'Ошибка выполнения Python: ')+detail);
  };
  w.onmessageerror=()=>{if(worker===w)fail('worker_message_failed','Ошибка обмена данными со средой Python.');};
}
globalThis.executePython=executePython;
globalThis.stopRun=stopRun;
