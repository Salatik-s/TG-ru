// Builds a private native-outbound snapshot without fetching a subscription or logging credentials.
import {isBlockedLabel,nodeTag,parseNodeUri,toXrayOutbound} from './vpn-nodes.mjs';

export function buildSnapshot({raw='',cached=null,selection=null}={}) {
  const candidates=new Map(),stats={regular:0,bs:0,invalid:0,duplicates:0,unsupported:0};
  const add=(node,tier)=>{
    const tag=nodeTag(node),outbound=toXrayOutbound({...node,tag});
    if(candidates.has(tag)){stats.duplicates++;return;}
    if(candidates.size>=256){stats.unsupported++;return;}
    if(!['vless','trojan','shadowsocks'].includes(outbound.protocol))throw new Error('UNSUPPORTED_NATIVE_OUTBOUND');
    candidates.set(tag,{tag,tier,outbound});stats[tier]++;
  };
  if(cached?.version===1&&Array.isArray(cached.nodes)) {
    for(const node of cached.nodes.slice(0,256)) {
      try {if(node?.tag!==nodeTag(node))throw new Error('INVALID_CACHED_NODE');add(node,'regular');}
      catch {stats.invalid++;}
    }
  }
  if(typeof raw==='string'&&Buffer.byteLength(raw)<=2*1024*1024) {
    let text=raw.replace(/^\ufeff/,'').trim();
    if(text&&!/^[a-z][\w+.-]*:\/\//im.test(text)&&/^[\w+/=\s-]+$/.test(text)) {
      const decoded=Buffer.from(text.replace(/\s/g,''),'base64').toString('utf8');
      if(!decoded.includes('\ufffd')&&!decoded.includes('\0'))text=decoded;
    }
    for(const line of text.split(/\r?\n/)) {
      const uri=line.trim();if(!uri||uri.startsWith('#'))continue;
      try {
        const label=decodeURIComponent(uri.split('#').slice(1).join('#'));
        const tier=isBlockedLabel(label)?'bs':'regular';
        add(parseNodeUri(uri,{allowBs:true}),tier);
      } catch(error) {if(error?.code?.startsWith('UNSUPPORTED_'))stats.unsupported++;else stats.invalid++;}
    }
  }
  const preferred=selection?.nodeTag;
  return {snapshot:{version:1,preferredTag:typeof preferred==='string'&&candidates.has(preferred)?preferred:null,candidates:[...candidates.values()].sort((a,b)=>(a.tier==='bs')-(b.tier==='bs'))},stats};
}
