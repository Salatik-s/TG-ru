import { readFileSync } from 'node:fs';
import { handleStorePages } from './pages.mjs';
import { previewBrandIconUrl, previewHeader } from './preview-header.mjs';
import { previewFooter } from './preview-footer.mjs';

const read = (name) => readFileSync(new URL(name, import.meta.url), 'utf8');
const template = read('./preview-home.html');
const css = read('./preview-home.css');
const policy = "default-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; object-src 'none'";

export async function handlePreviewPages(request, response, context) {
  const route = new URL(request.url, 'http://localhost').pathname;
  if (!['GET', 'HEAD'].includes(request.method) || !['/', '/preview-home.css'].includes(route)) {
    return handleStorePages(request, response, context);
  }
  if (route === '/preview-home.css') {
    response.writeHead(200, { 'Content-Type': 'text/css; charset=utf-8' });
    response.end(request.method === 'HEAD' ? undefined : css);
    return true;
  }
  const session = await context.getSession();
  const { account } = session;
  const accountHref = account ? '/cabinet' : '/register';
  const accountLabel = account ? 'Открыть кабинет' : 'Создать аккаунт';
  const fields = {
    header: previewHeader({ session, route: '/' }),
    footer: previewFooter,
    primaryAction: `<a class="btn shine big" href="${accountHref}">${accountLabel}</a>`,
    secondaryAction: account ? '' : '<a class="btn light big" href="/login">Войти</a>',
    demoHref: accountHref,
  };
  const body = template.replace(/\{\{(\w+)\}\}/g, (match, key) => fields[key] ?? match);
  response.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Security-Policy': policy,
  });
  response.end(request.method === 'HEAD' ? undefined : `<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="light"><title>TG-ru — Telegram через VPN-шлюз</title>
<link rel="icon" type="image/png" sizes="736x736" href="${previewBrandIconUrl}">
<link rel="stylesheet" href="/preview-home.css"><link rel="stylesheet" href="/preview-header.css"></head><body>${body}</body></html>`);
  return true;
}
