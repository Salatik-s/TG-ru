#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
base=/opt/tg-ru-store
config=/etc/nginx/conf.d/tg-ru-downloads.conf
domain=tg.24projects.ru
image=node:24-bookworm-slim@sha256:c0a93797ee78814ae6d32fcca2561fdbbe423149c3f5389aad32d86e9fd85bd4
mode=${1:---plan}
checksum=${2:-}
die() { printf '%s\n' "$*" >&2; exit 1; }
[[ $EUID = 0 && ( $mode == --plan || $mode == --install ) && $checksum =~ ^[a-f0-9]{64}$ ]] ||
    die 'Run as root with --plan or --install and the verified APK SHA-256.'
for tool in docker curl nginx systemctl flock sha256sum openssl python3 ss; do
    command -v "$tool" >/dev/null || die "Missing existing host tool: $tool"
done
exec 9>/run/lock/tg-ru-downloads.lock
flock -n 9 || die 'Another TG-ru website deployment is running.'
exec 8>/run/familygram-install.lock
flock -n 8 || die 'The legacy FamilyGram deployment is running.'
systemctl is-active --quiet nginx || die 'Existing Nginx service is unavailable.'
docker image inspect "$image" >/dev/null || die 'The pinned Node image already used by FamilyGram is unavailable.'
docker compose version >/dev/null || die 'Docker Compose is unavailable.'
[[ -f $config && ! -L $config ]] || die 'Existing TG-ru vhost configuration is unavailable.'
[[ -f /opt/familygram/.familygram-owned && $(cat /opt/familygram/.familygram-owned) == familygram-v1 ]] ||
    die 'Existing verified FamilyGram installation is unavailable.'
legacy_release=$(readlink -f /opt/familygram/current)
[[ $legacy_release =~ ^/opt/familygram/releases/[a-f0-9]{16}$ ]] ||
    die 'Unexpected legacy runtime path.'
export STORE_XRAY_PATH="$legacy_release/runtime/xray"
egress=/opt/familygram/data/egress.json
[[ -f $egress && ! -L $egress && -x $STORE_XRAY_PATH && ! -L $STORE_XRAY_PATH ]] ||
    die 'Verified legacy Xray or its encrypted egress snapshot is unavailable.'
[[ -f $legacy_release/runtime/xray.sha256 && ! -L $legacy_release/runtime/xray.sha256 ]] ||
    die 'Existing Xray SHA-256 manifest is unavailable.'
(cd "$legacy_release/runtime" && sha256sum -c --status xray.sha256) ||
    die 'Existing Xray SHA-256 check failed.'
[[ -f $source_root/TG-ru.apk && ! -L $source_root/TG-ru.apk ]] || die 'Store APK missing.'
printf '%s  %s\n' "$checksum" "$source_root/TG-ru.apk" | sha256sum -c --status - ||
    die 'Store APK SHA-256 check failed.'
[[ -f $source_root/store-release.json && ! -L $source_root/store-release.json ]] ||
    die 'Store APK release metadata missing.'
for migration in schema.sql manual-grants.sql; do
    [[ -s $source_root/local-store/$migration && ! -L $source_root/local-store/$migration ]] ||
        die "Store database migration missing: $migration"
done
python3 - "$source_root" "$checksum" <<'PY'
from pathlib import Path
import json
import re
import sys
root = Path(sys.argv[1])
metadata = json.loads((root / "store-release.json").read_text(encoding="utf-8"))
if (metadata["sha256"] != sys.argv[2] or metadata["bytes"] != (root / "TG-ru.apk").stat().st_size
        or not isinstance(metadata["version_code"], int) or metadata["version_code"] <= 10018
        or not re.fullmatch(r"[0-9A-Za-z._-]{1,64}", metadata["version_name"])
        or not isinstance(metadata["min_sdk"], int) or not 21 <= metadata["min_sdk"] <= 36):
    raise ValueError("Store APK release metadata does not match its artifact")
PY

if [[ -e $base || -L $base ]]; then
    [[ -d $base && ! -L $base && -f $base/.owned && $(cat "$base/.owned") == tg-ru-store-v1 ]] ||
        die 'Unexpected existing Store directory.'
    [[ ! -L $base/data && ! -L $base/releases ]] || die 'Unexpected Store storage symlink.'
fi
previous=''
if [[ -e $base/current || -L $base/current ]]; then
    [[ -L $base/current ]] || die 'Unexpected Store current path.'
    previous=$(readlink -f "$base/current")
    [[ $previous =~ ^/opt/tg-ru-store/releases/release\.[A-Za-z0-9]{8}$ &&
        -f $previous/deploy/store/compose.yaml && -f $previous/TG-ru.apk ]] || die 'Unexpected previous Store release.'
fi
if [[ -z $previous ]] && ss -H -ltn '( sport = :48185 or sport = :48186 or sport = :28443 or sport = :28445 )' | grep -q .; then
    die 'At least one dedicated Store port is already in use.'
fi

staging=$(mktemp -d /tmp/tg-ru-store-preflight.XXXXXXXX)
config_changed=0
current_changed=0
services_changed=0
firewall_changed=0
next_link=''
compose() {
    local root=$1
    shift
    docker compose --project-directory "$root/deploy/store" -f "$root/deploy/store/compose.yaml" "$@"
}
restore() {
    local status=$?
    trap - EXIT
    if (( status )); then
        if (( config_changed )); then
            install -m 0644 "$staging/previous.conf" "$config.rollback"
            mv -Tf -- "$config.rollback" "$config"
            nginx -t && systemctl reload nginx || printf '%s\n' 'Nginx rollback needs review.' >&2
        fi
        if (( current_changed )); then
            if [[ -n $previous ]]; then
                ln -s -- "$previous" "$next_link"
                mv -Tf -- "$next_link" "$base/current"
            else
                rm -f -- "$base/current"
            fi
        fi
        if (( services_changed )); then
            if [[ -n $previous ]]; then
                compose "$previous" up -d --pull never || printf '%s\n' 'Store container rollback needs review.' >&2
            else
                compose "$release" down --timeout 10 || printf '%s\n' 'Store cleanup needs review.' >&2
            fi
        fi
        if (( firewall_changed )); then
            ufw --force delete allow 28443/tcp comment tg-ru-store || printf '%s\n' 'Store firewall rollback needs review.' >&2
        fi
    fi
    [[ -z $next_link ]] || rm -f -- "$next_link"
    rm -f -- "$staging/previous.conf" "$staging/next.conf" "$staging/legacy-before.html" "$staging/legacy-after.html"
    rmdir -- "$staging"
    exit "$status"
}
trap restore EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

https() {
    curl --noproxy '*' --resolve "$domain:443:127.0.0.1" --connect-timeout 3 --max-time 10 "$@"
}
legacy_download_status() {
    https -sS -I -o /dev/null -w '%{http_code}' "https://$domain/download/TG-ru.apk"
}
legacy_gateway() {
    curl -fsS --noproxy '*' --resolve '24projects.ru:8443:127.0.0.1' --max-time 8 https://24projects.ru:8443/health >/dev/null
}
cp -p -- "$config" "$staging/previous.conf"
python3 "$source_root/deploy/store/route.py" "$config" "$staging/next.conf"
https -fsS "https://$domain/index.html" -o "$staging/legacy-before.html"
grep -Fq 'id="tg-ru-release"' "$staging/legacy-before.html" || die 'The legacy Android metadata endpoint is missing.'
[[ $(legacy_download_status) == 401 ]] || die 'The existing APK endpoint did not return its authentication challenge.'
legacy_gateway || die 'The existing FamilyGram gateway is not healthy.'
printf '%s\n' 'Store preflight passed. Existing download, metadata, legacy gateway and encrypted route are present.'
[[ $mode == --install ]] || exit 0

install -d -m 0755 "$base" "$base/releases"
printf '%s\n' tg-ru-store-v1 > "$base/.owned"
install -d -m 0711 "$base/data"
install -d -m 0700 -o 10001 -g 10001 "$base/data/gateway"
install -d -m 0700 -o 10002 -g 10002 "$base/data/web"
env_file="$base/data/runtime.env"
[[ ! -L $env_file ]] || die 'Unexpected Store environment symlink.'
if [[ ! -f $env_file ]]; then
    { printf 'STORE_GATEWAY_SECRET='; openssl rand -hex 32; } > "$env_file"
fi
[[ $(wc -l < "$env_file") == 1 ]] && grep -Eq '^STORE_GATEWAY_SECRET=[a-f0-9]{64}$' "$env_file" ||
    die 'Invalid existing Store runtime settings.'
chmod 0600 "$env_file"

release=$(mktemp -d "$base/releases/release.XXXXXXXX")
next_link="$base/.current-${release##*/}"
cp -R -- "$source_root/server" "$source_root/local-store" "$source_root/web" "$source_root/i18n" "$source_root/deploy" "$release"
find "$release" -type d -exec chmod 0755 {} +
find "$release" -type f -exec chmod 0644 {} +
install -m 0644 "$source_root/TG-ru.apk" "$release/TG-ru.apk"
install -m 0644 "$source_root/store-release.json" "$release/store-release.json"
compose "$release" config --quiet

services_changed=1
if ! compose "$release" up -d --pull never --wait --wait-timeout 45; then
    compose "$release" logs --no-color --tail 25 web gateway >&2 || true
    die 'Store container startup checks failed.'
fi
gateway_id=$(compose "$release" ps -q gateway)
[[ $gateway_id =~ ^[0-9a-f]{12,64}$ ]] || die 'Store gateway container did not start.'
ready=0
for (( attempt=1; attempt<=20; attempt++ )); do
    if compose "$release" exec -T gateway node /app/deploy/store/verify.mjs; then
        ready=1
        break
    fi
    sleep 4
done
(( ready )) || die 'The dedicated Store gateway did not deliver an MTProto response over the encrypted route.'
curl -fsS --max-time 8 -H "Host: $domain" http://127.0.0.1:48185/ >/dev/null ||
    die 'Store backend could not serve its public virtual host.'

if command -v ufw >/dev/null && LC_ALL=C ufw status | grep -q 'Status: active'; then
    [[ -r /etc/ufw/user.rules ]] || die 'Active UFW rules cannot be checked.'
    rules=(/etc/ufw/user.rules)
    [[ ! -r /etc/ufw/user6.rules ]] || rules+=(/etc/ufw/user6.rules)
    if ! awk '
        $1=="###" && $2=="tuple" && $3=="###" && ($5=="tcp" || $5=="any") {
            if($6=="any")found=1;
            count=split($6,ports,",");
            for(i=1;i<=count;i++) {
                fields=split(ports[i],range,":");
                if(fields==1 && range[1]==28443)found=1;
                if(fields==2 && range[1]+0<=28443 && range[2]+0>=28443)found=1;
            }
        } END {exit !found}' "${rules[@]}"; then
        ufw allow 28443/tcp comment tg-ru-store
        firewall_changed=1
    fi
fi

ln -s -- "$release" "$next_link"
mv -Tf -- "$next_link" "$base/current"
current_changed=1
install -m 0644 "$staging/next.conf" "$config.store-next"
mv -Tf -- "$config.store-next" "$config"
config_changed=1
nginx -t
systemctl reload nginx

published=0
for attempt in {1..10}; do
    if homepage=$(https -fsS "https://$domain/") && [[ $homepage == *'<link rel="stylesheet" href="/preview-home.css">'* ]] &&
       https -fsS "https://$domain/preview-home.css" -o /dev/null &&
       https -fsS "https://$domain/tg-ru-mark.png" -o /dev/null &&
       metadata=$(https -fsS "https://$domain/api/store/release") &&
       python3 -c 'import json, sys; actual = json.loads(sys.argv[1]); expected = json.load(open(sys.argv[2])); assert actual["ok"] is True and all(actual[key] == expected[key] for key in ("version_code", "version_name", "min_sdk", "bytes", "sha256"))' "$metadata" "$release/store-release.json" &&
       session=$(https -fsS "https://$domain/api/store/session") && [[ $session == *'"ok":true'* ]] &&
       [[ $(https -sS -o /dev/null -w '%{http_code}' "https://$domain/_tg_ru_store_apk") == 404 ]] &&
       [[ $(https -sS -o /dev/null -w '%{http_code}' "https://$domain/downloads") == 303 ]] &&
       [[ $(https -sS -o /dev/null -w '%{http_code}' "https://$domain/downloads/TG-ru.apk") == 403 ]]; then
        published=1
        break
    fi
    sleep 1
done
(( published )) || die 'Store HTTPS checks failed.'
[[ $(https -sS -o /dev/null -w '%{http_code}' "https://$domain/v1/store-transport") == 404 ]] ||
    die 'Store WSS endpoint did not reject an ordinary HTTPS request.'
compose "$release" exec -T gateway node /app/deploy/store/verify.mjs --wss ||
    die 'Store WSS over HTTPS did not deliver an MTProto response.'
https -fsS "https://$domain/index.html" -o "$staging/legacy-after.html"
cmp -s "$staging/legacy-before.html" "$staging/legacy-after.html" ||
    die 'Existing Android update metadata changed.'
[[ $(legacy_download_status) == 401 ]] || die 'The existing protected download endpoint changed.'
legacy_gateway || die 'The existing FamilyGram gateway stopped responding.'
printf '%s\n' 'Store published. REALITY and WSS both delivered MTProto. Store HTTPS and legacy endpoints verified.'
