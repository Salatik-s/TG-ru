# Familygram application transport

This is an **application-scoped encrypted VLESS transport**, not an
Android system VPN. It does not create a TUN interface, request `VpnService`,
change device routes, or start a local SOCKS/HTTP/MTProxy listener. The remote
gateway is Xray VLESS over REALITY/TCP or verified TLS/WebSocket. Calling it a system VPN would be inaccurate.

Telegram TCP bytes → native `AF_UNIX` socket pair → embedded Xray dispatcher →
VLESS Vision over REALITY/TCP → the private gateway → Telegram destination.

The HTTPS transport uses VLESS without Vision, TLS and WebSocket at
`24projects.ru:8443/v1/transport`. The profile validator fixes the host, SNI,
port and path, and rejects REALITY keys, flow or fingerprint in this mode.
TLS requires the system certificate authorities and matching server name;
`allowInsecure` is false. ALPN is HTTP/1.1 and curves are X25519/CurveP256 to
avoid the larger hybrid key share on this compatibility path. No global trust
store or Android network settings are changed. TCP and UDP/XUDP use the same
dispatcher and lease controls as REALITY. This library chooses one profile;
profile preference/reconnection belongs to the application layer.

The local WSS regression uses an ephemeral CA only in test configuration,
checks rejection of untrusted certificates and wrong SNI, exchanges reqPQ/resPQ
with a fixture (including its nonce), a 176 KiB TCP payload and UDP packets of
1, 53, 1200 and 7526 bytes at random and 443 ports. It does not authenticate a
Telegram account or establish physical-phone/public-gateway reachability.

The application's traffic remains subject to an already enabled phone VPN.
No `VpnService.protect()` bypass is used. A phone VPN that blocks the gateway,
or excludes this app, changes connectivity accordingly. Simultaneous operation
needs physical device testing; it is not proven by desktop integration tests.

## Sources and build

* Xray core: `v26.10.31`, upstream commit
  `a4a3dff5b20c2faae567b742c172c7b95e75c083`, Go module
  `v1.260327.1-0.20261031132019-a4a3dff5b20c`.
* Go: 1.27.3. Official Windows archive SHA256:
  `f26081f438319757f8a1b46d7d0b2c5ec7583d4038e485e0aa725cfde7885fff`.
* Android build script uses NDK r27c, API 24, arm64-v8a by default and 16 KiB
  ELF page alignment. The downloaded NDK subset contains arm64 and x86_64 files;
  pass a complete NDK to build the script's armeabi-v7a target.
  As required by the pinned Xray Android build instructions, linking uses
  `-checklinkname=0` for the `anet` dependency's Android network-interface hook.

Run `go test -ldflags=-checklinkname=0 -v ./...` with Go 1.27+, then
`./build-android.ps1` in PowerShell. The local integration test requires an
available, bindable loopback UDP port 443 as well as ephemeral TCP/UDP ports.
The script emits `out/<abi>/libfamilytunnel.so` and Go's generated C header.
Copy each library into the corresponding Android `jniLibs/<abi>` directory
and link the native tgnet bridge against it. Bundle `THIRD_PARTY_NOTICES.txt`
with the application's notices; it includes the Go, Xray and linked module
licenses.

The arm64 and x86_64 libraries were successfully cross-compiled and inspected:
ELF64 AArch64 and x86-64, 16 KiB LOAD alignment, seven exported FG functions, dependencies only
on Android `liblog.so`, `libdl.so`, and `libc.so`. The actual x86_64 library also
passed encrypted TCP and UDP integration on an Android API 30 emulator through
the pinned original Telegram ELF's hooked socket imports; see
`../calls/TEST-REPORT.md`. arm64 is cross-compiled and inspected only.

`smoke/android_abi_smoke.c` is a minimal device-side ABI harness. Compile it with
the same NDK target and `-ldl`, push it and the library to `/data/local/tmp`, then
run `fg-abi-smoke /data/local/tmp/libfamilytunnel.so`. It checks loading, exported
entry points, TCP/UDP socket types, nonblocking/CLOEXEC flags, descriptor
ownership and lease expiry. Its only profile points to closed loopback port 1;
it does not prove real gateway connectivity or the Telegram JNI integration.

Source API reference: https://github.com/XTLS/Xray-core/blob/v26.10.31/core/functions.go

## Native contract

`include/familygram_transport.h` documents the ABI:

* `FGStart(profile)` validates and starts a profile, returning 0 on success.
  Successful replacement cancels old connections. Failed start preserves the
  previous working engine. The profile must never be written to logs.
* `FGReady()` reports whether an engine is configured. It does **not** prove
  successful gateway authentication, reachability, or Telegram connectivity.
* Every engine has a 75-second lease measured on Android's monotonic
  `CLOCK_BOOTTIME`, including deep sleep. Desktop tests use Go's monotonic clock.
  `FGRefreshLease(75)` renews it only while still valid. The app must call this
  only after authenticated backend renewal (normally every 60 seconds).
  A one-second native watchdog actively cancels expired streams even if Java
  watchdogs are delayed; IO also checks expiry before writes and after reads;
  `FGReady` and new dials also reject expired leases. An expired engine needs a
  newly authorized `FGStart`; renewal cannot resurrect it.
* `FGDial(host,port)` returns a nonblocking, close-on-exec, already connected
  `AF_UNIX` stream fd, or -1. The caller owns the returned fd and closes it
  exactly once. Register it with epoll and perform normal reads/writes. Do not
  call `connect()`, `TCP_NODELAY`, or IP-specific socket options on this fd.
  The Xray connection is asynchronous; remote handshake failure surfaces as
  EOF. Existing Telegram connection timeouts must remain active.
* `FGStop()` cancels all engine flows. Caller-owned TCP descriptors receive EOF
  but remain caller-owned; it never closes a potentially recycled native fd.
  UDP has no EOF, so its native owner must also cancel operations and close its
  descriptors on revocation, stop, or failed readiness checks.
* `FGUDPDial(ip,port)` is a fixed-destination datagram primitive. It returns a
  connected `AF_UNIX SOCK_DGRAM` fd; each `send`/`recv` is one packet. Destination
  must be a literal IP. Passing an IP sockaddr to `sendto()` on that descriptor
  is unsupported. It is not a drop-in replacement for a multi-peer UDP socket.
  Payloads must contain 1..7526 bytes; invalid sizes are rejected rather than
  silently dropped by the pinned core's XUDP writer (8192 bytes minus 666 bytes
  reserved headroom).
  UDP destination port 443 is supported: internally Xray's client-only
  `xtls-rprx-vision-udp443` switch permits it, while the profile, server and wire
  flow remain `xtls-rprx-vision`. Pinned Xray strips the suffix before encoding
  requests (`proxy/vless/outbound/outbound.go:246–257`). Without that switch the
  core deliberately rejects UDP/443, including potential call relay traffic.
* Call `FGRelease(fd)` **before** native `close(fd)` (mandatory for datagrams,
  recommended for TCP). Unix datagram socket pairs do not reliably signal EOF
  when a peer closes, so the explicit release cancels both pumps. Release never
  closes the caller-owned fd; do not release an already closed/reused fd.

The two pumps close both Go streams when either direction completes. This
matches Telegram's long-lived bidirectional connection model, but deliberately
does not implement a general-purpose TCP half-close API. Go duplicates only
the private socket-pair endpoint, closes the original, and never retains the
native-owned endpoint. Cancelling a stream also cancels its Xray context.

The caller must fail closed when private transport is required but unavailable.
It must not silently fall back to a direct TCP socket after `FGDial` fails.

## Tests and remaining coverage

Verified on Windows with Go 1.27.3: five top-level tests passed, including ten
invalid-profile subtests; the live-gateway test was explicitly skipped. These
checks cover profile rejection, one encrypted outbound/no listeners, engine
shutdown, active stream cancellation on lease expiry, and an actual local
VLESS/REALITY encrypted round trip: 126976 TCP bytes and UDP packets of 1, 53,
1200 and 7526 bytes, on both an ephemeral destination port and UDP/443. The new
UDP/443 regression reproduced EOF before the client switch and passed after it.
Invalid UDP sizes are rejected. The temporary camouflage
endpoint uses an ECDSA certificate compatible with Chrome's TLS fingerprint.
Only the test server allows its loopback echo destinations through Xray's
private-IP protection. Test keys are generated in memory; the fixed test UUID
is not a deployed device credential.

`FG_TEST_PROFILE=<private JSON path> go test -run TestLiveRealityGateway -v`
additionally requests `https://telegram.org/` through a provisioned gateway.
Without that environment variable this live test is explicitly skipped.

**Actual Telegram voice/video calls remain unverified.** The C ABI provides a
fixed-destination UDP primitive using `core.DialUDP`; the separate `../calls`
adapter adds stable socket identity and multi-target UDP to the pinned native
client. Its actual original-ELF encrypted integration passed on Android,
including destination UDP/443. This does not validate ICE/STUN/TURN behavior,
call setup, media quality or application lifecycle. P2P must be disabled until
separately supported. Java HTTP, WebView and other libraries are outside that
adapter. This is not a device-wide VPN or app-wide traffic guarantee. The
gateway hostname itself is resolved by the device networking stack.

No desktop test verifies the Android JNI/epoll adapter, production credentials,
phone VPN coexistence, or a physical device Telegram session. Record those
checks separately after producing and installing the APK.

After the UDP/443 fix, `libfamilytunnel.so` SHA256:

* arm64-v8a, 33267536 bytes:
  `cb08cc3c1a89780a72c9f8f9f50723f87f636dc1a37aeb3fd43fd87e0d29cf14`.
* x86_64, 35574160 bytes:
  `5b8a7973ce9285f5f65a0da28b1e7e71455a6dec1a1292e3a6197fe442218127`.
