import { previewBrand } from './preview-header.mjs';

export const previewFooter = `<footer class="preview-footer">
  <div class="preview-footer-grid">
    <div class="preview-footer-intro">
      ${previewBrand}
      <p>Независимый клиент Telegram с собственным VPN-шлюзом.</p>
    </div>
    <nav aria-label="Документы">
      <strong>Документы</strong>
      <a href="/terms">Оферта</a>
      <a href="/privacy">Конфиденциальность</a>
      <a href="/refunds">Возвраты</a>
    </nav>
    <nav aria-label="Информация о сервисе">
      <strong>Сервис</strong>
      <a href="/visibility">Что мы видим</a>
      <a href="/compatibility">Политика совместимости</a>
      <a href="/help">Помощь</a>
    </nav>
  </div>
</footer>`;
