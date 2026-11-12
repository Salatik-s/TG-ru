import { once } from 'node:events';
import { createServer } from 'node:http';
import { isIP } from 'node:net';

export function listenConfig(env, prefix, defaultPort) {
  const host = env[`${prefix}_HOST`] ?? '127.0.0.1';
  const rawPort = env[`${prefix}_PORT`] ?? String(defaultPort);
  const port = Number(rawPort);
  if (!isIP(host) || !/^\d+$/.test(rawPort) || port < 1 || port > 65535) {
    throw new Error('invalid_listen_configuration');
  }
  return { host, port };
}

export function respond(response, status, contentType, body) {
  response.writeHead(status, {
    'Content-Type': contentType,
    'Cache-Control': 'no-store',
    'Content-Security-Policy': "default-src 'none'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
  });
  response.end(body);
}

export function status(response, code, value) {
  respond(response, code, 'application/json; charset=utf-8', JSON.stringify({ status: value }));
}

export function readOnly(request, response) {
  if (request.method === 'GET' || request.method === 'HEAD') return true;
  response.setHeader('Allow', 'GET, HEAD');
  status(response, 405, 'method_not_allowed');
  return false;
}

export function event(code, failed = false) {
  const record = JSON.stringify({ time: new Date().toISOString(), level: failed ? 'error' : 'info', code });
  if (failed) console.error(record);
  else console.log(record);
}

export async function serve(config, handle, close = () => {}) {
  const server = createServer({ requestTimeout: 10_000, headersTimeout: 5_000 }, handle);
  try {
    server.listen(config);
    await once(server, 'listening');
  } catch (error) {
    close();
    throw error;
  }

  function stop() {
    process.removeListener('SIGINT', stop);
    process.removeListener('SIGTERM', stop);
    server.close(close);
  }

  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
}
