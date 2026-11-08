# Actual APK native MTProto diagnostic

2026-11-08, task-owned Android API 30 x86_64 emulator `emulator-5582`.
Target: unchanged `release/FamilyGram-0.1.0-preview5.apk`. A separate
same-signer instrumentation APK uses only an ephemeral local test profile.
No phone number, Telegram account, production invitation or credential is used.

The test process waits for real ApplicationLoader native bootstrap, starts the
local REALITY profile, temporarily supplies an in-process access lease, then
launches the actual LaunchActivity. Production gate code and APK are unchanged.
The access lease injection is confined to this instrumentation process; this
is a native transport diagnostic, not a test of production device approval.

Observed second run, gateway hostname `localhost` through adb reverse 31888:

* Before gate: `applicationInited=false`.
* Native bootstrap: `initialized=true`; profile start returned 0, ready=true.
* After actual Launch: `applicationInited=true`.
* Actual ConnectionsManager account 0: DC 2, state 3, appPaused=false,
  appResumeCount=1.
* Account-free `TL_help_getNearestDc` queued with flags 0x1b, request token 4.
* Native logs show account 0 connect, handshake begin, receive 84 bytes,
  and handshake processing.
* Local REALITY → MTProto fixture, cumulative two runs: 45 connections,
  45 valid Obfuscated2 headers, 45 req_pq_multi, 45 returned resPQ,
  45 observed req_DH_params, zero invalid frames.

The first run used literal 127.0.0.1; the second used hostname localhost, so
Go hostname resolution also participated. Both use the real APK JNI tgnet,
epoll, socket bridge, Go engine, REALITY, Obfuscated2 and BoringSSL RSA code.
The fixture redirects all decoded destinations to its local fake DC and
returns the pinned Telegram public-key fingerprint plus a test pq value.
It deliberately stops after receiving req_DH_params: it does not possess a
Telegram private RSA key and cannot authenticate an account. Consequently the
Java request remains pending with callback=false; this is expected, not a
reported application failure. No assertion of full Telegram login is made.

The original first instrumentation attempt started before native bootstrap
finished; waiting for initialized=true corrected that test race. A subsequent
startActivitySync/waitForIdleSync waited on UI activity; replacing only that
test wait with asynchronous actual Launch and bounded state polling completed
the harness. Neither issue resulted in a production code change.

Not established: physical arm64 runtime, the user's device/DNS/VPN, reachability
of public 24projects.ru:18443 from that phone, full REALITY authentication there,
or completion of real Telegram auth.sendCode. No native production defect was
reproduced by this isolated diagnostic.
