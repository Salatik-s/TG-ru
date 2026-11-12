import { createServer } from 'node:http';
import { ApiError } from './access.mjs';

export async function body(req) {
  let bytes=0; const chunks=[];
  for await (const c of req) { bytes+=c.length; if (bytes>4096) throw new ApiError(413,'body_too_large'); chunks.push(c); }
  try {return JSON.parse(Buffer.concat(chunks).toString('utf8'));} catch {throw new ApiError(400,'invalid_json');}
}
export function send(res, status, value, retry) {
  res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store','X-Content-Type-Options':'nosniff',...(retry?{'Retry-After':String(retry)}:{})});
  res.end(JSON.stringify(value));
}
export function failure(res,e) { if(res.headersSent){res.destroy();return;}send(res,e instanceof ApiError?e.status:503,{error:e instanceof ApiError?e.code:'unavailable',...(e.retryAfter?{retry_after:e.retryAfter}:{})},e.retryAfter); }
export function apiServer(access,gateway,{trustProxy=false,push=null,manage=null}={}) {
  const server=createServer(async(req,res)=>{
    try {
      if(req.method==='GET' && req.url==='/health') return send(res,gateway.ready?200:503,{status:gateway.ready?'ok':'unavailable'});
      const ip=trustProxy ? req.headers['x-real-ip'] : req.socket.remoteAddress;
      if(typeof ip!=='string' || ip.length>64) throw new ApiError(400,'invalid_source');
      if(push && req.method==='GET' && req.url==='/v1/events') {access.rate(ip,'sse',3600,3600);return push.open(req,res);}
      if(push && req.method==='PUT' && req.url.startsWith('/v1/push/')) {
        const id=push.webhookIdentity(req.url,ip);let bytes=0;const chunks=[];
        for await(const c of req){bytes+=c.length;if(bytes>128)throw new ApiError(413,'push_too_large');chunks.push(c);}
        push.signal(id,Buffer.concat(chunks));return send(res,200,{ok:true});
      }
      if(req.method!=='POST' || !['/v1/challenge','/v1/complete'].includes(req.url)) throw new ApiError(404,'not_found');
      if(!String(req.headers['content-type']).startsWith('application/json')) throw new ApiError(415,'json_required');
      const input=await body(req);
      if(req.url==='/v1/challenge') return send(res,200,access.challenge(input,ip));
      const response=access.complete(input,ip);
      if(response.action==='manage') {
        if(response.management.command==='set_developer') {
          const management=access.setDeveloper(response.device.id,response.management.username);
          return send(res,200,{...access.status(access.device(response.device.id)),management});
        }
        if(typeof manage!=='function')throw new ApiError(503,'management_unavailable');
        const management=await manage(response.device.id,response.management);
        return send(res,200,{...access.status(access.device(response.device.id)),management});
      }
      if(response.action==='config') {
        if(!gateway.ready) throw new ApiError(503,'gateway_unavailable');
        response.result.gateway=gateway.client(response.device);
        if(typeof gateway.clientFallbacks==='function')response.result.gateway_fallbacks=gateway.clientFallbacks(response.device);
        if(push)response.result.notifications=push.mint(response.device.id);
      }
      send(res,200,response.result);
    } catch(e) {failure(res,e);}
  });
  server.requestTimeout=10000; server.headersTimeout=10000; server.maxHeadersCount=32;
  return server;
}
