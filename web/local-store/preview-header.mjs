export const previewBrandIconUrl = '/tg-ru-mark.png';
export const previewBrand = `<a class="preview-header-brand" href="/" aria-label="TG-ru, главная"><img src="${previewBrandIconUrl}" width="34" height="34" alt="">TG-ru</a>`;

const publicLinks = [
  ['/#platforms', 'Платформы'],
  ['/#how', 'Как это работает'],
  ['/#visibility', 'Что мы видим'],
  ['/#price', 'Подписка'],
];

const accountLinks = [
  ['/cabinet', 'Обзор'],
  ['/devices', 'Устройства'],
  ['/downloads', 'Загрузки'],
  ['/help', 'Помощь'],
];

export function previewHeader({ session, route, ownerView = false }) {
  const signedIn = Boolean(session?.account);
  const links = signedIn ? accountLinks : publicLinks;
  const navigation = links.map(([href, label]) =>
    `<a href="${href}"${href === route ? ' aria-current="page"' : ''}>${label}</a>`).join('');
  const actions = ownerView ? '' : signedIn
    ? `<form method="post" action="/form/logout"><input type="hidden" name="csrf" value="${session.csrf}">
        <button class="preview-header-button" type="submit">Выйти</button></form>`
    : `<a class="preview-header-button preview-header-login" href="/login"${route === '/login' ? ' aria-current="page"' : ''}>Войти</a>
       <a class="preview-header-button preview-header-primary" href="/register"${route === '/register' ? ' aria-current="page"' : ''}>Создать аккаунт</a>`;
  return `<header class="preview-header${signedIn ? ' preview-header-signed-in' : ''}"><div class="preview-header-bar">
    ${previewBrand}
    <nav aria-label="Основная навигация">${navigation}</nav>
    ${actions ? `<div class="preview-header-actions">${actions}</div>` : ''}
  </div></header>`;
}
