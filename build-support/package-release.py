"""Package an explicitly selected signed APK and the isolated host installer."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
DELIVERY = Path("C:/Users/andre/Documents/Codex/FamilyGram")
JDK = Path("C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin/java.exe")
SIGNER = Path("C:/Users/andre/AppData/Local/Android/Sdk/build-tools/37.0.0/lib/apksigner.jar")
AAPT = Path("C:/Users/andre/AppData/Local/Android/Sdk/build-tools/37.0.0/aapt2.exe")


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while data := stream.read(1024 * 1024): digest.update(data)
    return digest.hexdigest()


parser = argparse.ArgumentParser()
parser.add_argument("--apk", type=Path, required=True)
args = parser.parse_args()
if not args.apk.is_file() or args.apk.suffix != ".apk":
    raise ValueError("Select a real signed APK")
DELIVERY.mkdir(parents=True, exist_ok=True)
marker = DELIVERY / ".familygram-release"
if not marker.exists() and any(DELIVERY.iterdir()):
    raise ValueError("Delivery directory is not owned by this project")
marker.write_text("familygram-v1\n", encoding="ascii")
apk = DELIVERY / args.apk.name
shutil.copy2(args.apk, apk)
# Windows preserves an earlier filename casing; normalize the requested artifact name.
if args.apk.name.lower() == "tg-ru.apk" and apk.name != "TG-ru.apk":
    interim = DELIVERY / "TG-ru-name-normalization.tmp"
    if interim.exists(): raise ValueError("Unexpected delivery rename marker")
    apk.rename(interim); apk = DELIVERY / "TG-ru.apk"; interim.rename(apk)
subprocess.run([str(JDK), "-jar", str(SIGNER), "verify", "--verbose", str(apk)], check=True)
badging = subprocess.check_output([str(AAPT), "dump", "badging", str(apk)], encoding="utf-8")
if "package: name='com.salatik.familygram'" not in badging:
    raise ValueError("Unexpected application ID")
(DELIVERY / "APK-INFO.txt").write_text(badging, encoding="utf-8")
source = DELIVERY / "source"
for name in ("server", "deploy", "runtime"):
    for item in (ROOT / name).rglob("*"):
        if not item.is_file() or any(part in {"node_modules", ".git", "__pycache__"} for part in item.parts): continue
        if item.name.startswith(".env") or item.suffix.lower() in {".sqlite", ".db", ".pem", ".key", ".jks", ".p12"}: continue
        target = source / name / item.relative_to(ROOT / name)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(item, target)

(DELIVERY / "Deploy.ps1").write_text('''[CmdletBinding()]
param([switch]$PlanOnly, [switch]$PauseVoiceRooms)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/source/deploy/Deploy.ps1" -PlanOnly:$PlanOnly -PauseVoiceRooms:$PauseVoiceRooms
''', encoding="utf-8-sig")
(DELIVERY / "Family.ps1").write_text('''[CmdletBinding()]
param(
 [Parameter(Mandatory=$true,Position=0)][ValidateSet('invite','list','approve','revoke')][string]$Command,
 [Parameter(Position=1)][string]$DeviceId,
 [ValidateRange(1,720)][int]$Hours=24,
 [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/source/deploy/Manage.ps1" -Command $Command -DeviceId $DeviceId -Hours $Hours -PlanOnly:$PlanOnly
''', encoding="utf-8-sig")
(DELIVERY / "Retry.ps1").write_text('''[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/source/deploy/Retry.ps1" -PlanOnly:$PlanOnly
''', encoding="utf-8-sig")
(DELIVERY / "Diagnose.ps1").write_text('''[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/source/deploy/Diagnose.ps1" -PlanOnly:$PlanOnly
''', encoding="utf-8-sig")
(DELIVERY / "Fix-Egress.ps1").write_text('''[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/source/deploy/Fix-Egress.ps1" -PlanOnly:$PlanOnly
''', encoding="utf-8-sig")
(DELIVERY / "Fix-Transport.ps1").write_text('''[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/source/deploy/Fix-Transport.ps1" -PlanOnly:$PlanOnly
''', encoding="utf-8-sig")
(DELIVERY / "Fix-Owner.ps1").write_text('''[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/source/deploy/Fix-Owner.ps1" -PlanOnly:$PlanOnly
''', encoding="utf-8-sig")
(DELIVERY / "Test-Access.ps1").write_text('''[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/source/deploy/Test-Access.ps1" -PlanOnly:$PlanOnly
''', encoding="utf-8-sig")

start = r'''TG-ru — семейный Android-клиент, заголовок чатов S.A.A.

Файл: TG-ru.apk. Обновление установленного приложения сохраняет аккаунт и допуск.
Для функций preview11 обновление хоста не требуется: обновляется только APK.
Существующие аккаунт, допуск и выбор включённых плагинов сохраняются.

С телефона владельца: Настройки → Устройства семьи.
Создать приглашение → ввести на новом телефоне → сравнить отпечаток → разрешить доступ.
Остальные одобренные устройства не становятся владельцами.

Python и просмотр документов предустановлены. На новой установке они выключены;
обновление сохраняет ваш выбор. Включить: Настройки → Плагины.

Python: кнопка «Запустить» находится прямо под блоком Python в сообщении.
Вывод появляется там же; длинный вывод можно развернуть и скопировать.
«Остановить» отменяет загрузку или выполнение. Редактор доступен из меню сообщения.
Лимит выполнения — 10 секунд после запуска среды; загрузка среды имеет отдельный
предел 30 секунд. Ошибки показывают причину, тип исключения и строку Python.
Код работает локально, результат не отправляется в чат.

Документы: PDF — переходы по страницам, увеличение двумя пальцами и двойным
касанием, повторная отрисовка с повышенной детализацией. DOCX/ODT — базовое
форматирование; DOC/RTF — текстовый просмотр. Сложная вёрстка может отличаться.

Голосовые и видеокружки: кнопка расшифровки у сообщения использует системную
модель на телефоне. Семейный хост, Whisper и Telegram Premium для этого не нужны.
Нужны Android API 33+ и проверенный провайдер файлового on-device распознавания.
Встроенная совместимость закреплена за протестированной версией Google AiAi
12872254 на Android 15; другие устройства и версии не поддерживаются автоматически.
Языковая модель должна быть установлена. Её загрузка требует сети и API 34+;
распознавание после загрузки идёт локально. Облачного или микрофонного fallback нет.
Файл до 16 МиБ и 5 минут; распознавание идёт примерно с длительностью записи.
Сохранение удалённых сообщений включено, история — в меню конкретного чата.

Команды для отдельной установки и обслуживания сервера (для этого обновления APK не нужны):

1. При необходимости установить или обновить хост. Открой PowerShell и выполни:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Users\andre\Documents\Codex\FamilyGram\Deploy.ps1"
Пароль SSH вводится в штатном приглашении. Он не записан в файлах проекта.
Если нужно освободить RAM, остановив VoiceRooms с сохранением данных, добавь -PauseVoiceRooms.
Будут проверены и остановлены только контейнеры Compose-проекта voicerooms из /opt/voicerooms.
Скрипт покажет память до/после и команду возврата VoiceRooms. Память выводится для диагностики; искусственного порога запуска нет.

2. Создать одноразовое приглашение:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Users\andre\Documents\Codex\FamilyGram\Family.ps1" invite

3. Установить APK, указать имя телефона и вставить приглашение.

4. Посмотреть ожидающие устройства:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Users\andre\Documents\Codex\FamilyGram\Family.ps1" list
Сравни отпечаток на телефоне с отпечатком в списке. Одобрить устройство:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Users\andre\Documents\Codex\FamilyGram\Family.ps1" approve ПОЛНЫЙ_ID_ИЗ_СПИСКА

5. Клиент подключится к семейному серверу и откроет обычный вход в Telegram.
Для каждого следующего телефона создай новое приглашение. Одобрение нужно один раз на установку.

Как устроено приложение
Клиент устанавливает защищённое соединение с твоим шлюзом через TLS/WebSocket на 8443; прежний VLESS/REALITY на 18443 сохранён для совместимости.
Системный VPN приложение не занимает. На хосте работают допуск устройств и relay
сигналов уведомлений; Telegram-сессия и локальная история находятся на телефоне.
Сигнал о новом сообщении не содержит текста переписки. Настройки призрака
и локальной истории: главная → ⋮ → Настройки призрака. Плагины и устройства семьи
доступны в обычных настройках. Системные настройки находятся в самом низу.

Неверный код: 5 минут ожидания на сервере. Десять ошибок: блокировка установки.
Отозвать доступ:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Users\andre\Documents\Codex\FamilyGram\Family.ps1" revoke ПОЛНЫЙ_ID_ИЗ_СПИСКА

Обновление изолировано в /opt/familygram. Существующие сайты, VPN и данные других
проектов сохраняются. При невозможности безопасной установки скрипт остановится.

Пределы проверки
Сборка, подпись, проверка допуска и зашифрованный транспорт проверяются отдельно.
Настоящие Telegram-звонки, камера, микрофон, энергосбережение и телефонный VPN
не подтверждены текущими проверками. Результаты испытаний Android 15 перечислены
в УСТРОЙСТВО-ПРОЕКТА.md и qa/saved-media/RESULT.md внутри исходников.
Хост меняется только при отдельном запуске команды установки или исправления.
Описание функций не заменяет проверку конкретного итогового APK; итоговые SHA и
результаты испытаний фиксируются при сборке. Работа над iOS/IPA отложена.
'''
(DELIVERY / "НАЧАТЬ.txt").write_text(start.replace('\\"', '"').replace('\\\\', '\\'), encoding="utf-8-sig")
shutil.copy2(ROOT / "README.md", DELIVERY / "УСТРОЙСТВО-ПРОЕКТА.md")

exclusions = {".git", "tools", "android", "client", "upstream-files", "release", "build-logs", "__pycache__"}
with zipfile.ZipFile(DELIVERY / "FamilyGram-source.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for current, directories, names in os.walk(ROOT):
        directories[:] = [name for name in directories if name not in exclusions
                          and name not in {"out", "build", "generated", ".private"}
                          and not name.endswith(".parts")
                          and not name.startswith(("work-", "build-ascii", "build-direct"))]
        for name in names:
            item = Path(current) / name
            if item.suffix.lower() in {".apk", ".ipa", ".so", ".pem", ".key", ".jks", ".p12", ".png", ".exe", ".dll", ".o", ".class", ".dex"}: continue
            if item.name.startswith(".env") or item.name == "apktool_3.0.3.jar": continue
            archive.write(item, item.relative_to(ROOT).as_posix())
    for name in ("download.py", "fetch-source.py", "ndk-subset.py", "inspect-public.mjs"):
        archive.write(ROOT / "tools" / name, "tools/" + name)
hashes = "\n".join(f"{sha(item)}  {item.name}" for item in (apk, DELIVERY / "FamilyGram-source.zip")) + "\n"
(DELIVERY / "SHA256SUMS.txt").write_text(hashes, encoding="ascii")
print("Packaged signed APK and isolated installer:", DELIVERY)
print(hashes, end="")
