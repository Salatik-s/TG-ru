import { request } from 'node:http';
const [command,id]=process.argv.slice(2);
if(!['invite','list','approve','revoke','set-owner'].includes(command)) {console.error('Usage: node owner.mjs invite [hours] | list | approve DEVICE_ID | revoke DEVICE_ID | set-owner APPROVED_DEVICE_ID');process.exit(2);}
const payload=JSON.stringify({command,...(command==='invite'?{hours:id?Number(id):24}:{id})});
const req=request({socketPath:`${process.env.DATA_DIR||'/data'}/owner.sock`,path:'/owner',method:'POST',headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(payload)}},res=>{
  let data='';res.on('data',c=>data+=c);res.on('end',()=>{if(res.statusCode!==200){console.error(data);process.exitCode=1;}else console.log(JSON.stringify(JSON.parse(data),null,2));});
});
req.on('error',()=>{console.error('Local owner socket unavailable');process.exitCode=1;});req.end(payload);
