# Локальный запуск Billing и сайта TG-ru

Billing применяет миграции к собственной SQLite-базе перед запуском HTTP. У сайта есть стартовая страница Store и собственный `/health`. Оба процесса по умолчанию доступны только на loopback. В этой версии ещё нет регистрации, оплаты, аккаунтов и обмена со шлюзом.

## Windows и Node.js 24

В первом терминале PowerShell из корня проекта:

```powershell
$env:TG_RU_BILLING_DATA_DIR = Join-Path ([IO.Path]::GetTempPath()) ('tg-ru-local-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $env:TG_RU_BILLING_DATA_DIR | Out-Null
node billing/main.mjs
```

Во втором терминале из корня проекта:

```powershell
node web/main.mjs
```

Страница открывается на [127.0.0.1:8082](http://127.0.0.1:8082/). Состояние сервисов можно проверить на [Billing](http://127.0.0.1:8081/health) и [Web](http://127.0.0.1:8082/health). Для переноса на другой локальный порт задайте `TG_RU_BILLING_PORT` или `TG_RU_WEB_PORT` до запуска. Переменные `TG_RU_BILLING_HOST` и `TG_RU_WEB_HOST` принимают IP-адрес привязки. Остановить процесс можно клавишами `Ctrl+C` в его терминале.

## Docker Compose на Linux

```sh
docker compose -f deploy/foundation/compose.yaml up --build --wait
```

Compose-проект `tg-ru-foundation` создаёт сеть `billing`, отдельную сеть `web` и том `billing-data`. Billing работает как UID `10002`, Web как `10003`, существующий Gateway как `10001` в отдельном проекте. У Billing нет опубликованного порта. Web опубликован только на `127.0.0.1`. Подключение Gateway к новым сетям и доступ к его `/data` отсутствуют в манифесте. [Сети Compose](https://docs.docker.com/reference/compose-file/networks/).

Остановка с сохранением тома:

```sh
docker compose -f deploy/foundation/compose.yaml down
```

## Проверки

```sh
node --test billing/test/database.test.mjs foundation/test/services.test.mjs
node deploy/foundation/verify-isolation.mjs --config-only
node deploy/foundation/verify-isolation.mjs
```

Первая команда тестирует процессы и миграцию. Вторая проверяет развёрнутый конфиг Compose. Третья создаёт одноразовый Compose-проект с отдельным имитатором Gateway, проверяет доступ к SQLite и доступность контейнеров по IP, затем удаляет созданные тестовые ресурсы. Для третьей нужен запущенный Docker Engine с Linux-контейнерами. [Разделение томов Docker](https://docs.docker.com/engine/storage/volumes/).
