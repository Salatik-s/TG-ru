// Adapted from the owner's 24projects VPN parser. FamilyGram permits BS nodes only as an explicitly ranked fallback.
import { isIP } from 'node:net';
import { createHash } from 'node:crypto';

const MAX_BYTES = 2 * 1024 * 1024;
const UUID = /^(?!00000000-0000-0000-0000-000000000000$)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const KNOWN_PROTOCOLS = new Set(['vless', 'trojan', 'ss', 'vmess', 'hysteria2', 'hy2', 'tuic']);
const FINGERPRINTS = new Set(['chrome', 'firefox', 'edge', 'safari', '360', 'qq', 'ios', 'android', 'random', 'randomized']);
const SS_METHODS = new Set(['aes-128-gcm', 'aes-192-gcm', 'aes-256-gcm', 'chacha20-ietf-poly1305', 'xchacha20-ietf-poly1305', '2022-blake3-aes-128-gcm', '2022-blake3-aes-256-gcm', '2022-blake3-chacha20-poly1305']);
const PARAMETERS = new Set(['type', 'security', 'encryption', 'flow', 'sni', 'serverName', 'fp', 'pbk', 'sid', 'alpn', 'host', 'path', 'serviceName', 'mode', 'headerType', 'spx', 'allowInsecure', 'insecure', 'packetEncoding', 'packet_encoding']);

// Error messages and codes are constant. Never attach input URIs, URLs or provider bodies.
export class VPNError extends Error {
  constructor(code, stats) { super(`VPN configuration unavailable (${code}).`); this.name = 'VPNError'; this.code = code; if (stats) this.stats = stats; }
}
const fail = code => { throw new VPNError(code); };
const decode = text => { try { return decodeURIComponent(text); } catch { return fail('INVALID_URI'); } };
function base64(text) {
  const compact = text.replace(/\s/g, '');
  if (!compact || !/^[\w+/=-]+$/.test(compact) || compact.length % 4 === 1) fail('INVALID_BASE64');
  const bytes = Buffer.from(compact, 'base64');
  if (bytes.toString('base64url') !== compact.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')) fail('INVALID_BASE64');
  const decoded = bytes.toString('utf8');
  if (decoded.includes('\ufffd') || decoded.includes('\0')) fail('INVALID_BASE64');
  return decoded;
}

export function isBlockedLabel(value) {
  let label = String(value).normalize('NFKC').replace(/[\p{Cf}\p{M}]/gu, '').toLowerCase();
  for (let i = 0; i < 2 && /%[\da-f]{2}/i.test(label); i++) { try { label = decodeURIComponent(label); } catch { break; } }
  label = label.normalize('NFKC').replace(/[\p{Cf}\p{M}]/gu, '').toLowerCase();
  return /(?:бс|white[\s_.-]*list|allow[\s_.-]*list|whitelist|wlist|bel(?:ye|iy|ie|yy)[\s_.-]*spisk|бел(?:ые|ый|ого|ых|ая|ое|ую)|бел[\p{L}\s_.-]*(?:спис|лист)|(?:^|[^\p{L}])(?:б[\s._-]*с|b[\s._-]*s|w[\s._-]*l)|(?:^|[^\p{L}])white(?:$|[^\p{L}])|обход\s*глушилок|(?:^|[^\p{L}])lte(?:$|[^\p{L}])|❌\s*рф|авто\s*\|)/u.test(label);
}

function safeServer(hostname) {
  const host = hostname.replace(/^\[|\]$/g, '').toLowerCase();
  if (!host || host.length > 253 || /[\s\0/@\\]/.test(host)) fail('INVALID_SERVER');
  const ip = isIP(host);
  if (ip === 4) {
    const [a, b] = host.split('.').map(Number);
    if ([0, 10, 127].includes(a) || a >= 224 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127)) fail('UNSAFE_ENDPOINT');
  } else if (ip === 6) {
    if (/^(?:::(?:1)?$|f[cd]|fe[89ab]|ff|::ffff:)/i.test(host)) fail('UNSAFE_ENDPOINT');
  } else if (!host.includes('.') || host.endsWith('.') || /^(?:0x[\da-f]+|\d+)(?:\.(?:0x[\da-f]+|\d+)){0,3}$/i.test(host) || /(?:^|\.)(?:localhost|local|internal)$/.test(host) || !/^[a-z\d.-]+$/.test(host) || host.split('.').some(part => !part || !/^[a-z\d](?:[a-z\d-]*[a-z\d])?$/.test(part))) fail('INVALID_SERVER');
  return host;
}
function endpoint(url) {
  const port = Number(url.port);
  if (!Number.isInteger(port) || port < 1 || port > 65535) fail('INVALID_PORT');
  return { server: safeServer(url.hostname), server_port: port, connect_timeout: '15s' };
}
function parameters(url) {
  const q = url.searchParams;
  for (const key of q.keys()) {
    if (!PARAMETERS.has(key) || q.getAll(key).length !== 1) fail('UNSUPPORTED_PARAMETERS');
    if (q.get(key).length > 4096 || /[\r\n\0]/.test(q.get(key))) fail('INVALID_URI');
  }
  for (const key of ['insecure', 'allowInsecure']) if (q.has(key) && !['', '0', 'false'].includes(q.get(key))) fail('UNSAFE_TLS');
  if (q.has('headerType') && !['', 'none'].includes(q.get('headerType'))) fail('UNSUPPORTED_TRANSPORT');
  if (q.has('spx') && (!q.get('spx').startsWith('/') || q.get('spx').length > 1024)) fail('UNSUPPORTED_PARAMETERS');
  return q;
}
function tls(q, protocol) {
  const security = q.get('security') || (protocol === 'trojan' ? 'tls' : 'none');
  if (!['none', 'tls', 'reality'].includes(security) || (protocol === 'trojan' && security !== 'tls')) fail('UNSUPPORTED_TLS');
  if (security === 'none') {
    if (['sni', 'serverName', 'fp', 'pbk', 'sid', 'alpn'].some(key => q.get(key))) fail('UNSUPPORTED_PARAMETERS');
    return undefined;
  }
  const result = { enabled: true };
  if (q.get('sni') && q.get('serverName') && q.get('sni') !== q.get('serverName')) fail('INVALID_TLS');
  const sni = q.get('sni') || q.get('serverName');
  if (sni) { if (/[\s\0/@\\]/.test(sni) || sni.length > 253) fail('INVALID_TLS'); result.server_name = sni; }
  if (q.get('alpn')) {
    result.alpn = q.get('alpn').split(',');
    if (result.alpn.length > 10 || result.alpn.some(value => !/^[\w./-]{1,40}$/.test(value))) fail('INVALID_TLS');
  }
  const fp = q.get('fp');
  if (fp) { if (!FINGERPRINTS.has(fp)) fail('UNSUPPORTED_FINGERPRINT'); result.utls = { enabled: true, fingerprint: fp }; }
  if (security === 'reality') {
    const publicKey = q.get('pbk'), shortId = q.get('sid') || '';
    if (!publicKey || !/^[\w-]{43}$/.test(publicKey) || Buffer.from(publicKey, 'base64url').length !== 32 || !/^(?:[\da-f]{2}){0,8}$/i.test(shortId)) fail('INVALID_REALITY');
    // Reality requires uTLS. Use only an explicitly supplied, supported fingerprint.
    if (!fp) fail('UNSUPPORTED_FINGERPRINT');
    result.reality = { enabled: true, public_key: publicKey, short_id: shortId, ...(q.has('spx') ? { spider_x: q.get('spx') } : {}) };
  } else if (q.has('pbk') || q.has('sid')) fail('INVALID_TLS');
  return result;
}
function transport(q) {
  const type = q.get('type') || 'tcp';
  if (['tcp', 'none'].includes(type)) {
    if (['host', 'path', 'serviceName', 'mode'].some(key => q.get(key))) fail('UNSUPPORTED_TRANSPORT');
    return undefined;
  }
  if (type === 'ws') {
    const result = { type: 'ws', path: q.get('path') || '/' };
    if (q.get('host')) result.headers = { Host: q.get('host') };
    if (q.has('serviceName') || q.has('mode')) fail('UNSUPPORTED_TRANSPORT');
    return result;
  }
  if (type === 'grpc') {
    if (q.get('mode') && q.get('mode') !== 'gun') fail('UNSUPPORTED_TRANSPORT');
    if (q.get('host') || q.get('path')) fail('UNSUPPORTED_TRANSPORT');
    return { type: 'grpc', service_name: q.get('serviceName') || '' };
  }
  if (type === 'httpupgrade') {
    if (q.has('serviceName') || q.has('mode')) fail('UNSUPPORTED_TRANSPORT');
    return { type: 'httpupgrade', ...(q.get('host') ? { host: q.get('host') } : {}), path: q.get('path') || '/' };
  }
  // XHTTP, SplitHTTP, KCP and proprietary Happ transports are not approximated.
  fail('UNSUPPORTED_TRANSPORT');
}
function shadowsocks(uri) {
  let value = uri.slice(5).split('#')[0];
  if (!value.includes('@')) {
    const legacy = base64(value), at = legacy.lastIndexOf('@'), colon = legacy.indexOf(':');
    if (colon < 1 || at < colon + 2) fail('INVALID_URI');
    value = encodeURIComponent(legacy.slice(0, colon)) + ':' + encodeURIComponent(legacy.slice(colon + 1, at)) + '@' + legacy.slice(at + 1);
  }
  let url; try { url = new URL('ss://' + value); } catch { return fail('INVALID_URI'); }
  if (url.search || (url.pathname && url.pathname !== '/')) fail('UNSUPPORTED_PARAMETERS');
  let auth = decode(url.username);
  if (!url.password) auth = base64(auth); else auth += ':' + decode(url.password);
  const at = auth.indexOf(':'), method = auth.slice(0, at), password = auth.slice(at + 1);
  if (at < 1 || !password || !SS_METHODS.has(method)) fail('UNSUPPORTED_CIPHER');
  return { type: 'shadowsocks', ...endpoint(url), method, password };
}

export function parseNodeUri(uri, { allowBs = false } = {}) {
  if (typeof uri !== 'string' || uri.length > 16384 || /[\r\n\0]/.test(uri)) fail('INVALID_URI');
  const protocol = uri.match(/^([a-z][\w+.-]*):\/\//i)?.[1].toLowerCase();
  if (!['vless', 'trojan', 'ss'].includes(protocol)) fail('UNSUPPORTED_PROTOCOL');
  const label = decode(uri.split('#').slice(1).join('#'));
  if (!allowBs && isBlockedLabel(label)) fail('EXCLUDED_BS');
  if (protocol === 'ss') return shadowsocks(uri);
  let url; try { url = new URL(uri); } catch { return fail('INVALID_URI'); }
  if (url.pathname && url.pathname !== '/') fail('UNSUPPORTED_PARAMETERS');
  const q = parameters(url), security = tls(q, protocol), wire = transport(q);
  const result = { type: protocol, ...endpoint(url) };
  if (protocol === 'vless') {
    const uuid = decode(url.username);
    if (url.password || !UUID.test(uuid)) fail('INVALID_UUID');
    if (q.get('encryption') && q.get('encryption') !== 'none') fail('UNSUPPORTED_ENCRYPTION');
    result.uuid = uuid;
    if (q.get('flow')) {
      if (q.get('flow') !== 'xtls-rprx-vision' || wire || !security) fail('UNSUPPORTED_FLOW');
      result.flow = q.get('flow');
    }
    if (q.has('packetEncoding') && q.has('packet_encoding')) fail('UNSUPPORTED_PARAMETERS');
    const packet = q.get('packetEncoding') || q.get('packet_encoding');
    if (packet) { if (!['xudp', 'packetaddr'].includes(packet)) fail('UNSUPPORTED_PARAMETERS'); result.packet_encoding = packet; }
  } else {
    result.password = decode(url.username) + (url.password ? ':' + decode(url.password) : '');
    if (!result.password) fail('INVALID_PASSWORD');
    if (['flow', 'encryption', 'packetEncoding', 'packet_encoding'].some(key => q.has(key))) fail('UNSUPPORTED_PARAMETERS');
  }
  if (security) result.tls = security;
  if (wire) result.transport = wire;
  return result;
}

export function nodeTag(node) {
  const { tag, ...settings } = node;
  return 'node-' + createHash('sha256').update(JSON.stringify(settings)).digest('hex').slice(0, 24);
}

export function toXrayOutbound(node) {
  if (!node || typeof node.server !== 'string' || !Number.isInteger(node.server_port) || node.server_port < 1 || node.server_port > 65535) fail('INVALID_NODES');
  const settings = { address: safeServer(node.server), port: node.server_port };
  const result = { tag: node.tag || nodeTag(node), protocol: node.type, settings, streamSettings: { method: 'raw', security: 'none' } };
  if (node.type === 'vless') {
    if (!node.tls) fail('UNSAFE_TLS');
    if (!UUID.test(node.uuid)) fail('INVALID_UUID');
    settings.id = node.uuid; settings.encryption = 'none';
    if (node.flow) settings.flow = node.flow;
    // XUDP is intrinsic to current Xray VLESS; packetaddr is a different dialect.
    if (node.packet_encoding === 'packetaddr') fail('UNSUPPORTED_PARAMETERS');
  } else if (node.type === 'trojan') settings.password = node.password;
  else if (node.type === 'shadowsocks') {
    if (node.method === 'aes-192-gcm') fail('UNSUPPORTED_CIPHER');
    settings.method = node.method; settings.password = node.password;
  } else fail('UNSUPPORTED_PROTOCOL');
  if (node.tls) {
    const secure = node.tls;
    if (secure.reality) {
      result.streamSettings.security = 'reality';
      result.streamSettings.realitySettings = { serverName: secure.server_name || node.server, fingerprint: secure.utls.fingerprint, password: secure.reality.public_key, shortId: secure.reality.short_id, ...(secure.reality.spider_x !== undefined ? { spiderX: secure.reality.spider_x } : {}) };
    } else {
      result.streamSettings.security = 'tls';
      result.streamSettings.tlsSettings = { serverName: secure.server_name || node.server, allowInsecure: false, ...(secure.alpn ? { alpn: secure.alpn } : {}), ...(secure.utls ? { fingerprint: secure.utls.fingerprint } : {}) };
    }
  }
  if (node.transport) {
    const transport = node.transport;
    if (transport.type === 'ws') { result.streamSettings.method = 'websocket'; result.streamSettings.wsSettings = { path: transport.path, ...(transport.headers ? { host: transport.headers.Host } : {}) }; }
    else if (transport.type === 'grpc') { result.streamSettings.method = 'grpc'; result.streamSettings.grpcSettings = { serviceName: transport.service_name, multiMode: false }; }
    else if (transport.type === 'httpupgrade') { result.streamSettings.method = 'httpupgrade'; result.streamSettings.httpupgradeSettings = { path: transport.path, ...(transport.host ? { host: transport.host } : {}) }; }
    else fail('UNSUPPORTED_TRANSPORT');
    if (node.tls?.reality && !['grpc', 'raw'].includes(result.streamSettings.method)) fail('UNSUPPORTED_TRANSPORT');
  }
  return result;
}
