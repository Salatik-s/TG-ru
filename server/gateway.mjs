import { spawn } from 'node:child_process';
import { writeFileSync, renameSync } from 'node:fs';
import { createConnection } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';

export function xrayConfig(config, devices, route=null, {allowDirectForTests=false}={}) {
  const outbound=route?.outbound?structuredClone(route.outbound):null;
  if(outbound&&!['vless','trojan','shadowsocks'].includes(outbound.protocol))throw new Error('Invalid encrypted egress');
  if(outbound)outbound.tag='family-egress';
  const selected=outbound?'family-egress':allowDirectForTests?'test-direct':'block';
  return {
    log: {loglevel:'warning'},
    inbounds:[{tag:'family-reality',listen:'0.0.0.0',port:18443,protocol:'vless',settings:{clients:devices.map(d=>({id:d.uuid,flow:'xtls-rprx-vision'})),decryption:'none'},
      streamSettings:{network:'tcp',security:'reality',realitySettings:{show:false,target:`${config.serverName}:443`,xver:0,serverNames:[config.serverName],privateKey:config.privateKey,shortIds:[config.shortId]}},
      sniffing:{enabled:true,destOverride:['http','tls'],routeOnly:true}},
      // TLS terminates at the existing HTTPS nginx. This port is container-network only.
      {tag:'family-ws',listen:'0.0.0.0',port:10000,protocol:'vless',settings:{clients:devices.map(d=>({id:d.uuid})),decryption:'none'},
        streamSettings:{network:'ws',security:'none',wsSettings:{path:'/v1/transport'}},
        sniffing:{enabled:true,destOverride:['http','tls'],routeOnly:true}}],
    outbounds:[{tag:'block',protocol:'blackhole'},...(outbound?[outbound]:allowDirectForTests?[{tag:'test-direct',protocol:'freedom',settings:{domainStrategy:'UseIP'}}]:[])],
    routing:{domainStrategy:'IPIfNonMatch',rules:[
      {type:'field',domain:['localhost','domain:local','domain:internal'],outboundTag:'block'},
      {type:'field',ip:['0.0.0.0/8','10.0.0.0/8','100.64.0.0/10','127.0.0.0/8','169.254.0.0/16','172.16.0.0/12','192.168.0.0/16','224.0.0.0/4','240.0.0.0/4','::/128','::1/128','fc00::/7','fe80::/10','ff00::/8'],outboundTag:'block'},
      {type:'field',network:'tcp,udp',outboundTag:selected}]}
  };
}

export class Gateway {
  constructor(config, dataDir, binary='/usr/local/bin/xray',options={}) { this.config=config; this.dir=dataDir; this.binary=binary; this.options=options;this.child=null;this.ready=false;this.listening=false;this.revision=0;this.applied=0;this.runner=null;this.closed=false; }
  async stopChild() {
    this.ready=false;this.listening=false;
    const child=this.child; if (!child) return;
    if (child.exitCode === null) {
      const exited=new Promise(resolve=>child.once('exit',resolve));
      child.kill('SIGTERM');
      await Promise.race([exited,delay(3000)]);
      if (child.exitCode === null && child.signalCode === null) { child.kill('SIGKILL'); await exited; }
    }
    if(this.child===child)this.child=null;
  }
  async stop(){this.closed=true;this.revision++;await this.stopChild();try{await this.runner;}catch{}}
  reload(devices,route=null){
    if(this.closed)return Promise.reject(new Error('Gateway stopped'));
    this.devicesSource=devices;this.routeSource=route;this.revision++;this.ready=false;
    // Revoke and route failure interrupt the old gateway immediately, even during a queued reload.
    if(this.child&&this.child.exitCode===null)this.child.kill('SIGTERM');
    return this.schedule();
  }
  schedule(){
    if(this.runner)return this.runner;
    this.runner=this.reconcile().finally(()=>{this.runner=null;if(!this.closed&&this.applied!==this.revision)return this.schedule();});
    return this.runner;
  }
  async reconcile(){
    while(!this.closed){
      const revision=this.revision;await this.stopChild();
      if(this.closed)return;if(revision!==this.revision)continue;
      const devices=typeof this.devicesSource==='function'?this.devicesSource():this.devicesSource;
      const route=typeof this.routeSource==='function'?this.routeSource():this.routeSource;
      this.applied=revision;
      try{await this.launch(devices,route,revision);}catch(e){await this.stopChild();if(revision===this.revision)throw e;}
      if(revision===this.revision)return;
    }
  }
  async launch(devices,route,revision) {
    const path=`${this.dir}/xray.json`;
    writeFileSync(`${path}.tmp`,JSON.stringify(xrayConfig(this.config,devices,route,this.options)),{mode:0o600}); renameSync(`${path}.tmp`,path);
    const child=this.child=spawn(this.binary,['run','-config',path],{stdio:['ignore','ignore','ignore']});
    let failed=false;
    const ended=()=>{failed=true;if(this.child===child){this.ready=false;this.listening=false;}};
    child.once('error',ended);child.once('exit',ended);
    for (let i=0;i<40;i++) {
      await delay(100);
      if(this.closed||revision!==this.revision)return;
      if (failed) throw new Error('Gateway failed to start');
      const listening=await Promise.all([18443,10000].map(port=>new Promise(resolve=>{const s=createConnection({host:'127.0.0.1',port});s.once('connect',()=>{s.destroy();resolve(true);});s.once('error',()=>resolve(false));s.setTimeout(100,()=>{s.destroy();resolve(false);});})));
      if (listening.every(Boolean)) {this.listening=true;this.ready=Boolean(route?.outbound||this.options.allowDirectForTests);return;}
    }
    throw new Error('Gateway readiness timed out');
  }
  client(device) {
    return {protocol:'vless',host:this.config.host,port:18443,uuid:device.uuid,security:'reality',public_key:this.config.publicKey,short_id:this.config.shortId,server_name:this.config.serverName,flow:'xtls-rprx-vision',fingerprint:'chrome'};
  }
  clientFallbacks(device) {
    return [{protocol:'vless',host:this.config.host,port:8443,uuid:device.uuid,security:'tls',transport:'ws',path:'/v1/transport',server_name:this.config.host,flow:'',fingerprint:''}];
  }
}
