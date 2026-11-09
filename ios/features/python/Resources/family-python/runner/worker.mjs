import {loadPyodide} from './runtime/pyodide.mjs';
import createModule from './runtime/pyodide.asm.mjs';
let output='',lines=[],outputExceeded=false;
const emit=self.postMessage.bind(self);
function write(text){const chunk=String(text)+'\n';if(output.length+chunk.length>65536)outputExceeded=true;output+=chunk.slice(0,Math.max(0,65536-output.length));}
const python=await loadPyodide({indexURL:new URL('./runtime/',import.meta.url).href,
  createPyodideModule:createModule,jsglobals:Object.freeze({}),
  stdout:write,stderr:write,stdin:()=>lines.length?lines.shift():null,
  packages:[],enableRunUntilComplete:false});
self.onmessage=({data})=>{
  if(!data||typeof data.code!=='string'||data.code.length>65536)return;
  lines=typeof data.input==='string'?data.input.slice(0,16384).split('\n'):[];
  output='';outputExceeded=false;
  try{
    python.runPython(data.code);
    if(outputExceeded)throw new Error('Лимит вывода: 64 КБ');
    emit({type:'result',phase:'run',text:(output||'Код выполнен. Вывод отсутствует.').slice(0,65536)});
  }catch(error){
    // PythonError.message retains exception class, traceback and source line. Never pass
    // the error through write(): an output-limit exception must not throw a second time.
    const reason=error instanceof Error?error.message:String(error);
    const remaining=Math.max(0,65536-reason.length-1);
    emit({type:'error',phase:'run',code:outputExceeded?'output_limit':'python_exception',text:(output.slice(0,remaining)+'\n'+reason).trim().slice(0,65536)});
  }
};
emit({type:'ready'});
