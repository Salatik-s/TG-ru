import { readFileSync } from 'node:fs';

const staticFiles = new Map([
  ['/store.css', ['text/css; charset=utf-8', readFileSync(new URL('./store.css', import.meta.url))]],
  ['/fonts/inter-cyrillic.woff2', ['font/woff2', readFileSync(new URL('./inter-cyrillic.woff2', import.meta.url))]],
  ['/fonts/inter-latin.woff2', ['font/woff2', readFileSync(new URL('./inter-latin.woff2', import.meta.url))]],
  ['/store.mjs', ['text/javascript; charset=utf-8', readFileSync(new URL('./store.mjs', import.meta.url))]],
]);
const strings = JSON.parse(readFileSync(new URL('../../i18n/ru.json', import.meta.url), 'utf8'));
const dateTime = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Europe/Moscow' });
const calendarDate = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeZone: 'Europe/Moscow' });
const clockTime = new Intl.DateTimeFormat('ru-RU', { timeStyle: 'short', timeZone: 'Europe/Moscow' });
const plural = new Intl.PluralRules('ru');
const pages = new Set(['/', '/register', '/login', '/recover', '/cabinet', '/devices', '/downloads', '/help',
  '/checkout', '/visibility', '/terms', '/privacy', '/refunds', '/compatibility']);
const protectedPages = new Set(['/cabinet', '/devices']);
const forms = new Map([
  ['/form/register', 'register'], ['/form/login', 'login'], ['/form/recover', 'recover'], ['/form/logout', 'logout'],
  ['/form/trial', 'trial'], ['/form/devices/revoke', 'devices/revoke'],
]);
const supportLink = 'https://t.me/prince_skazki';
const favicon = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAACwElEQVR4nMRXX0hTYRT/3bvhdWJjYDUIJ9aMyDIKIeNaZGQwKJRYe4o99NBDKBX0UAwKepEIAutlDz4ESkEsiVbEoKKVNCoITXIQ+BCsJtKEmJBsbd6+8+1Oh3mvc3O7Pxjf7vfn/s4595xz78+MVXBpROlhg0cBOgUBDigwoxQIyCgKYgLwnl0F7nqF4P9bCnDxgdIuLuIOIz6KCoCRvV0UceXeWeFzwVwO3GsBoyV7W7wVGcbhzkeDG0CeCwo+VJy8wAhFwCGKhEjXFPaqkRMYF+ckW9SEewpj0Etee2AcPCKVGgwCcYu8zg0CcZurmnwrQckIg6HrfXY2gthMUmPVCttuGTZJ3ZuI4PuXMOIJwObsgqNtea1kA/58HcCr4JTG6h50XH/OSJKIP3HjRXh6eSkyhI9oQWv/KOSdVj0K3gcUzdVUEgvpFP8bf3wQbyaAZu8ndO6iGQk1dVZkJy5geDgEWFyQ+3zYbqtFKnoDwYchpOHCsdt+OHUioZ+AkhUW9XCNutNUuwWWTfkNMURfMnJWSAf6/GhV68nS4cfJv9cQ/QlkfrGJxlINWBPTSMzQKGPrimJuOHwLR4q4w8aUoL0R+aDMvz6FRwV5Y+t5hzPHtVtN2WXIPZidwlwuVVC314fuc37I7TlSk0n/fJkGyNi2j8YQxsdyVWCyy2jeL0NKxUClam/Sb7RlPgIJzl4fJicHMPfsBO6Pu9DSJGE+GkL8N1t29KNtByppAMPm8zh904Gxoav49iPEfjRpRYM8iG63ayk3tKDfB9aLbArpBVay9UW0QBUb+yIyseZUv64TLAnpI9EoMG6RvtthEIhbVEWDISBu6gMBGIcA1wWXR5RwpdSQFkglDXqFLt4JSS5VNRkZF+eE2oq5VmNyqSpGqNIsrw+X3gWk1UguUWhQIdC9iaNQJQurbaymPP8HAAD//56cWNAAAAAGSURBVAMAsRzb6b2KXo0AAAAASUVORK5CYII=';

const paths = {
  phone: '<rect x="7" y="3" width="10" height="18" rx="2"/><path d="M11 17h2"/>',
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
    : `${route === '/login' ? '' : action('/login', 'Войти', 'ghost')}${action('/register', 'Создать аккаунт', 'primary')}`;
  return `<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="description" content="TG-ru — независимый Android-клиент Telegram с подпиской на доступ к шлюзу.">
  <meta name="color-scheme" content="dark">
  <title>${escapeHtml(title)} · TG-ru</title>
  <link rel="icon" type="image/png" sizes="32x32" href="${favicon}">
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
    </div>
  </footer>
</body>
</html>`;
}

function pricing(session, accountPath) {
  const gateway = 'Шлюз TG-ru';
  const editor = 'Онлайн-редактор TXT, Word, PDF, Excel';
  const plans = [
    { name: 'DEMO', price: '0 ₽', term: 'на 5 часов', points: [gateway, 'До 2 устройств', 'Оплата не нужна'],
      button: action(accountPath, 'Попробовать DEMO') },
    { name: 'START', price: '199 ₽', term: 'в месяц', points: [gateway, 'До 3 устройств', 'Без автоматического списания'],
      button: session.account ? action('/checkout', 'Получить доступ', 'primary') : action('/register', 'Создать аккаунт', 'primary') },
    { name: 'PRO', price: '500 ₽', term: 'за 3 месяца', highlight: 'gateway', badge: 'Выгодно: −16%',
      previous: '597 ₽ при оплате START три раза', saving: '167 ₽ в месяц вместо 199 ₽ · экономия 97 ₽',
      points: ['Всё из START', 'До 4 устройств', 'Оплата за 90 дней'],
      button: action('/checkout?plan=pro', 'Выбрать PRO', 'primary') },
  ];
  const editorPlans = [
    { name: 'DEMO SECRET', price: '0 ₽', term: 'на 30 минут', points: [gateway, editor, 'Оплата не нужна'],
      button: '<button type="button" class="button secondary" disabled>Попробовать SECRET</button>' },
    { name: 'SECRET', price: '666 ₽', term: 'в месяц', highlight: 'editor', badge: 'Редактор файлов',
      points: [gateway, editor, '1 устройство', '10 файлов в день', '40 файлов в неделю'],
      button: '<button type="button" class="button secondary" disabled>Выбрать SECRET</button>' },
    { name: 'SECRET+', price: '1 300 ₽', term: 'в месяц', points: ['Всё из SECRET', '3 устройства', '20 файлов в день', '100 файлов в неделю'],
      button: '<button type="button" class="button secondary" disabled>Выбрать SECRET+</button>' },
  ];
  const card = ({ name, price, term, highlight, badge, previous, saving, points, button }) => `
    <article class="price-panel${highlight ? ' price-highlight-' + highlight : ''}">
      ${badge ? `<span class="plan-badge">${badge}</span>` : ''}
      <h4>${name}</h4>
      <div class="plan-amount">
        ${previous ? `<p class="plan-previous"><s>${previous}</s></p>` : ''}
        <p class="price"><strong>${price}</strong> <span>${term}</span></p>
        ${saving ? `<p class="plan-saving">${saving}</p>` : ''}
      </div>
      <ul class="plan-points">${points.map(point => `<li>${icon('check')}<span>${point}</span></li>`).join('')}</ul>
      ${button}
    </article>`;
  return `
<section id="price" class="wrap band">
  <h2>Подписка</h2>
  <section class="plan-group" aria-labelledby="gateway-plans">
    <div class="plan-group-header"><h3 id="gateway-plans">Доступ к шлюзу</h3></div>
    <div class="price-grid">${plans.map(card).join('')}</div>
  </section>
  <section class="plan-group plan-group-editor" aria-labelledby="editor-plans">
    <div class="plan-group-header"><h3 id="editor-plans">Онлайн-редактор файлов</h3><p>В разработке</p></div>
    <div class="price-grid">${editorPlans.map(card).join('')}</div>
  </section>
  <article class="price-panel plan-custom">
    <div><h3>CUSTOM</h3><strong>Цена договорная</strong>
    <p>Свои настройки, количество устройств и лимиты обсуждаются с владельцем сервиса.</p></div>
    ${action(supportLink, 'Написать владельцу')}
  </article>
</section>`;
}

function home(session) {
  const accountPath = session.account ? '/cabinet' : '/register';
  const primary = action(accountPath, session.account ? 'Открыть кабинет' : 'Создать аккаунт', 'primary');
  const secondary = session.account ? '' : action('/login', 'Войти');
  return layout(strings.home.title, `
<section class="wrap hero">
  <div class="hero-copy">
    <h1>Telegram через <br><span>VPN-шлюз TG-ru</span></h1>
    <p class="lead">${strings.home.subtitle}.</p>
    <div class="actions">${primary}${secondary}</div>
  </div>
  <figure class="route-figure" aria-label="Маршрут подключения">
    <figcaption>Маршрут подключения</figcaption>
    <ol class="route">
      <li><span class="route-icon">${icon('phone')}</span><span><strong>Ваш Android</strong><small>Приложение TG-ru</small></span></li>
      <li class="route-current"><span class="route-icon">${icon('arrow')}</span><span><strong>VPN-шлюз TG-ru</strong><small>Передаёт трафик в Telegram</small></span></li>
      <li><span class="route-icon">${icon('chat')}</span><span><strong>Telegram</strong><small>Чаты вашего аккаунта</small></span></li>
    </ol>
  </figure>
</section>
<section id="how" class="wrap band">
  <h2>Как это работает</h2>
  <ol class="steps">
    <li><span>1</span><div><h3>Создайте аккаунт и запустите пробу</h3><p>Логин и пароль кабинета нужны для доступа к шлюзу TG-ru.</p></div></li>
    <li><span>2</span><div><h3>Установите TG-ru</h3><p>Скачайте APK в кабинете. При первом запуске введите логин и пароль TG-ru.</p></div></li>
    <li><span>3</span><div><h3>Войдите в Telegram</h3><p>После подключения введите номер телефона, как при обычном входе в Telegram.</p></div></li>
  </ol>
</section>
<section class="wrap band">
  <h2>Что мы видим, а чего нет</h2>
  ${visibilitySummary()}
  <a class="text-link" href="/visibility">Подробнее о данных ${icon('arrow')}</a>
</section>
${pricing(session, accountPath)}
<section class="wrap band">
  <h2>Частые вопросы</h2>
  <div class="faq">
    <details><summary>Как защищены сообщения?</summary><p>Приложение использует протокол MTProto для связи с Telegram. Наш VPN-шлюз пересылает зашифрованный трафик. <a href="/visibility">Подробнее о данных шлюза</a>.</p></details>
    <details><summary>Зачем два аккаунта?</summary><p>Кабинет TG-ru управляет доступом к VPN-шлюзу. В свой аккаунт Telegram вы входите отдельно, в приложении.</p></details>
    <details><summary>Что будет, когда подписка закончится?</summary><p>Приложение предложит продлить доступ. Данные Telegram на телефоне сохранятся.</p></details>
    <details><summary>Сколько устройств можно подключить?</summary><p>На DEMO можно подключить два устройства, на START — три, на PRO — четыре.</p></details>
  </div>
</section>`, { session, route: '/', marketing: true });
}

function visibilitySummary() {
  return `<div class="visibility-grid">
    <div><h3><span class="route-icon">${icon('eye')}</span>Что видно шлюзу</h3><p>IP-адрес подключения, время соединения и объём переданного трафика.</p></div>
    <div><h3><span class="route-icon">${icon('lock')}</span>Что шлюз не видит</h3><p>Содержимое переписки и файлов, номер телефона, код входа и облачный пароль Telegram.</p></div>
  </div>`;
}

function credentialsPage(route, session, { error, login = '' } = {}) {
  const registering = route === '/register';
  const title = registering ? 'Создать аккаунт' : 'Войти';
  const fieldHint = registering ? '<small id="login-hint">3–24 символа: латиница, цифры, _</small>' : '';
  return layout(title, `
<section class="wrap form-page${registering ? ' register-page' : ''}">
  <form class="form-card" method="post" action="/form/${registering ? 'register' : 'login'}">
    <div class="form-intro"><h1>${title}</h1>
    <p>${registering ? 'Проба на 5 часов бесплатна. START стоит 199 ₽ за 30 дней.' : 'Здесь можно посмотреть срок доступа и список устройств.'}</p></div>
    ${csrf(session)}${errorNotice(error)}
    <div class="field"><label for="login">Логин</label><input id="login" name="login" autocomplete="username" autocapitalize="none" spellcheck="false" required maxlength="24" value="${escapeHtml(login)}" ${registering ? 'minlength="3" pattern="[a-zA-Z0-9_]{3,24}" aria-describedby="login-hint"' : ''}>${fieldHint}</div>
    <div class="field"><label for="password">Пароль</label><div class="password"><input id="password" name="password" type="password" autocomplete="${registering ? 'new-password' : 'current-password'}" required maxlength="256" ${registering ? 'minlength="12" aria-describedby="password-hint"' : ''}><button type="button" class="button ghost" data-password="password" aria-pressed="false" hidden>Показать</button></div>${registering ? '<div class="password-hints"><small id="password-hint">Не короче 12 символов</small><button class="button ghost generate" type="button" data-generate="password" hidden>Сгенерировать пароль</button></div>' : '<a href="/recover">Забыли пароль?</a>'}</div>
    ${registering ? '<div class="field"><label for="email-preview">Email (необязательно)</label><input id="email-preview" type="email" disabled aria-describedby="email-preview-hint"><small id="email-preview-hint">Email пока не подключён. После регистрации вы получите код восстановления пароля.</small></div><label class="check"><input type="checkbox" name="accepted_terms" value="yes" required><span>Принимаю <a href="/terms">условия доступа</a>. С <a href="/privacy">политикой конфиденциальности</a> ознакомлен.</span></label>' : ''}
    ${formButton(title)}
    ${registering ? '' : '<p class="form-alt">Нет аккаунта? <a href="/register">Создать аккаунт</a></p>'}
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
  const label = account.plan === 'pro' ? 'PRO активен' : account.plan === 'start' ? 'START активен' : 'Пробный период';
  return { until, active: true, label, trial: !account.plan };
}

function status(session, now, access) {
  if (access.active) {
    const remaining = access.until - now;
    const days = Math.ceil(remaining / 86400);
    const word = { one: 'день', few: 'дня', many: 'дней' }[plural.select(days)];
    const minutes = Math.ceil(remaining / 60);
    const detail = remaining >= 86400 ? `Осталось ${days} ${word}.` : `Осталось ${Math.floor(minutes / 60)} ч ${minutes % 60} мин.`;
    const trialDuration = access.until - session.account.trial_started_at;
    const deadline = new Date(access.until * 1000);
    return `<p class="access-label">${access.label}</p><h2 class="access-title">Действует до ${calendarDate.format(deadline)}</h2>
      ${access.trial ? `<progress class="access-progress" value="${remaining}" max="${trialDuration}" aria-label="Осталось времени пробного доступа"></progress>` : ''}
      <p>${detail} Окончание в ${clockTime.format(deadline)} (МСК).</p>
      <div class="actions">${session.account.plan === 'pro' ? action('/checkout?plan=pro', 'Продлить на 90 дней', 'primary') : action('/checkout', 'Продлить на 30 дней', 'primary')}${action('/#price', 'Сменить тариф')}</div>`;
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
  return `<span class="route-icon">${icon('phone')}</span><div class="device-name"><strong>${escapeHtml(device.name)}</strong><p>Редакция Store · привязано ${formatDate(device.activated_at)} (МСК)</p></div>`;
}

function revokeDevice(device, session) {
  return `<details class="revoke"><summary>Отвязать</summary><p>Приложение на нём потеряет доступ. Вы сможете привязать его снова.</p><form method="post" action="/form/devices/revoke">${csrf(session)}${input('device_id', device.id)}${formButton('Подтвердить отвязку', 'danger')}</form></details>`;
}

function cabinet(session, error, now) {
  const { devices, login, device_limit } = session.account;
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
        <div class="dashboard-card-heading"><h2>Устройства</h2><p>Подключено ${devices.length} из ${device_limit}</p></div>
        ${devices.length ? `<ul class="device-overview-list">${devices.map((device) => `<li>${deviceSummary(device)}${revokeDevice(device, session)}</li>`).join('')}</ul>` : '<p>Устройств пока нет. Войдите в TG-ru на Android, чтобы подключить первое.</p>'}
      </section>
    </div>
    <div class="dashboard-stack dashboard-aside">
      <section class="dashboard-card"><h2>Приложение</h2><p>TG-ru для Android. После установки введите логин и пароль на первом экране.</p>
        ${action('/downloads', 'Скачать APK', 'primary')}<a class="download-hash-link" href="/downloads#download-checksum">Как проверить хеш</a></section>
      <section class="dashboard-card"><h2>Помощь</h2><p>Не получается подключиться или продлить доступ? Напишите в поддержку.</p>
        ${action('/help', 'Помощь с доступом')}</section>
    </div>
  </div>
  <section class="dashboard-card payments-card"><h2>Платежи</h2><p>История платежей появится после подключения онлайн-оплаты.</p></section>
</section>`, { session, route: '/cabinet' });
}

function devices(session, error) {
  const { devices: items, device_limit } = session.account;
  return layout('Устройства', `
<section class="wrap dashboard">
  <div class="page-heading"><h1>Устройства</h1><p>Подключено ${items.length} из ${device_limit}</p></div>
  ${errorNotice(error)}
  ${items.length >= device_limit ? `<p class="notice" role="status">${icon('info')}Все места заняты. Отвяжите устройство, чтобы подключить другое.</p>` : ''}
  ${items.length ? `<ul class="device-list">${items.map((device) => `<li>${deviceSummary(device)}${revokeDevice(device, session)}</li>`).join('')}</ul>` : `
  <div class="empty"><span class="route-icon">${icon('phone')}</span><h2>Устройств пока нет</h2><p>Скачайте приложение и введите логин и пароль TG-ru.</p>${action('/downloads', 'К загрузкам', 'primary')}</div>`}
  <p class="subtle"><a href="/cabinet">К обзору</a></p>
</section>`, { session, route: '/devices' });
}

function checkout(session, pro) {
  const contact = pro ? supportLink + '?text=' + encodeURIComponent('Здравствуйте! Хочу подключить PRO на 90 дней.') : supportLink;
  return layout('Оплата', `
<section class="wrap form-page checkout-page">
  <div class="form-card">
    <div class="form-intro"><h1>Оплата подписки${pro ? ' PRO' : ''}</h1><p>${pro ? '500 ₽ за 90 дней. До 4 устройств.' : '199 ₽ за 30 дней. Автоматического списания нет.'}</p></div>
    <p class="notice" role="status">Оплата временно недоступна. Для получения доступа обратитесь в поддержку.</p>
    <div class="actions">${action(contact, 'Написать в поддержку', 'primary')}${action(session.account ? '/cabinet' : '/#price', 'Отмена')}</div>
  </div>
</section>`, { session, route: '/checkout' });
}

const documents = {
  '/downloads': ['Загрузки', '<h2>Подключение устройства</h2><ol><li>Создайте аккаунт TG-ru и запустите пятичасовую пробу или получите доступ через поддержку.</li><li>Откройте приложение и введите логин и пароль TG-ru.</li><li>Войдите в свой аккаунт Telegram на следующем экране.</li></ol><p>При установке Android может попросить разрешить установку приложений из браузера.</p>'],
  '/help': ['Помощь', `<h2>Как получить доступ</h2><p>Онлайн-оплата пока недоступна. Напишите в поддержку, чтобы подключить START или PRO.</p><p><a href="${supportLink}">Написать в поддержку</a></p><h2>Телефон не подключается</h2><p>Откройте кабинет и проверьте срок доступа. Если все места заняты, отвяжите телефон, которым больше не пользуетесь.</p><p><a href="/cabinet">Открыть кабинет</a></p>`],
  '/visibility': ['Что мы видим, а чего нет', `
<p>Кабинет управляет доступом, шлюз пересылает трафик, а Telegram обслуживает аккаунт и чаты.</p>
${visibilitySummary()}
<h2>Как проходит соединение</h2>
<p>Приложение шифрует сообщения по протоколу Telegram MTProto. TG-ru передаёт зашифрованный поток к серверам Telegram. Шлюзу доступны сетевые метаданные соединения, например IP-адрес, время и объём переданных данных.</p>
<p>В обычных облачных чатах шифрование действует между клиентом и серверами Telegram. Сквозное шифрование используется в секретных чатах. <a href="https://core.telegram.org/mtproto">Как устроен MTProto</a>.</p>
<h2>Два отдельных аккаунта</h2>
<p>Логин TG-ru нужен кабинету для пробы, доступа и привязки телефона. После активации приложение подтверждает себя ключом устройства. Номер телефона, код входа и облачный пароль Telegram вводятся отдельно в клиенте. Кабинет TG-ru их не запрашивает.</p>
<h2>Как проверить APK</h2>
<p>Сверьте SHA-256 скачанного файла с суммой в <a href="/downloads">разделе загрузок</a>. Она позволяет проверить целостность опубликованного APK. Подробнее о данных и запросах на их удаление читайте в <a href="/privacy">политике конфиденциальности</a>.</p>`],
  '/terms': ['Оферта и условия доступа', `
<p>Эти условия описывают доступ к кабинету TG-ru, Android-клиенту и шлюзу. Сейчас доступна бесплатная проба. Приём онлайн-оплаты на сайте отключён.</p>
<h2>Что предоставляет TG-ru</h2>
<p>TG-ru — независимый клиент, использующий Telegram API. Сервис даёт доступ к собственному шлюзу для соединения с Telegram. Для переписки нужен отдельный аккаунт Telegram. <a href="https://telegram.org/tos">Условия Telegram</a> действуют при использовании мессенджера.</p>
<h2>Проба, цена и срок</h2>
<ul><li>Пробный доступ действует 5 часов с момента запуска. Он доступен один раз на аккаунт, рассчитан на два устройства и не требует банковской карты.</li>
<li>START стоит 199 ₽ за 30 дней и даёт доступ на трёх устройствах. PRO стоит 500 ₽ за 90 дней и поддерживает четыре устройства. Чтобы подключить тариф, напишите в поддержку. Автоматических списаний нет. Редактор файлов в разработке.</li>
<li>После окончания срока шлюз перестаёт принимать подключения этого аккаунта. Оставшееся время и привязанные устройства отображаются в кабинете.</li></ul>
<h2>Подключение и правила пользования</h2>
<p>Создайте аккаунт, сохраните код восстановления, запустите пробу или получите доступ через поддержку и активируйте устройство. В кабинете можно отвязать телефон. Запрещено использовать шлюз для спама, атак, неправомерного доступа и других действий, нарушающих применимые правила.</p>
<h2>Платежи и обращения</h2>
<p>После подключения Platega сумма, срок доступа, сведения об исполнителе и способ оплаты будут показаны до подтверждения заказа. Для платёжной формы будут действовать <a href="https://platega.io/ru/public-offer">условия Platega</a>. Реквизиты исполнителя будут опубликованы до приёма платных заказов. Ознакомьтесь с <a href="/refunds">порядком возврата</a> и <a href="/privacy">политикой конфиденциальности</a>.</p>
<p>По вопросам доступа и условий напишите в <a href="${supportLink}">поддержку TG-ru</a>.</p>`],
  '/privacy': ['Политика конфиденциальности', `
<p>Политика описывает обработку данных в кабинете и шлюзе TG-ru. Обращения по данным принимает <a href="${supportLink}">поддержка TG-ru</a>.</p>
<h2>Какие данные обрабатываем и зачем</h2>
<ul><li><strong>Аккаунт.</strong> Логин, дата регистрации, хеши пароля и кода восстановления нужны для входа, восстановления доступа и защиты аккаунта.</li>
<li><strong>Доступ и устройства.</strong> Сроки пробы и подписки, сведения о ручной выдаче, название и открытый ключ установки, даты привязки и отвязки нужны для проверки права на подключение.</li>
<li><strong>Сессии и технические сведения.</strong> Cookie поддерживает вход в кабинет. При подключении обрабатываются IP-адрес и сетевые метаданные. Технические события используются для работы сервиса, диагностики и защиты от злоупотреблений.</li></ul>
<h2>Хранение и защита</h2>
<p>Пароли и коды восстановления хранятся в виде хешей. Сессия вошедшего пользователя действует до 14 дней и завершается при выходе. Сведения об аккаунте и устройствах хранятся, пока нужны для предоставления и защиты доступа. Одноразовая ссылка на APK привязана к сессии и действует до 15 минут.</p>
<h2>Telegram, Platega и другие получатели</h2>
<p>Telegram обслуживает переписку и вход в мессенджер по <a href="https://telegram.org/privacy">собственной политике конфиденциальности</a>. Шлюз TG-ru пересылает зашифрованный трафик. Подробности приведены на странице <a href="/visibility">«Что мы видим»</a>.</p>
<p>Оплата через Platega пока отключена. После подключения платёжная форма будет обслуживаться на стороне провайдера по <a href="https://platega.io/ru/privacy-policy">политике Platega</a>. Данные банковской карты в кабинете TG-ru вводить не потребуется.</p>
<h2>Как обратиться по своим данным</h2>
<p>Можно запросить сведения об обработке, исправление, прекращение обработки или удаление данных в предусмотренных законом случаях. Укажите логин TG-ru и суть запроса. Поддержка может попросить подтвердить владение аккаунтом безопасным способом. Пароль, код восстановления, код Telegram и банковские данные присылать не нужно.</p>`],
  '/refunds': ['Возвраты', `
<p>Бесплатная проба не требует оплаты и не переходит в платную подписку автоматически. Сейчас сайт TG-ru онлайн-платежи не принимает.</p>
<h2>Если вы уже оплатили доступ</h2>
<p>Напишите в <a href="${supportLink}">поддержку TG-ru</a>. Укажите логин, дату, сумму и номер операции, если он есть. Если доступ не появился, было двойное списание или вы хотите отказаться от услуги, укажите это в обращении. Полный номер карты, CVV, пароли и коды подтверждения отправлять не нужно.</p>
<h2>Как рассматривается обращение</h2>
<p>Команда проверит статус операции и срок предоставленного доступа. При оплате через платёжного провайдера возврат оформляется по исходной операции в порядке, доступном у провайдера. Срок поступления после оформления зависит от способа оплаты и банка.</p>
<p>Отказ от услуги и возврат рассматриваются по применимому законодательству. В случаях, предусмотренных статьёй 32 Закона РФ о защите прав потребителей, потребитель может отказаться от договора оказания услуг с учётом фактически понесённых расходов исполнителя. <a href="https://zpp.rospotrebnadzor.ru/npa/federal/192115">Закон о защите прав потребителей</a>.</p>`],
  '/compatibility': ['Политика совместимости', `
<h2>Устройства и установка</h2>
<p>Публичная редакция Store доступна для Android 7.0 и новее. Скачивайте актуальный APK из <a href="/downloads">кабинета TG-ru</a>. Релиз для iPhone пока не опубликован. Локальная расшифровка зависит от версии Android, системной поддержки и установленных языков.</p>
<h2>Редакции и доступ</h2>
<p>Store подключается через аккаунт TG-ru. Ранее выпущенные клиенты с приглашением продолжают использовать прежний шлюз. Если установить Store поверх прежней сборки с тем же идентификатором пакета, Android заменит приложение. Store при этом попросит активировать доступ через кабинет.</p>
<h2>Обновления и проверка файла</h2>
<p>Для обновления Store скачайте APK из кабинета и подтвердите установку в Android. Сайт показывает версию и SHA-256 файла. В случае ошибки укажите в <a href="${supportLink}">поддержке</a> модель устройства, версию Android, версию клиента и текст ошибки.</p>
<h2>Совместимость с Telegram</h2>
<p>TG-ru использует Telegram API и зависит от работы платформы, маршрута и сети пользователя. При выпуске обновлений ориентируемся на <a href="https://core.telegram.org/api/terms">правила Telegram для сторонних клиентов</a>. Доступ к шлюзу TG-ru и Telegram Premium оформляются отдельно.</p>`],
};

function documentPage(route, session, release) {
  const [title, body] = documents[route];
  const downloads = route === '/downloads' ? `<h2>TG-ru для Android</h2>${
    release
      ? `<p>Версия ${escapeHtml(release.version_name)} · Android ${release.min_sdk === 24 ? '7.0+' : 'API ' + release.min_sdk} · ${Math.round(release.bytes / 1024 / 1024)} МиБ</p>
<p>Опубликовано ${formatDate(Date.parse(release.released_at) / 1000)} по Москве.</p>
<div class="actions"><form method="post" action="/form/download">${csrf(session)}${formButton('Скачать TG-ru.apk')}</form></div>
<details id="download-checksum"><summary>Как проверить хеш SHA-256</summary><p>Сравните SHA-256 скачанного APK с этой суммой.</p><p class="break-word"><code>${escapeHtml(release.sha256)}</code></p></details>
<p>Установка поверх версии с приглашением переключит приложение на вход через кабинет TG-ru. Уже установленная версия с приглашением продолжит работать через свой шлюз.</p>`
      : '<p>Публичная загрузка пока не открыта.</p>'
  }` : '';
  return layout(title, `<article class="wrap prose"><h1>${title}</h1>${downloads}${body}</article>`, { session, route });
}

function redirect(response, location) {
  response.writeHead(303, { Location: location, 'Cache-Control': 'no-store' });
  response.end();
}

function respond(response, status, type, body, head = false) {
  response.writeHead(status, {
    'Content-Type': type,
    'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'self'; frame-ancestors 'none'; object-src 'none'",
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

export async function handleStorePages(request, response, { getSession, submit, release }) {
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

  if ((protectedPages.has(route) || release && route === '/downloads') && !session.account) redirect(response, '/login');
  else if (route === '/') html(home(session));
  else if (route === '/register' || route === '/login') {
    if (session.account) redirect(response, '/cabinet');
    else html(credentialsPage(route, session));
  } else if (route === '/recover') html(recover(session));
  else if (route === '/cabinet') html(cabinet(session, '', now));
  else if (route === '/devices') html(devices(session));
  else if (route === '/checkout') html(checkout(session, url.searchParams.get('plan') === 'pro'));
  else html(documentPage(route, session, release));
  return true;
}
