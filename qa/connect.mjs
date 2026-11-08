// QA-only CONNECT bridge: one destination, loopback listener, no HTTP relay.
import { createServer } from 'node:http';
import { createConnection } from 'node:net';
const server = createServer((_req, res) => { res.writeHead(403); res.end(); });
server.on('connect', (req, client, head) => {
  if (req.url !== '24projects.ru:8443') { client.end('HTTP/1.1 403 Forbidden\r\n\r\n'); return; }
  const upstream = createConnection({host:'127.0.0.1', port:8443});
  upstream.once('connect', () => {
    client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    if (head.length) upstream.write(head);
    client.pipe(upstream); upstream.pipe(client);
  });
  upstream.on('error', () => client.destroy());
  client.on('error', () => upstream.destroy());
  client.once('close', () => upstream.destroy());
  upstream.once('close', () => client.destroy());
});
server.listen(19090, '127.0.0.1', () => console.log('QA CONNECT bridge ready on loopback; only the local QA API is reachable.'));
process.on('SIGTERM', () => server.close());
process.on('SIGINT', () => server.close());
