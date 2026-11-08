#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
BASE=/opt/familygram
MODE=${1:---plan}
SOURCE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
die() { printf '%s\n' "$*" >&2; exit 1; }
ufw_port_is_covered() {
  # UFW's own tuple records include expanded application-profile port lists.
  awk -v p="$1" '
    $1=="###" && $2=="tuple" && $3=="###" && ($5=="tcp" || $5=="any") {
      if($6=="any")found=1;
      n=split($6,ports,",");
      for(i=1;i<=n;i++) {
        count=split(ports[i],range,":");
        if(count==1 && range[1]==p)found=1;
        if(count==2 && range[1]+0<=p && range[2]+0>=p)found=1;
      }
    }
    END {exit !found}'
}
report_memory() {
  local available_kb
  available_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
  printf 'Available RAM: %s MiB (diagnostic only; no RAM admission threshold or custom container memory limits).\n' "$((available_kb / 1024))"
}
select_certificate() {
  local certificate_root=${1:-/etc/letsencrypt} candidate name archive cert_pub key_pub
  for candidate in "$certificate_root/live/24projects-24projects.ru" "$certificate_root/live/24projects.ru" "$certificate_root"/live/*; do
    [[ -d "$candidate" && ! -L "$candidate" && -s "$candidate/fullchain.pem" && -s "$candidate/privkey.pem" ]] || continue
    name=${candidate##*/}
    [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || continue
    archive="$certificate_root/archive/$name"
    [[ -d "$archive" && ! -L "$archive" ]] || continue
    [[ $(readlink -f "$candidate/fullchain.pem") == "$archive"/* && $(readlink -f "$candidate/privkey.pem") == "$archive"/* ]] || continue
    openssl x509 -in "$candidate/fullchain.pem" -noout -checkend 86400 >/dev/null 2>&1 || continue
    openssl verify -purpose sslserver -verify_hostname 24projects.ru -untrusted "$candidate/fullchain.pem" "$candidate/fullchain.pem" >/dev/null 2>&1 || continue
    cert_pub=$(openssl x509 -in "$candidate/fullchain.pem" -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum) || continue
    key_pub=$(openssl pkey -in "$candidate/privkey.pem" -pubout -outform DER 2>/dev/null | sha256sum) || continue
    [[ "$cert_pub" == "$key_pub" ]] || continue
    export FAMILYGRAM_CERT_LIVE="$candidate"
    export FAMILYGRAM_CERT_ARCHIVE="$archive"
    export FAMILYGRAM_CERT_NAME="$name"
    printf 'Using verified 24projects.ru certificate: %s\n' "$candidate"
    return 0
  done
  printf '%s\n' 'No trusted, unexpired 24projects.ru certificate with matching key was found in the existing Certbot lineages.' >&2
  printf 'Checked live directory: %s/live\n' "$certificate_root" >&2
  return 1
}
[[ "$MODE" == --plan || "$MODE" == --install ]] || die 'Usage: install.sh --plan|--install'
[[ $EUID == 0 ]] || die 'Run as root.'
[[ $(uname -m) == x86_64 ]] || die 'This release pins the amd64 Xray binary; other architectures are not supported.'
for command in docker openssl curl ss sha256sum tar readlink awk df flock python3; do command -v "$command" >/dev/null || die "Missing prerequisite: $command (nothing installed automatically)"; done
docker info >/dev/null
docker compose version >/dev/null
report_memory
[[ -f "$SOURCE/runtime/xray" && ! -L "$SOURCE/runtime/xray" ]] || die 'Missing preverified Linux Xray runtime.'
(cd "$SOURCE/runtime" && sha256sum -c xray.sha256)
docker_root=$(docker info -f '{{.DockerRootDir}}')
for disk_path in /opt "$docker_root"; do
  available_mb=$(df -Pm "$disk_path" | awk 'NR==2 {print $4}')
  [[ "$available_mb" -ge 1024 ]] || die "Less than 1 GiB free on $disk_path; no services changed."
done
select_certificate || die 'Existing site certificates were preserved; no replacement or self-signed certificate was installed.'
[[ ! -L "$BASE" ]] || die 'Refusing symlink deployment root.'
if [[ -e "$BASE" ]]; then
  [[ -f "$BASE/.familygram-owned" && $(cat "$BASE/.familygram-owned") == familygram-v1 ]] || die '/opt/familygram already exists without ownership marker.'
  for path in data releases current; do [[ ! -L "$BASE/$path" || "$path" == current ]] || die "Unexpected symlink: $path"; done
fi
while read -r container; do
  [[ -z "$container" ]] && continue
  working=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$container")
  [[ "$working" == "$BASE"/releases/*/deploy ]] || die 'An unrelated Compose project already uses familygram.'
done < <(docker ps -aq --filter label=com.docker.compose.project=familygram)
for port in 8443 18443; do
  if ss -H -ltn "sport = :$port" | grep -q .; then
    own=$(docker ps --filter label=com.docker.compose.project=familygram --filter "publish=$port" -q)
    [[ -n "$own" ]] || die "Port $port is occupied by another service. Nothing stopped."
  fi
done
HOOK=/etc/letsencrypt/renewal-hooks/deploy/familygram-reload.sh
[[ ! -e "$HOOK" ]] || grep -q '^# familygram-v1$' "$HOOK" || die 'Certificate renewal hook path belongs to another installation.'
export FAMILYGRAM_RELEASE=plan
docker compose --project-directory "$SOURCE/deploy" -f "$SOURCE/deploy/compose.yaml" config --quiet
printf '%s\n' 'Preflight passed. Scope: /opt/familygram, Compose project familygram, TCP 8443/18443, one certificate reload hook.'
printf '%s\n' 'Existing sites, host nginx, VPN instances, volumes, existing firewall rules and ports 80/443 are preserved.'
if command -v ufw >/dev/null && LC_ALL=C ufw status | grep -q 'Status: active'; then printf '%s\n' 'UFW is active: installation adds only missing TCP 8443/18443 rules with familygram comments.'; fi
printf '%s\n' 'Provider firewall cannot be changed here: allow inbound TCP 8443 and 18443 in the hosting control panel if filtering is enabled.'
[[ "$MODE" == --install ]] || exit 0
exec 9>/run/familygram-install.lock
flock -n 9 || die 'Another FamilyGram installer is already running.'
release=$(find "$SOURCE/server" "$SOURCE/deploy" "$SOURCE/runtime" -type f ! -path '*/test/*' -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -c1-16)
mkdir -p "$BASE/releases"
printf 'familygram-v1\n' > "$BASE/.familygram-owned"
target="$BASE/releases/$release"
[[ "$target" == /opt/familygram/releases/* ]] || die 'Invalid release target.'
if [[ ! -d "$target" ]]; then mkdir -p "$target"; cp -a "$SOURCE/server" "$SOURCE/deploy" "$SOURCE/runtime" "$target/"; fi
# Only public release inputs are made readable to the unprivileged container UID.
find "$target/server" "$target/runtime" -type d -exec chmod 755 {} +
find "$target/server" "$target/runtime" -type f -exec chmod 644 {} +
chmod 755 "$target/runtime/xray"
export FAMILYGRAM_RELEASE="$release"
printf 'FAMILYGRAM_RELEASE=%s\nFAMILYGRAM_CERT_LIVE=%s\nFAMILYGRAM_CERT_ARCHIVE=%s\nFAMILYGRAM_CERT_NAME=%s\n' "$release" "$FAMILYGRAM_CERT_LIVE" "$FAMILYGRAM_CERT_ARCHIVE" "$FAMILYGRAM_CERT_NAME" > "$target/deploy/.env"
compose=(docker compose --project-directory "$target/deploy" --env-file "$target/deploy/.env" -f "$target/deploy/compose.yaml")
# Pull prebuilt, digest-pinned runtimes. No apt, compiler or BuildKit on this host.
"${compose[@]}" pull access tls
report_memory
mkdir -p "$BASE/data"
chown 10001:10001 "$BASE/data"
chmod 700 "$BASE/data"
python3 "$SOURCE/deploy/import-vpn.py" --output "$BASE/data"
candidate_count=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["candidates"]))' "$BASE/data/egress.json")
egress_wait_seconds=$((candidate_count * 8 + 90))
export FAMILYGRAM_START_PERIOD="${egress_wait_seconds}s"
printf 'FAMILYGRAM_START_PERIOD=%s\n' "$FAMILYGRAM_START_PERIOD" >> "$target/deploy/.env"
printf 'Checking saved VPN routes against Telegram; scan budget up to %s seconds, stopping at the first working route.\n' "$egress_wait_seconds"
previous=''
ufw_added=()
if [[ -L "$BASE/current" ]]; then previous=$(readlink -f "$BASE/current"); [[ "$previous" == "$BASE"/releases/* ]] || die 'Invalid current release symlink.'; fi
rollback() {
  code=$?
  trap - ERR
  printf '%s\n' 'FamilyGram failure diagnostics:' >&2
  "${compose[@]}" ps -a || true
  "${compose[@]}" logs --no-color --tail 60 || true
  printf '%s\n' 'Activation failed. Rolling back only FamilyGram containers; access database is preserved.' >&2
  for port in "${ufw_added[@]}"; do ufw --force delete allow "$port/tcp" comment familygram || true; done
  if [[ -n "$previous" ]]; then
    env -u FAMILYGRAM_RELEASE -u FAMILYGRAM_CERT_LIVE -u FAMILYGRAM_CERT_ARCHIVE -u FAMILYGRAM_CERT_NAME -u FAMILYGRAM_START_PERIOD docker compose --project-directory "$previous/deploy" --env-file "$previous/deploy/.env" -f "$previous/deploy/compose.yaml" up -d --force-recreate || true
    ln -sfn "$previous" "$BASE/current"
  else
    "${compose[@]}" down || true
    if [[ -L "$BASE/current" && $(readlink -f "$BASE/current") == "$target" ]]; then rm -- "$BASE/current"; fi
  fi
  exit "$code"
}
trap rollback ERR
if command -v ufw >/dev/null && LC_ALL=C ufw status | grep -q 'Status: active'; then
  [[ -r /etc/ufw/user.rules ]] || { printf '%s\n' 'Active UFW without readable user.rules; refusing blind firewall changes.' >&2; false; }
  ufw_sources=(/etc/ufw/user.rules)
  [[ ! -r /etc/ufw/user6.rules ]] || ufw_sources+=(/etc/ufw/user6.rules)
  for port in 8443 18443; do
    # Respect any pre-existing rule for this exact port, including explicit denies.
    # Never remove or replace an owner's existing rule or widen its source range.
    if cat "${ufw_sources[@]}" | ufw_port_is_covered "$port"; then
      printf 'Existing UFW rule for %s/tcp retained; verify its allowed source range.\n' "$port"
    else
      ufw allow "$port/tcp" comment familygram
      ufw_added+=("$port")
    fi
  done
fi
"${compose[@]}" up -d --wait --wait-timeout "$egress_wait_seconds"
"${compose[@]}" exec -T tls nginx -t
curl --fail --silent --show-error --connect-timeout 5 --max-time 20 --resolve 24projects.ru:8443:127.0.0.1 https://24projects.ru:8443/health >/dev/null
access_id=$("${compose[@]}" ps -q access)
docker exec -i "$access_id" node --input-type=module - < "$SOURCE/deploy/verify-egress.mjs"
for sample in 1 2 3 4 5 6; do
  for service in access tls; do
    id=$("${compose[@]}" ps -q "$service")
    [[ -n "$id" ]] || { printf 'Missing %s container.\n' "$service" >&2; false; }
    [[ $(docker inspect -f '{{.State.Running}} {{.State.OOMKilled}} {{.RestartCount}}' "$id") == 'true false 0' ]] || { printf '%s stopped, restarted or was killed by OOM.\n' "$service" >&2; false; }
    events=$(docker exec "$id" sh -c 'if [ -r /sys/fs/cgroup/memory.events ]; then cat /sys/fs/cgroup/memory.events; fi')
    if [[ -n "$events" ]] && awk '$1=="oom_kill" && $2>0 {found=1} END {exit !found}' <<< "$events"; then
      printf '%s has a real cgroup OOM kill event.\n' "$service" >&2; false
    fi
  done
  curl --fail --silent --show-error --connect-timeout 5 --max-time 20 --resolve 24projects.ru:8443:127.0.0.1 https://24projects.ru:8443/health >/dev/null
  [[ "$sample" == 6 ]] || sleep 5
done
"${compose[@]}" stats --no-stream
report_memory
printf '%s\n' 'Runtime started and remained healthy during the startup check; no custom memory caps were applied.'
ln -sfn "$target" "$BASE/current"
install -d -m 0755 "$(dirname "$HOOK")"
cat > "$HOOK" <<'EOF'
#!/bin/sh
# familygram-v1
cd /opt/familygram/current/deploy || exit 1
lineage=$(sed -n 's/^FAMILYGRAM_CERT_LIVE=//p' .env)
[ -n "$lineage" ] && [ "$RENEWED_LINEAGE" = "$lineage" ] || exit 0
docker compose exec -T tls nginx -t && docker compose exec -T tls nginx -s reload
EOF
chmod 700 "$HOOK"
trap - ERR
printf '%s\n' 'FamilyGram health check passed. Public API: https://24projects.ru:8443'
printf '%s\n' 'Create invite: cd /opt/familygram/current/deploy && docker compose exec access node owner.mjs invite'
