#!/usr/bin/env bash
set -Eeuo pipefail
MODE=${1:---plan}
die() { printf '%s\n' "$*" >&2; exit 1; }
[[ "$MODE" == --plan || "$MODE" == --pause ]] || die 'Usage: pause-voicerooms.sh --plan|--pause'
for command in docker awk; do command -v "$command" >/dev/null || die "Missing prerequisite: $command"; done
docker info >/dev/null
containers=()
services=()
running=$(docker ps -q --filter label=com.docker.compose.project=voicerooms)
while read -r id; do
  [[ -n "$id" ]] || continue
  [[ "$id" =~ ^[a-f0-9]{12,64}$ ]] || die 'Unexpected Docker container ID.'
  project=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$id")
  service=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' "$id")
  working=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$id")
  restart=$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$id")
  [[ "$project" == voicerooms ]] || die 'Unexpected Compose project; nothing stopped.'
  [[ "$working" == /opt/voicerooms || "$working" == /opt/voicerooms/* ]] || die 'VoiceRooms path not recognized; nothing stopped.'
  [[ "$working" != *'/../'* && "$working" != */.. ]] || die 'Unsafe VoiceRooms path; nothing stopped.'
  case "$service" in postgres|livekit|backend|frontend|bot) ;; *) die 'Unexpected VoiceRooms service; nothing stopped.';; esac
  [[ "$restart" == unless-stopped || "$restart" == no ]] || die 'Unexpected VoiceRooms restart policy; nothing stopped.'
  containers+=("$id")
  services+=("$service")
done <<< "$running"
printf '%s\n' 'Host memory before VoiceRooms pause (KiB):'
awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/ {print}' /proc/meminfo
if ((${#containers[@]} == 0)); then
  printf '%s\n' 'No running VoiceRooms containers found. Nothing stopped; installer will check available RAM.'
  exit 0
fi
printf 'Verified running VoiceRooms containers: %s\n' "${#containers[@]}"
docker stats --no-stream --format '{{.Name}}: {{.MemUsage}}' "${containers[@]}"
[[ "$MODE" == --pause ]] || { printf '%s\n' 'PLAN ONLY: no container was stopped.'; exit 0; }
# Stop application writers before the dedicated database; never remove containers or volumes.
for next in bot frontend backend livekit postgres; do
  for index in "${!containers[@]}"; do
    [[ "${services[$index]}" == "$next" ]] || continue
    printf 'Stopping VoiceRooms %s (%s)\n' "$next" "${containers[$index]}"
    docker stop --timeout 30 "${containers[$index]}"
  done
done
for id in "${containers[@]}"; do
  [[ $(docker inspect -f '{{.State.Running}}' "$id") == false ]] || die 'A VoiceRooms container is still running. Deployment stopped.'
done
printf '%s\n' 'VoiceRooms paused. Containers, volumes, source, nginx and VPN configuration retained.'
printf 'To resume later (after checking RAM): docker start'; printf ' %s' "${containers[@]}"; printf '\n'
printf '%s\n' 'Host memory after VoiceRooms pause (KiB):'
awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/ {print}' /proc/meminfo
