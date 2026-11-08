// No account login: prove both deployed transports return MTProto resPQ through the selected VPN.
import {readFileSync} from 'node:fs';
import {DatabaseSync} from 'node:sqlite';
import {probeReality,probeNativeOutbound} from '/app/egress-probe.mjs';
try{
  const database=new DatabaseSync('/data/access.sqlite',{readOnly:true});
  let device;
  try {device=database.prepare("SELECT uuid FROM devices WHERE status='approved' AND uuid IS NOT NULL LIMIT 1").get();}
  finally {database.close();}
  if(!device){
    console.log(JSON.stringify({check:'REALITY_VPN_MTPROTO',skipped:true,reason:'NO_APPROVED_DEVICE_YET'}));
  }else{
    const config=JSON.parse(readFileSync('/data/server.json','utf8'));
    const result=await probeReality(config,device.uuid,{gatewayHost:'127.0.0.1',timeoutMs:20000});
    console.log(JSON.stringify({check:'REALITY_VPN_MTPROTO',...result}));
    if(!result.ok)process.exitCode=1;
    // This script runs inside access. HTTPS lives in the separate Compose service
    // tls, while certificate verification and the WebSocket Host remain public.
    const secureWebSocket={protocol:'vless',settings:{vnext:[{address:'tls',port:8443,users:[{id:device.uuid,encryption:'none'}]}]},streamSettings:{network:'ws',security:'tls',wsSettings:{host:'24projects.ru',path:'/v1/transport'},tlsSettings:{serverName:'24projects.ru',allowInsecure:false,useSystemCA:false,alpn:['http/1.1'],curvePreferences:['X25519','CurveP256']}}};
    const webSocketResult=await probeNativeOutbound(secureWebSocket,{timeoutMs:20000});
    console.log(JSON.stringify({check:'WSS_VPN_MTPROTO',...webSocketResult}));
    if(!webSocketResult.ok)process.exitCode=1;
  }
}catch{
  console.log(JSON.stringify({check:'REALITY_VPN_MTPROTO',ok:false,code:'PRIVATE_INPUT_OR_PROBE_FAILURE'}));
  process.exitCode=1;
}
