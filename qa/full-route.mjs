// Account-free full public HTTPS/WSS -> private VPN -> each pinned production DC.
import {readFileSync} from 'node:fs';
import {probeNativeOutbound} from '../server/egress-probe.mjs';
const profile=JSON.parse(readFileSync('C:/Users/andre/.codex/private/familygram-network-debug/private-profile.json','utf8'));
if(!/^[a-f0-9-]{36}$/.test(profile.uuid))throw new Error('Private diagnostic profile invalid');
const outbound={protocol:'vless',settings:{vnext:[{address:'24projects.ru',port:8443,users:[{id:profile.uuid,encryption:'none'}]}]},streamSettings:{network:'ws',security:'tls',wsSettings:{path:'/v1/transport',host:'24projects.ru'},tlsSettings:{serverName:'24projects.ru',allowInsecure:false,useSystemCA:false,alpn:['http/1.1'],curvePreferences:['X25519','CurveP256']}}};
const destinations=[['DC1','149.154.175.50'],['DC2','149.154.167.51'],['DC3','149.154.175.100'],['DC4','149.154.167.91'],['DC5','149.154.171.5']];
for(let n=0;n<destinations.length;n+=2)await Promise.all(destinations.slice(n,n+2).map(async([name,host])=>{
  const result=await probeNativeOutbound(outbound,{binary:'C:/Users/andre/Documents/Codex/familygram-build-2026-11-08/tools/xray-windows-verified/xray.exe',timeoutMs:20000,destination:{host,port:443}});
  console.log(JSON.stringify({check:'PUBLIC_WSS_VPN_'+name+'_MTPROTO',...result}));if(!result.ok)process.exitCode=1;
}));
