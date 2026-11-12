import { readFileSync } from 'node:fs';
import { event, listenConfig, readOnly, respond, serve, status } from '../foundation/http.mjs';

const htmlEntities = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
const escapeHtml = (text) => text.replace(/[&<>"']/g, (character) => htmlEntities[character]);

try {
  const config = listenConfig(process.env, 'TG_RU_WEB', 8082);
  const strings = JSON.parse(readFileSync(new URL('../i18n/ru.json', import.meta.url), 'utf8'));
  const css = readFileSync(new URL('./preview.css', import.meta.url), 'utf8');
  const page = `<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(strings.site.title)}</title>
  <link rel="stylesheet" href="/preview.css">
</head>
<body>
  <header>${escapeHtml(strings.site.title)}</header>
  <main>
    <h1>${escapeHtml(strings.home.title)}</h1>
    <p>${escapeHtml(strings.home.preview)}</p>
  </main>
  <footer>${escapeHtml(strings.site.disclaimer)}</footer>
</body>
</html>`;

  await serve(config, (request, response) => {
    switch (request.url) {
      case '/health':
        if (readOnly(request, response)) status(response, 200, 'ok');
        break;
      case '/':
        if (readOnly(request, response)) respond(response, 200, 'text/html; charset=utf-8', page);
        break;
      case '/preview.css':
        if (readOnly(request, response)) respond(response, 200, 'text/css; charset=utf-8', css);
        break;
      default:
        status(response, 404, 'not_found');
    }
  });
  event('web_ready');
} catch {
  event('web_start_failed', true);
  process.exitCode = 1;
}
