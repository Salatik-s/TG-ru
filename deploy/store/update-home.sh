#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

base=/opt/tg-ru-store
web=tg-ru-store-web-1
staged=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
files=(web/local-store/pages.mjs web/local-store/store.css web/local-store/store.mjs i18n/ru.json)
die() { printf '%s\n' "$*" >&2; exit 1; }

[[ $EUID == 0 && -f $base/.owned && $(cat "$base/.owned") == tg-ru-store-v1 ]] ||
    die 'Managed Store installation is required.'
exec 9>/run/lock/tg-ru-downloads.lock
flock -n 9 || die 'Another TG-ru publication is running.'
release=$(readlink -f "$base/current")
[[ $release =~ ^/opt/tg-ru-store/releases/release\.[A-Za-z0-9]{8}$ ]] ||
    die 'Unexpected active Store release.'
mounts=$(docker inspect --format '{{range .Mounts}}{{println .Source .Destination}}{{end}}' "$web")
grep -Fxq "$release/web/local-store /app/web/local-store" <<< "$mounts" ||
    die 'The Store web container does not use the active release.'
grep -Fxq "$release/i18n /app/i18n" <<< "$mounts" ||
    die 'The Store web container does not use the active translations.'

for file in "${files[@]}"; do
    [[ -s $staged/$file && ! -L $staged/$file && -f $release/$file && ! -L $release/$file ]] ||
        die "Unexpected frontend file: $file"
done

backup=$(mktemp -d /tmp/tg-ru-home-backup.XXXXXXXX)
changed=0
restore() {
    status=$?
    trap - EXIT
    if (( status && changed )); then
        for file in "${files[@]}"; do install -m 0644 "$backup/${file##*/}" "$release/$file"; done
        docker restart "$web" >/dev/null || printf '%s\n' 'Store web restart needs review.' >&2
    fi
    for file in "${files[@]}"; do rm -f -- "$backup/${file##*/}"; done
    rm -f -- "$backup/legacy-before" "$backup/legacy-after"
    rmdir -- "$backup"
    exit "$status"
}
trap restore EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

site() {
    curl --noproxy '*' --resolve 'tg.24projects.ru:443:127.0.0.1' -fsS --max-time 8 "https://tg.24projects.ru$1"
}
site /index.html > "$backup/legacy-before"
for file in "${files[@]}"; do cp -p -- "$release/$file" "$backup/${file##*/}"; done
changed=1
for file in "${files[@]}"; do
    temp=$(mktemp "$release/$file.home.XXXXXXXX")
    install -m 0644 "$staged/$file" "$temp"
    mv -Tf -- "$temp" "$release/$file"
done
docker restart "$web" >/dev/null
ready=0
for attempt in {1..20}; do
    if html=$(site / 2>/dev/null) && css=$(site /store.css 2>/dev/null) &&
       [[ $html == *'id="editor-plans"'* && $html == *'name="color-scheme" content="dark"'* &&
          $html != *'id="theme"'* && $css == *'.price-highlight-editor'* ]]; then
        ready=1
        break
    fi
    sleep 1
done
(( ready )) || die 'Store homepage did not pass the post-update check.'
terms=$(site /terms)
[[ $terms == *'https://telegram.org/tos'* ]] || die 'Store terms page did not pass the post-update check.'
session=$(site /api/store/session)
[[ $session == *'"ok":true'* ]] || die 'The Store session API did not respond.'
site /index.html > "$backup/legacy-after"
cmp -s "$backup/legacy-before" "$backup/legacy-after" || die 'Legacy update metadata changed.'
curl --noproxy '*' --resolve '24projects.ru:8443:127.0.0.1' -fsS --max-time 8 'https://24projects.ru:8443/health' >/dev/null
printf '%s\n' 'New Store homepage published. Store account API and the legacy gateway passed.'
