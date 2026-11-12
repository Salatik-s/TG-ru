import { readFileSync } from 'node:fs';

const staticFiles = new Map([
  ['/store.css', ['text/css; charset=utf-8', readFileSync(new URL('./store.css', import.meta.url))]],
  ['/fonts/inter-cyrillic.woff2', ['font/woff2', readFileSync(new URL('./inter-cyrillic.woff2', import.meta.url))]],
  ['/fonts/inter-latin.woff2', ['font/woff2', readFileSync(new URL('./inter-latin.woff2', import.meta.url))]],
  ['/store.mjs', ['text/javascript; charset=utf-8', readFileSync(new URL('./store.mjs', import.meta.url))]],
]);
const strings = JSON.parse(readFileSync(new URL('../../i18n/ru.json', import.meta.url), 'utf8'));
const dateTime = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Europe/Moscow' });
const plural = new Intl.PluralRules('ru');
const pages = new Set(['/', '/register', '/login', '/recover', '/cabinet', '/devices', '/downloads', '/help',
  '/checkout', '/visibility', '/terms', '/privacy', '/refunds', '/compatibility']);
const protectedPages = new Set(['/cabinet', '/devices', '/checkout']);
const forms = new Map([
  ['/form/register', 'register'], ['/form/login', 'login'], ['/form/recover', 'recover'], ['/form/logout', 'logout'],
  ['/form/trial', 'trial'], ['/form/devices/revoke', 'devices/revoke'],
]);
const supportLink = 'https://t.me/prince_skazki';

const paths = {
  phone: '<rect x="7" y="3" width="10" height="18" rx="2"/><path d="M11 17h2"/>',
  relay: '<rect x="4" y="4" width="16" height="6" rx="1"/><rect x="4" y="14" width="16" height="6" rx="1"/><path d="M8 7h.01M8 17h.01m4-10h4m-4 10h4"/>',
  chat: '<path d="M20 11.5a7.5 7.5 0 0 1-7.5 7.5H5l1.3-3.6A7.5 7.5 0 1 1 20 11.5Z"/><path d="M9 11.5h6"/>',
  eye: '<path d="M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6S2 12 2 12Z"/><circle cx="12" cy="12" r="2.5"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4m-4 4v2"/>',
  arrow: '<path d="M5 12h14m-6-6 6 6-6 6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  time: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l2.5 1.5"/>',
  info: '<circle cx="12" cy="12" r="8"/><path d="M12 11v5m0-8h.01"/>',
};
const icon = (name) => `<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${paths[name]}</svg>`;
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const formatDate = (seconds) => dateTime.format(new Date(seconds * 1000));
const input = (name, value) => `<input type="hidden" name="${name}" value="${escapeHtml(value)}">`;
const csrf = (session) => input('csrf', session.csrf);
const action = (href, text, kind = 'secondary') => `<a class="button ${kind}" href="${href}">${text}</a>`;
const formButton = (text, kind = 'primary') => `<button type="submit" class="button ${kind}">${text}</button>`;
const badge = (text, tone) => `<span class="badge ${tone}">${icon(tone === 'success' ? 'check' : 'time')}${text}</span>`;

const errorMessages = {
  invalid_login: 'Логин должен содержать 3–24 символа: латиницу, цифры или _.',
  invalid_password: 'Введите пароль.',
  password_too_short: 'Пароль слишком короткий.',
  weak_password: 'Пароль слишком простой: выберите другой.',
  login_taken: 'Этот логин занят.',
  terms_required: 'Нужно принять оферту.',
  invalid_credentials: 'Неверный логин или пароль.',
  invalid_recovery_code: 'Код восстановления не подошёл. Проверьте его и повторите.',
  rate_limited: 'Слишком много попыток. Подождите немного и повторите.',
  too_many_attempts: 'Слишком много попыток. Подождите немного и повторите.',
  csrf: 'Срок действия формы истёк. Повторите действие.',
  csrf_invalid: 'Срок действия формы истёк. Повторите действие.',
  unauthorized: 'Срок входа истёк. Войдите в кабинет ещё раз.',
  authentication_required: 'Срок входа истёк. Войдите в кабинет ещё раз.',
  trial_already_used: 'Пробный период этого аккаунта уже начался.',
  trial_unavailable: 'Бесплатная проба уже использована или недоступна для этого аккаунта.',
  invalid_request: 'Проверьте данные и повторите.',
  device_not_found: 'Устройство уже отвязано.',
};

function errorNotice(error) {
  if (!error) return '';
  return `<p class="notice error" role="alert" tabindex="-1">${icon('info')}${escapeHtml(errorMessages[error] ?? 'Не удалось выполнить действие. Проверьте данные и повторите.')}</p>`;
}

function layout(title, content, { session, route, marketing = false } = {}) {
  const account = session?.account;
  const navLink = (href, name) => `<a href="${href}"${route === href ? ' aria-current="page"' : ''}>${name}</a>`;
  const nav = marketing || !account
    ? '<a href="/#how">Как это работает</a>' + navLink('/visibility', 'Что мы видим') + '<a href="/#price">Цена</a>'
    : navLink('/cabinet', 'Обзор') + navLink('/devices', 'Устройства') + navLink('/downloads', 'Загрузки') + navLink('/help', 'Помощь');
  const sessionLinks = account
    ? (marketing ? action('/cabinet', 'Кабинет', 'primary') : `<form method="post" action="/form/logout">${csrf(session)}${formButton('Выйти', 'ghost')}</form>`)
    : `${route === '/login' ? '' : action('/login', 'Войти', 'ghost')}${route === '/register' ? '' : action('/register', 'Создать аккаунт', 'primary')}`;
  return `<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="description" content="TG-ru — независимый Android-клиент Telegram с подпиской на доступ к шлюзу.">
  <meta name="color-scheme" content="dark light">
  <title>${escapeHtml(title)} · TG-ru</title>
  <script src="/store.mjs"></script>
  <link rel="stylesheet" href="/store.css">
</head>
<body>
  <a class="skip" href="#content">Перейти к содержимому</a>
  <header class="site-header">
    <div class="wrap topbar">
      <a class="brand" href="/" aria-label="TG-ru, главная"><span class="brand-icon" aria-hidden="true">TG</span>TG-ru</a>
      <nav aria-label="Основная навигация">${nav}</nav>
      <div class="session-links">${sessionLinks}</div>
    </div>
  </header>
  <main id="content" tabindex="-1">${content}</main>
  <footer class="site-footer">
    <div class="wrap footer-inner">
      <div class="footer-main">
        <p>${strings.site.disclaimer}</p>
      </div>
      <nav aria-label="Информация о сервисе">
        <a href="/terms">Оферта</a><a href="/privacy">Конфиденциальность</a><a href="/visibility">Что мы видим</a><a href="/refunds">Возвраты</a><a href="/compatibility">Политика совместимости</a>
      </nav>
      <div class="footer-meta">
        <label class="theme-control" hidden>Тема <select id="theme" aria-label="Тема оформления"><option value="system">Системная</option><option value="light">Светлая</option><option value="dark">Тёмная</option></select></label>
      </div>
    </div>
  </footer>
</body>
</html>`;
}

function home(session) {
  return layout(strings.home.title, `
<section class="wrap hero">
  <div class="hero-copy">
    <h1>Неофициальный клиент Telegram <span>со своим шлюзом</span></h1>
    <p class="lead">${strings.home.subtitle}.</p>
    <div class="actions">${session.account ? action('/cabinet', 'Открыть кабинет', 'primary') : action('/register', 'Создать аккаунт', 'primary') + action('/login', 'Войти')}</div>
    <p class="trial-note">${icon('time')}Первые 5 часов бесплатно. Банковская карта для пробы не нужна.</p>
  </div>
  <figure class="route-figure" aria-label="Маршрут подключения">
    <figcaption>Маршрут подключения</figcaption>
    <ol class="route">
      <li><span class="route-icon">${icon('phone')}</span><span><strong>Ваш Android</strong><small>Приложение TG-ru</small></span></li>
      <li class="route-current"><span class="route-icon">${icon('relay')}</span><span><strong>Шлюз TG-ru</strong><small>Подключение по подписке</small></span></li>
      <li><span class="route-icon">${icon('chat')}</span><span><strong>Telegram</strong><small>Чаты вашего аккаунта</small></span></li>
    </ol>
    <p>Логин TG-ru нужен для доступа к шлюзу. В Telegram вы входите отдельно в приложении.</p>
  </figure>
</section>
<section id="how" class="wrap band">
  <h2>Как это работает</h2>
  <ol class="steps">
    <li><span>1</span><div><h3>Создайте аккаунт TG-ru</h3><p>Начните бесплатную пробу на 5 часов. За дальнейшим доступом можно обратиться в поддержку.</p></div></li>
    <li><span>2</span><div><h3>Скачайте приложение и введите логин и пароль один раз</h3><p>Так приложение привяжет телефон к вашему аккаунту TG-ru.</p></div></li>
    <li><span>3</span><div><h3>Дальше приложение подтверждает себя само, пароль на телефоне не хранится</h3><p>На следующем экране войдите в Telegram привычным способом.</p></div></li>
  </ol>
</section>
<section class="wrap band">
  <h2>Что мы видим, а чего нет</h2>
  ${visibilitySummary()}
  <a class="text-link" href="/visibility">Подробнее о данных ${icon('arrow')}</a>
</section>
<section id="price" class="wrap band">
  <h2>Цена</h2>
  <div class="price-grid">
    <article class="price-panel">
      <h3>Пробный доступ</h3>
      <p class="price"><strong>0 ₽</strong> на 5 часов</p>
      <p>Один пробный период на аккаунт. Карта не нужна.</p>
      ${action(session.account ? '/cabinet' : '/register', 'Попробовать бесплатно')}
    </article>
    <article class="price-panel price-featured">
      <h3>Доступ к шлюзу</h3>
      <p class="price"><strong>199 ₽</strong> за 30 дней</p>
      <p>До трёх устройств. Без автоматических списаний.</p>
      ${action(session.account ? '/checkout' : '/register', 'Получить доступ', 'primary')}
    </article>
  </div>
  <p class="pricing-note">Онлайн-оплата пока недоступна. <a href="${supportLink}">Написать в поддержку</a>.</p>
</section>
<section class="wrap band">
  <h2>Частые вопросы</h2>
  <div class="faq">
    <details><summary>Это официальный Telegram?</summary><p>Нет. TG-ru — неофициальный клиент. Telegram не связан с этим сервисом.</p></details>
    <details><summary>Читаете ли вы мои переписки?</summary><p>Шлюз пересылает трафик, зашифрованный протоколом Telegram. <a href="/visibility">Подробнее о данных шлюза</a>.</p></details>
    <details><summary>Что будет, когда подписка закончится?</summary><p>Приложение покажет экран продления. Ваши данные Telegram на телефоне не удаляются.</p></details>
    <details><summary>Сколько устройств можно подключить?</summary><p>До трёх.</p></details>
  </div>
</section>`, { session, route: '/', marketing: true });
}

function visibilitySummary() {
  return `<div class="visibility-grid">
    <div><h3>${icon('eye')} Что видно шлюзу</h3><p>IP-адрес подключения, время соединения и объём переданного трафика.</p></div>
    <div><h3>${icon('lock')} Что шлюз не видит</h3><p>Содержимое переписки и файлов, номер телефона, код входа и облачный пароль Telegram.</p></div>
  </div>`;
}

function credentialsPage(route, session, { error, login = '' } = {}) {
  const registering = route === '/register';
  const title = registering ? 'Создать аккаунт' : 'Войти';
  const fieldHint = registering ? '<small id="login-hint">3–24 символа: латиница, цифры, _</small>' : '';
  return layout(title, `
<section class="wrap form-page">
  <form class="form-card" method="post" action="/form/${registering ? 'register' : 'login'}">
    <div class="form-intro"><h1>${title}</h1>
    <p>${registering ? 'Аккаунт TG-ru нужен для доступа к шлюзу. После регистрации можно начать пробу на 5 часов.' : 'Войдите в кабинет TG-ru, чтобы посмотреть срок доступа и подключённые устройства.'}</p></div>
    ${csrf(session)}${errorNotice(error)}
    <div class="field"><label for="login">Логин</label><input id="login" name="login" autocomplete="username" autocapitalize="none" spellcheck="false" required maxlength="24" value="${escapeHtml(login)}" ${registering ? 'minlength="3" pattern="[a-zA-Z0-9_]{3,24}" aria-describedby="login-hint"' : ''}>${fieldHint}</div>
    <div class="field"><label for="password">Пароль</label><div class="password"><input id="password" name="password" type="password" autocomplete="${registering ? 'new-password' : 'current-password'}" required maxlength="256" ${registering ? 'minlength="12" aria-describedby="password-hint"' : ''}><button type="button" class="button ghost" data-password="password" aria-pressed="false" hidden>Показать</button></div>${registering ? '<small id="password-hint">Не короче 12 символов</small><button class="button ghost generate" type="button" data-generate="password" hidden>Сгенерировать пароль</button>' : '<a href="/recover">Забыли пароль?</a>'}</div>
    ${registering ? '<label class="check"><input type="checkbox" name="accepted_terms" value="yes" required><span>Я принимаю <a href="/terms">оферту</a> и <a href="/privacy">политику конфиденциальности</a>.</span></label>' : ''}
    ${formButton(title)}
    <p class="form-alt">${registering ? 'Уже есть аккаунт? <a href="/login">Войти</a>' : 'Нет аккаунта? <a href="/register">Создать аккаунт</a>'}</p>
  </form>
</section>`, { session, route });
}

function recoveryCode(session, code, recovered = false) {
  return layout('Сохраните код восстановления', `
<section class="wrap form-page">
  <div class="form-intro"><h1>Сохраните код восстановления</h1><p>${recovered ? 'Пароль изменён. Мы завершили все сессии на сайте. ' : ''}Этот код показывается один раз. Без него пароль восстановить нельзя.</p></div>
  <div class="form-card">
    <div class="field"><label for="recovery-code">Код восстановления</label><input id="recovery-code" class="recovery-value" value="${escapeHtml(code)}" readonly spellcheck="false" autocomplete="off"></div>
    <div class="actions"><button class="button secondary" type="button" data-copy="recovery-code" hidden>Скопировать</button><button class="button ghost" type="button" data-save="recovery-code" hidden>Скачать .txt</button></div>
    <form method="get" action="${recovered ? '/login' : '/cabinet'}" class="confirm-form">
      <label class="check"><input type="checkbox" required><span>Я сохранил код</span></label>
      ${formButton('Продолжить')}
    </form>
  </div>
</section>`, { session, route: '/register' });
}

function recover(session, error) {
  return layout('Восстановить пароль', `
<section class="wrap form-page">
  <div class="form-intro"><h1>Восстановить пароль</h1><p>Введите код, который вы сохранили при регистрации, и задайте новый пароль.</p></div>
  <form class="form-card" method="post" action="/form/recover">
    ${csrf(session)}${errorNotice(error)}
    <div class="field"><label for="code">Код восстановления</label><input id="code" name="recovery_code" autocomplete="off" autocapitalize="none" required maxlength="160"></div>
    <div class="field"><label for="new-password">Новый пароль</label><div class="password"><input id="new-password" name="new_password" type="password" autocomplete="new-password" required minlength="12" maxlength="256"><button type="button" class="button ghost" data-password="new-password" aria-pressed="false" hidden>Показать</button></div><small>Не короче 12 символов</small></div>
    ${formButton('Изменить пароль')}
    <a href="/login">Вернуться ко входу</a>
  </form>
</section>`, { session, route: '/recover' });
}

function accessStatus(account, now) {
  const paidUntil = account.paid_until || 0;
  const manualUntil = account.manual_until || 0;
  const trialUntil = account.trial_until || 0;
  const until = Math.max(paidUntil, manualUntil, trialUntil);
  if (until <= now) return { until, active: false, trialAvailable: !account.trial_started_at && !until };
  const label = until === paidUntil ? 'Подписка активна' : until === manualUntil ? 'Доступ выдан поддержкой' : 'Пробный период';
  return { until, active: true, label };
}

function status(session, now, access) {
  if (access.active) {
    const remaining = access.until - now;
    const days = Math.ceil(remaining / 86400);
    const word = { one: 'день', few: 'дня', many: 'дней' }[plural.select(days)];
    const detail = remaining >= 86400 ? `Осталось ${days} ${word}.` : 'Точное время окончания указано по Москве.';
    return `<h2 class="access-title">До ${formatDate(access.until)}</h2><p>${detail}</p>
      ${action('/checkout', 'Продлить доступ', 'primary')}`;
  }
  if (access.trialAvailable) {
    return `<h2 class="access-title">Начните с 5 часов бесплатно</h2><p>Пробный период начнётся сразу после нажатия. Карта не нужна.</p>
      <div class="actions"><form method="post" action="/form/trial">${csrf(session)}${formButton('Попробовать 5 часов бесплатно')}</form>
      ${action('/checkout', 'Получить доступ')}</div>`;
  }
  return `<h2 class="access-title">Срок доступа истёк</h2>
    ${access.until ? `<p>Доступ закончился ${formatDate(access.until)} (МСК).</p>` : ''}
    ${action('/checkout', 'Получить доступ', 'primary')}`;
}

function deviceSummary(device) {
  return `<span class="route-icon">${icon('phone')}</span><div class="device-name"><strong>${escapeHtml(device.name)}</strong><p>Store · привязано ${formatDate(device.activated_at)} (МСК)</p></div>`;
}

function cabinet(session, error, now) {
  const { devices, login } = session.account;
  const access = accessStatus(session.account, now);
  return layout('Кабинет', `
<section class="wrap dashboard dashboard-overview">
  <div class="page-heading"><div><h1>Кабинет</h1><p>Вы вошли как <strong>${escapeHtml(login)}</strong></p></div>
    ${access.active ? badge(access.label, 'success') : access.trialAvailable ? '' : badge('Доступ закончился', 'warning')}</div>
  ${errorNotice(error)}
  <div class="dashboard-grid">
    <div class="dashboard-stack">
      <section class="dashboard-card subscription-card" aria-label="Доступ к шлюзу TG-ru">
        ${status(session, now, access)}
      </section>
      <section class="dashboard-card">
        <div class="dashboard-card-heading"><h2>Устройства</h2><p>Подключено ${devices.length} из 3</p></div>
        ${devices.length ? `<ul class="device-overview-list">${devices.map((device) => `<li>${deviceSummary(device)}</li>`).join('')}</ul>` : '<p>Устройств пока нет. Войдите в TG-ru на Android, чтобы подключить первое.</p>'}
        ${action('/devices', 'Управлять устройствами')}
      </section>
    </div>
    <div class="dashboard-stack dashboard-aside">
      <section class="dashboard-card"><h2>Приложение</h2><p>TG-ru для Android. После установки введите логин и пароль на первом экране.</p>
        ${action('/downloads', 'К загрузкам', 'primary')}</section>
      <section class="dashboard-card"><h2>Помощь</h2><p>Если доступ не появился или возникла проблема с подключением, напишите в поддержку.</p>
        ${action('/help', 'Помощь с доступом')}</section>
    </div>
  </div>
</section>`, { session, route: '/cabinet' });
}

function devices(session, error) {
  const items = session.account.devices;
  return layout('Устройства', `
<section class="wrap dashboard">
  <div class="page-heading"><h1>Устройства</h1><p>Подключено ${items.length} из 3</p></div>
  ${errorNotice(error)}
  ${items.length === 3 ? `<p class="notice" role="status">${icon('info')}Достигнут лимит в 3 устройства. Отвяжите одно, чтобы подключить новое.</p>` : ''}
  ${items.length ? `<ul class="device-list">${items.map((device) => `<li>${deviceSummary(device)}<details class="revoke"><summary>Отвязать</summary><p>Приложение на нём потеряет доступ. Вы сможете привязать его снова.</p><form method="post" action="/form/devices/revoke">${csrf(session)}${input('device_id', device.id)}${formButton('Подтвердить отвязку', 'danger')}</form></details></li>`).join('')}</ul>` : `
  <div class="empty"><span class="route-icon">${icon('phone')}</span><h2>Устройств пока нет</h2><p>Скачайте приложение и введите логин и пароль TG-ru.</p>${action('/downloads', 'К загрузкам', 'primary')}</div>`}
  <p class="subtle"><a href="/cabinet">К обзору</a></p>
</section>`, { session, route: '/devices' });
}

function checkout(session) {
  return layout('Оплата', `
<section class="wrap form-page checkout-page">
  <div class="form-card">
    <div class="form-intro"><h1>Оплата подписки</h1><p>199 ₽ за 30 дней. Автоматического списания нет.</p></div>
    <p class="notice" role="status">${icon('info')}Оплата временно недоступна. Для получения доступа обратитесь в поддержку.</p>
    <div class="actions">${action(supportLink, 'Написать в поддержку', 'primary')}${action('/cabinet', 'К обзору')}</div>
  </div>
</section>`, { session, route: '/checkout' });
}

const documents = {
  '/downloads': ['Загрузки', `<h2>TG-ru для Android</h2><p>Публичная загрузка пока не открыта.</p><h2>Подключение устройства</h2><ol><li>Создайте аккаунт TG-ru и начните пробу или оформите подписку.</li><li>Откройте установленное приложение и введите логин и пароль TG-ru.</li><li>Войдите в свой аккаунт Telegram на следующем экране.</li></ol><p>Android попросит разрешить установку из этого источника. Это нормально для установки не из магазина.</p>`],
  '/help': ['Помощь', `<h2>Доступ к шлюзу</h2><p>Оплата временно недоступна. Для получения доступа обратитесь в поддержку.</p><p><a href="${supportLink}">Написать в поддержку</a></p><h2>Не получается подключить телефон</h2><p>Проверьте, что пробный период или подписка действуют. При лимите в три телефона сначала отвяжите одно из устройств в кабинете.</p><p><a href="/cabinet">Открыть кабинет</a></p>`],
  '/visibility': ['Что мы видим, а чего нет', `${visibilitySummary()}<h2>Два аккаунта</h2><p>Аккаунт TG-ru связывает подписку и устройство. В Telegram вы входите отдельно. Пароль TG-ru вводится при активации устройства, затем оно подтверждает себя своим ключом.</p><h2>Как проверить сборку</h2><p>Сборка приложения пока не воспроизводима: вы можете сверить хеш файла с опубликованным, но не можете самостоятельно собрать тот же файл из исходного кода.</p>`],
  '/terms': ['Оферта', '<p>Оферта готовится до запуска оплаты.</p><h2>Стоимость</h2><p>199 ₽ за 30 дней. Проба на 5 часов доступна один раз для аккаунта. Автоматических списаний нет.</p>'],
  '/privacy': ['Конфиденциальность', '<p>Кабинет хранит хеш пароля, срок доступа и публичный ключ привязанного устройства. На сайте нет рекламы и аналитики.</p><h2>Данные Telegram</h2><p>Номер телефона, код входа и облачный пароль Telegram не запрашиваются кабинетом.</p><p><a href="/visibility">Какие данные видит шлюз</a></p>'],
  '/refunds': ['Возвраты', '<p>Условия возврата уточняются.</p>'],
  '/compatibility': ['Политика совместимости', '<p>Порядок поддержки версий и архив загрузок будут опубликованы перед выпуском.</p>'],
};

function documentPage(route, session) {
  const [title, body] = documents[route];
  return layout(title, `<article class="wrap prose"><h1>${title}</h1>${body}</article>`, { session, route });
}

function redirect(response, location) {
  response.writeHead(303, { Location: location, 'Cache-Control': 'no-store' });
  response.end();
}

function respond(response, status, type, body, head = false) {
  response.writeHead(status, {
    'Content-Type': type,
    'Content-Security-Policy': "default-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'; object-src 'none'",
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
    'Cache-Control': 'no-store',
  });
  response.end(head ? undefined : body);
}

async function formData(request) {
  if (!/^application\/x-www-form-urlencoded(?:;|$)/i.test(request.headers['content-type'] ?? '')) return null;
  if (Number(request.headers['content-length']) > 16_384) {
    request.resume();
    return null;
  }
  let length = 0;
  const buffers = [];
  for await (const chunk of request.iterator({ destroyOnReturn: false })) {
    length += chunk.length;
    if (length > 16_384) {
      request.resume();
      return null;
    }
    buffers.push(chunk);
  }
  return Object.fromEntries(new URLSearchParams(Buffer.concat(buffers).toString('utf8')));
}

export async function handleStorePages(request, response, { getSession, submit }) {
  const url = new URL(request.url, 'http://localhost');
  const route = url.pathname;
  const asset = staticFiles.get(route);
  const pageRequest = request.method === 'GET' || request.method === 'HEAD';
  if (route === '/account' && pageRequest) {
    redirect(response, '/cabinet');
    return true;
  }
  if (asset && pageRequest) {
    respond(response, 200, ...asset, request.method === 'HEAD');
    return true;
  }
  const formAction = request.method === 'POST' ? forms.get(route) : undefined;
  if (!formAction && (!pageRequest || !pages.has(route))) return false;
  let session = await getSession();
  const now = Math.floor(Date.now() / 1000);
  const html = (body, status = 200) => respond(response, status, 'text/html; charset=utf-8', body, request.method === 'HEAD');

  if (formAction) {
    const data = await formData(request);
    if (!data) {
      html(layout('Ошибка формы', '<section class="wrap prose"><h1>Не удалось прочитать форму</h1><p><a href="/">Вернуться на главную</a></p></section>', { session, route }), 400);
      return true;
    }
    try {
      const fields = { ...data, accepted_terms: data.accepted_terms === 'yes' };
      const result = await submit(formAction, fields, data.csrf);
      if ((formAction === 'register' || formAction === 'recover') && result.recovery_code) {
        session = { ...session, ...result };
        html(recoveryCode(session, result.recovery_code, formAction === 'recover'), formAction === 'register' ? 201 : 200);
      } else if (formAction === 'logout' || formAction === 'recover') {
        redirect(response, '/login');
      } else {
        redirect(response, formAction === 'devices/revoke' ? '/devices' : '/cabinet');
      }
    } catch (error) {
      const status = Number.isInteger(error.status) && error.status >= 400 && error.status <= 599 ? error.status : 500;
      if (formAction === 'register' || formAction === 'login') html(credentialsPage('/' + formAction, session, { error: error.code, login: data.login }), status);
      else if (formAction === 'recover') html(recover(session, error.code), status);
      else if (!session.account) redirect(response, '/login');
      else if (formAction === 'devices/revoke') html(devices(session, error.code), status);
      else html(cabinet(session, error.code, now), status);
    }
    return true;
  }

  if (protectedPages.has(route) && !session.account) redirect(response, '/login');
  else if (route === '/') html(home(session));
  else if (route === '/register' || route === '/login') {
    if (session.account) redirect(response, '/cabinet');
    else html(credentialsPage(route, session));
  } else if (route === '/recover') html(recover(session));
  else if (route === '/cabinet') html(cabinet(session, '', now));
  else if (route === '/devices') html(devices(session));
  else if (route === '/checkout') html(checkout(session));
  else html(documentPage(route, session));
  return true;
}
