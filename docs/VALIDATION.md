# Проверки Android preview11

Финальный APK: **0.1.0-preview11 / 10011**, 150 075 024 байта.

SHA-256: `788ae2174ac88ffb433b4ca13e2eafba60f9b2dd303d6edcf66745bbb7bd4659`.

Основной отчёт: [PREVIEW11-RESULT.md](../qa/saved-media/PREVIEW11-RESULT.md). Он различает финальный APK, ранее проверенный candidate с неизменёнными runtime/decoder и исторический baseline.

- Финальный Python/context/document прогон: 29 секунд. Context Run работает в сообщении без редактора; sandbox, ошибки и десятисекундный timeout прошли.
- Настоящий PDF pinch выше 150%, повышенная ширина повторного bitmap render, fit-page и переходы страниц прошли. Форматированный DOCX WebView загрузился, native zoom увеличил scale; DOCX/DOC/ODT/RTF/TXT fixtures прошли.
- Штатная транскрипция synthetic Opus voice показала native Telegram bubble. Non-Premium visibility проверена с немедленным восстановлением флага; Premium RPC не использовался.
- Системный file-input RU прошёл с runtime RECORD_AUDIO revoked и UID mic AppOp IGNORE у отдельного test-only пакета. Это известная Android 15 / AiAi-конфигурация, не универсальный результат.
- 91 PCM fixture, 25 Office fixtures и настоящие WAV/AAC/Opus/AVC+AAC MP4 codec checks прошли; cancel/corrupt/no-audio отказы проверены.
- Финальная навигация и owner UI: пять секунд. Пользовательские включённые/выключенные плагины сохранены; fresh-install default остаётся off.

Исторические неизменённые сетевые, медиапередачи, owner-cycle и history проверки сохранены в [PREVIEW9-RESULT.md](../qa/saved-media/PREVIEW9-RESULT.md); они не выдаются за повторённые для preview11. Хост не обновлялся, remote ASR/Whisper не развёртывались.

Скриншоты публикуются только для собственных synthetic fixtures. Личные записи, лица, переписки, транскрипты, коды и ключи не включены. Подробнее: [NATIVE-SPEECH.md](NATIVE-SPEECH.md), [OFFICE.md](OFFICE.md).

Звонки с собеседником, все сторонние VPN, длительная фоновая доставка и другие устройства остаются за границей этих проверок. Android Release не содержит IPA.
