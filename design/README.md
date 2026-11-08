# FamilyGram design handoff

- [PRODUCT.md](../PRODUCT.md) — факты брифа, рабочие допущения и открытые решения.
- [DESIGN.md](../DESIGN.md) — palette light/dark, typography, geometry, components, accessibility.
- [flows.md](flows.md) — navigation, onboarding, VPN, ghost, local history, plugins, security and acceptance states.
- [android-res/values/colors.xml](android-res/values/colors.xml) и [dark](android-res/values-night/colors.xml) — 15 semantic color roles.
- [android-res/values/dimens.xml](android-res/values/dimens.xml) — размеры и типографическая шкала.
- [android-res/values/strings.xml](android-res/values/strings.xml) — XML-ready русский текст, 85 строк.
- [validation.md](validation.md) — выполненные проверки и границы доказательств.

Пакет не меняет исходную навигацию Telegram, не добавляет фиктивные чаты и не подключён к сборке. Перед интеграцией сверить все условные возможности с фактическим кодом и API.
