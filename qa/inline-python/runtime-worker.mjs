// Node-only harness for the exact shipped worker and pinned Pyodide assets.
import {parentPort} from 'node:worker_threads';
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {loadPyodide} from '../../overlay/assets/familygram/runner/runtime/pyodide.mjs';
import createModule from '../../overlay/assets/familygram/runner/runtime/pyodide.asm.mjs';
globalThis.self={postMessage:data=>parentPort.postMessage(data)};
// Node's filesystem loader needs a native path; Android uses the sealed HTTPS asset URL.
const runtime=fileURLToPath(new URL('../../overlay/assets/familygram/runner/runtime/',import.meta.url));
const source=fs.readFileSync(new URL('../../overlay/assets/familygram/runner/worker.mjs',import.meta.url),'utf8')
  .replace(/^import .*;\r?\n/gm,'').replace("new URL('./runtime/',import.meta.url).href",'runtime');
await new (Object.getPrototypeOf(async function(){}).constructor)('loadPyodide','createModule','runtime',source)(loadPyodide,createModule,runtime);
parentPort.on('message',data=>self.onmessage({data}));
