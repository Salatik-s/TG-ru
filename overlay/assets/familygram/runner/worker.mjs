import {loadPyodide} from './runtime/pyodide.mjs';
import createModule from './runtime/pyodide.asm.mjs';
let output='',lines=[];
const emit=self.postMessage.bind(self);
function write(text){output+=(String(text)+'\n');if(output.length>65536)throw new Error('Лимит вывода: 64 КБ');}
const python=await loadPyodide({indexURL:new URL('./runtime/',import.meta.url).href,
  createPyodideModule:createModule,jsglobals:Object.freeze({}),
  stdout:write,stderr:write,stdin:()=>lines.length?lines.shift():null,
  packages:[],enableRunUntilComplete:false});
self.onmessage=({data})=>{
  if(!data||typeof data.code!=='string'||data.code.length>65536)return;
  lines=typeof data.input==='string'?data.input.slice(0,16384).split('\n'):[];
  output='';
  try{python.runPython(data.code);if(!output)output='Код выполнен. Вывод отсутствует.';}
  catch(error){write(error instanceof Error?error.message:String(error));}
  emit({type:'result',text:output.slice(0,65536)});
};
emit({type:'ready'});
