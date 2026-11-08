import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync,existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const installer=readFileSync(new URL('../../deploy/install.sh',import.meta.url),'utf8');
const helper=installer.slice(installer.indexOf('ufw_port_is_covered()'),installer.indexOf('\n[[ "$MODE"'));
const bash=process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash';
test('UFW coverage respects existing exact, ranged, application-profile, restricted and deny rules', {skip:process.platform==='win32'&&!existsSync(bash)},()=>{
  const covered=input=>spawnSync(bash,['-c',helper+'\nufw_port_is_covered 8443'],{input,encoding:'utf8'}).status===0;
  assert(covered('### tuple ### allow tcp 8443 0.0.0.0/0 any 1.2.3.4/32 in\n'));
  assert(covered('### tuple ### deny tcp 8000:9000 0.0.0.0/0 any 0.0.0.0/0 in\n'));
  assert(covered('### tuple ### allow tcp 443,8443 0.0.0.0/0 any 0.0.0.0/0 in_app\n'));
  assert(covered('### tuple ### allow any any 0.0.0.0/0 any 1.2.3.4/32 in\n'));
  assert(!covered('### tuple ### allow udp 8443 0.0.0.0/0 any 0.0.0.0/0 in\n'));
  assert(!covered('### tuple ### allow tcp 22,443 0.0.0.0/0 any 0.0.0.0/0 in\n'));
  assert.match(installer,/for port in 8443 18443/);
  assert.match(installer,/for port in "\$\{ufw_added\[@\]\}"; do ufw --force delete allow "\$port\/tcp" comment familygram/);
});
test('owner wrapper accepts only full hexadecimal ID and source packaging includes relay',()=>{
  const manage=readFileSync(new URL('../../deploy/Manage.ps1',import.meta.url),'utf8');
  assert(manage.includes('\\A[a-fA-F0-9]{64}\\z'));
  const deploy=readFileSync(new URL('../../deploy/Deploy.ps1',import.meta.url),'utf8');
  assert(deploy.includes("'server/push.mjs'"));assert(deploy.includes("'deploy/Manage.ps1'"));
});

test('VoiceRooms pause validates every container before stopping, preserves other projects and honors plan', {skip:process.platform==='win32'&&!existsSync(bash)},()=>{
  const pause=readFileSync(new URL('../../deploy/pause-voicerooms.sh',import.meta.url),'utf8');
  const mock=String.raw`
docker() {
  case "$1" in
    info) return 0 ;;
    ps) [[ "$FG_PAUSE_CASE" == empty ]] || printf 'aaaaaaaaaaaa\nbbbbbbbbbbbb\n' ;;
    inspect)
      case "$3" in
        *project.working_dir*)
          if [[ "$FG_PAUSE_CASE" == path && "$4" == bbbbbbbbbbbb ]]; then printf '/opt/other'; else printf '/opt/voicerooms/releases/abc'; fi ;;
        *compose.project*)
          if [[ "$FG_PAUSE_CASE" == project && "$4" == bbbbbbbbbbbb ]]; then printf 'other'; else printf 'voicerooms'; fi ;;
        *compose.service*)
          if [[ "$FG_PAUSE_CASE" == service && "$4" == bbbbbbbbbbbb ]]; then printf 'unknown'; elif [[ "$4" == aaaaaaaaaaaa ]]; then printf 'postgres'; else printf 'backend'; fi ;;
        *RestartPolicy*)
          if [[ "$FG_PAUSE_CASE" == restart ]]; then printf 'always'; else printf 'unless-stopped'; fi ;;
        *State.Running*) printf 'false' ;;
        *) return 41 ;;
      esac ;;
    stats) printf 'mock memory snapshot\n' ;;
    stop) printf 'STOP:%s\n' "$4" ;;
    *) printf 'UNEXPECTED_DOCKER_OPERATION\n'; return 42 ;;
  esac
}
`;
  const run=(scenario,mode='--pause')=>spawnSync(bash,['-c',mock+'\nset -- '+mode+'\n'+pause],{encoding:'utf8',env:{...process.env,FG_PAUSE_CASE:scenario}});
  for(const scenario of ['path','project','service','restart']) {
    const r=run(scenario); assert.notEqual(r.status,0,scenario); assert(!r.stdout.includes('STOP:'),scenario+' stopped a container');
  }
  const plan=run('valid','--plan'); assert.equal(plan.status,0,plan.stderr); assert(!plan.stdout.includes('STOP:'));
  const empty=run('empty'); assert.equal(empty.status,0,empty.stderr); assert(!empty.stdout.includes('STOP:'));
  const paused=run('valid'); assert.equal(paused.status,0,paused.stderr);
  assert.deepEqual([...paused.stdout.matchAll(/STOP:([a-f0-9]+)/g)].map(m=>m[1]),['bbbbbbbbbbbb','aaaaaaaaaaaa']);
  assert(!paused.stdout.includes('UNEXPECTED_DOCKER_OPERATION'));
});

test('RAM is diagnostic only per owner request; startup checks actual failures without custom caps', {skip:process.platform==='win32'&&!existsSync(bash)},()=>{
  const memory=installer.slice(installer.indexOf('report_memory()'),installer.indexOf('\n[[ "$MODE"'));
  for(const available of [447300,418816,425983,0]) {
    const r=spawnSync(bash,['-c','die() { exit 9; }; awk() { printf "%s" "$FG_AVAILABLE_KB"; };\n'+memory+'\nreport_memory'],{encoding:'utf8',env:{...process.env,FG_AVAILABLE_KB:String(available)}});
    assert.equal(r.status,0,'available KiB '+available+': '+r.stderr);
  }
  const compose=readFileSync(new URL('../../deploy/compose.yaml',import.meta.url),'utf8');
  assert(!/mem_limit|memswap_limit|NODE_OPTIONS|GOMEMLIMIT/.test(compose));
  assert(!/^\s+build:/m.test(compose)); assert(!installer.includes('build --pull'));
  assert.match(compose,/node:24-bookworm-slim@sha256:[a-f0-9]{64}/);
  assert.match(compose,/nginx:1.28-alpine@sha256:[a-f0-9]{64}/);
  assert(installer.includes('sha256sum -c xray.sha256'));
  assert(installer.includes("'{{.State.Running}} {{.State.OOMKilled}} {{.RestartCount}}'"));
  assert(installer.includes('/sys/fs/cgroup/memory.events'));
  assert(installer.includes('--connect-timeout 5 --max-time 20'));
  assert(installer.includes('logs --no-color --tail 60'));
});

test('TLS reuses a verified Certbot lineage with matching archive path and renewal hook',()=>{
  const compose=readFileSync(new URL('../../deploy/compose.yaml',import.meta.url),'utf8');
  assert(installer.includes('live/24projects-24projects.ru'));
  assert(installer.includes('-verify_hostname 24projects.ru'));
  assert(installer.includes('"$cert_pub" == "$key_pub"'));
  assert(compose.includes('${FAMILYGRAM_CERT_LIVE:?certificate required}:/etc/letsencrypt/live/24projects.ru:ro'));
  assert(compose.includes('/etc/letsencrypt/archive/${FAMILYGRAM_CERT_NAME:?certificate name required}:ro'));
  assert(installer.includes('FAMILYGRAM_CERT_LIVE=%s'));
  assert(installer.includes('[ "$RENEWED_LINEAGE" = "$lineage" ]'));
  assert(!compose.includes('/etc/letsencrypt:/etc/letsencrypt'));
});
