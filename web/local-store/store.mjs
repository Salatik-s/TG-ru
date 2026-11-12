(() => {
  'use strict';

  document.addEventListener('DOMContentLoaded', () => {
    for (const button of document.querySelectorAll('[data-password]')) {
      const field = document.getElementById(button.dataset.password);
      if (!field) continue;
      button.hidden = false;
      button.addEventListener('click', () => {
        const visible = field.type === 'password';
        field.type = visible ? 'text' : 'password';
        button.setAttribute('aria-pressed', String(visible));
        button.textContent = visible ? 'Скрыть' : 'Показать';
      });
    }
    for (const button of document.querySelectorAll('[data-generate]')) {
      const field = document.getElementById(button.dataset.generate);
      if (!field || !globalThis.crypto?.getRandomValues) continue;
      button.hidden = false;
      button.addEventListener('click', () => {
        const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
        const values = crypto.getRandomValues(new Uint8Array(24));
        field.value = Array.from(values, (value) => alphabet[value & 63]).join('');
        field.type = 'text';
        const toggle = document.querySelector('[data-password="password"]');
        if (toggle) {
          toggle.setAttribute('aria-pressed', 'true');
          toggle.textContent = 'Скрыть';
        }
        field.focus();
        field.select();
      });
    }

    for (const button of document.querySelectorAll('[data-copy]')) {
      const field = document.getElementById(button.dataset.copy);
      if (!field || !navigator.clipboard) continue;
      button.hidden = false;
      button.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(field.value);
          button.textContent = 'Скопировано';
          setTimeout(() => { button.textContent = 'Скопировать'; }, 2000);
        } catch {
          field.focus();
          field.select();
        }
      });
    }
    for (const button of document.querySelectorAll('[data-save]')) {
      const field = document.getElementById(button.dataset.save);
      if (!field) continue;
      button.hidden = false;
      button.addEventListener('click', () => {
        const address = URL.createObjectURL(new Blob([field.value + '\n'], { type: 'text/plain;charset=utf-8' }));
        const link = document.createElement('a');
        link.href = address;
        link.download = 'tg-ru-recovery.txt';
        link.click();
        setTimeout(() => URL.revokeObjectURL(address), 1000);
      });
    }

    const error = document.querySelector('[role="alert"][tabindex="-1"]');
    if (error) error.focus();
    for (const form of document.querySelectorAll('form[method="post"]')) {
      form.addEventListener('submit', (event) => {
        if (!event.submitter) return;
        if (form.dataset.pending === 'true') {
          event.preventDefault();
          return;
        }
        form.dataset.pending = 'true';
        const button = event.submitter;
        button.setAttribute('aria-busy', 'true');
        button.setAttribute('aria-disabled', 'true');
        button.textContent = 'Подождите…';
      });
    }
    window.addEventListener('pageshow', (event) => {
      if (event.persisted && document.querySelector('form[data-pending="true"]')) location.reload();
    });
  });
})();
