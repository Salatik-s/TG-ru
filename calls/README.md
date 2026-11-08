# Native call transport candidate

This directory contains an **unreleased socket-adapter candidate**. The Android
harness, exact-original-ELF hook audit, and encrypted TCP/UDP integration passed
on Android API 30 x86_64 on 2026-11-08. Actual Telegram relay calls still require
validation before distribution. No system VPN, SOCKS listener, remote proxy setting or second
`VpnService` is introduced.

## Evidence from the pinned client

Client commit: `c83765c4151d50ba514325ddf0a3262769729dc6`.
The extracted source snapshots under `reference/` are evidence, not modified
client source. Relevant original code:

* `rtc_base/physical_socket_server.cc:155`: UDP Create enables read/write events.
* Lines 738–788: Initialize sets nonblocking mode and registers the descriptor
  before Connect/Bind. Lines 1531–1552 insert that descriptor into epoll.
* Lines 262–282: TCP Connect enables events only after the connect syscall.
* Lines 472–543: receiving can use `recvmsg` with `SCM_TIMESTAMP`, or recvfrom.
* `sdk/android/src/jni/android_network_monitor.cc:300–405` dynamically resolves
  `android_setsocknetwork` and may reject sockets when network binding fails.
* `libtgvoip/os/posix/NetworkSocketPosix.cpp:243–287` creates an IPv6 UDP socket,
  binds it, then uses getsockname; IPv4 endpoints may use mapped IPv6 addresses.

Replacing a UDP descriptor using dup2 after it is registered would invalidate
the epoll registration. This adapter instead returns a stable private AF_UNIX
socket pair at socket() time. TCP is attached to FGDial behind the stable fd.
UDP packets and endpoint metadata travel in private datagram frames, with one
FGUDPDial stream per target. No IP socket is opened by that path.

## Installer boundary

MIT xHook is pinned at `391d0c4103626bf39d54a1d9c7a10965c82ff8d2` from
https://github.com/iqiyi/xHook. One local change replaces its compile-time page
macros with `sysconf(_SC_PAGESIZE)` to support Android's 16 KiB memory pages.

Only the original `libtmessages.49.so` is targeted. Installation verifies its
GNU build ID, requires exactly one matching loaded ELF, checks required symbol
relocations, and audits every rewritten GOT slot. The Go transport and other
libraries are excluded. Partial installation restores original pointers and
memory protections before returning failure; inability to restore exits the
process. Signal-based fault suppression is disabled so a broken install cannot
be mistaken for success. The libraries must remain loaded for the process life.

Expected original build IDs:

* arm64: `28acf3ed246ca3f26c40c0993afa3e1d82f906d9`
* x86_64: `7048b8cfb006807284f88cf7aa280bd2e087b502`

The exact pinned import table includes socket/connect/bind/send/recv,
sendto/recvfrom/recvmsg, read/write/readv/writev, option/address calls, dlsym and
syscall. Known networking dlsym lookups return the wrappers. The architecture-
specific syscall gate blocks direct network syscalls and tail-calls the real
syscall function unchanged for other syscall numbers. It does not guess the
number or types of variadic syscall arguments.

## Bootstrap contract

`overlay/org/telegram/messenger/family/FamilyCalls.java` is the Java wrapper.
After original NativeLoader, libfamilytunnel, and libfamilytgnet are loaded,
call `FamilyCalls.initialize()` **before ConnectionsManager.native_init or any
VoIP initialization**. JNI_OnLoad installs and audits hooks; JNI_ERR is fatal
to bootstrap. Never catch that failure and continue with original networking.

After an approved profile successfully starts FGStart, call
`FamilyCalls.onApprovedTransport()`. On revoke/stop call `FamilyCalls.revoke()`
before FGStop, and terminate active Java VoIP sessions too. New sockets are
rejected while revoked. A worker checks FGReady at most every 50 ms and releases
each Go fd before closing it. Existing UDP sockets receive an error wakeup,
because datagram sockets do not have EOF. Native owners still own and close
their application descriptors. Kernel operations and close are serialized per
descriptor to prevent operations against a recycled fd.

## Build and tests

Windows `build.ps1 -Abi x86_64 -WorkspaceRoot <ASCII alias of familygram>` builds
the library and harnesses under `out/x86_64`; arm64-v8a is also supported. It uses
absolute output paths because LLVM/Ninja relative output paths fail in this
OneDrive Unicode working directory. CMakeLists is supplied for normal paths.

The Android `fgcalls-adapter-smoke` uses a mock transport made
only of Unix socket pairs. It exercises the pinned WebRTC ordering (epoll
registration before binding/connecting), multi-target datagrams, source
addresses, MSG_PEEK, recvmsg scatter/truncation/timestamps, mapped IPv6, TCP,
denied DNS/raw fallback, native lease loss, and 64 concurrent close/reuse cycles.
It passed on API 30 x86_64, including exact release accounting and descriptor
counts returning to baseline. It cannot validate a real gateway or Telegram call.

`fgcalls-audit <Go so> <original libtmessages.49.so> <libfamilycalls.so>` loads
the actual original ELF on Android and verifies hook installation. It passed
against the exact pinned x86_64 original: calls through its rewritten GOT see
virtual AF_INET sockets while the kernel sees AF_UNIX, and preapproval/revoke
gates reject new traffic. JNI_OnLoad runs only when loaded through Java; this
audit invokes FGCallsInstall explicitly.

With an optional fourth argument containing a test profile, the audit starts
the real Go transport and exercises TCP plus two UDP destinations through the
original ELF's GOT. This passed using `smoke-server/`, a self-contained Windows
REALITY gateway with loopback TCP/UDP echo endpoints, reached through a temporary
`adb reverse tcp:31888 tcp:31888`. The loopback UDP destinations include port
443, explicitly verified after fixing
the Go client's Vision UDP/443 switch. The fixture requires that loopback port
to be available and bindable. The gateway's ephemeral keys and test-only
loopback routing never enter a production profile. The test profile and reverse
were removed after the run. No external DNS, production gateway or Telegram
account was used. The result verifies the encrypted native transport path,
not call setup, audio/video quality, P2P, group calling or foreground/background
application lifecycle. arm64-v8a is cross-compiled and ELF-inspected, not run.

Outputs are `out/{arm64-v8a,x86_64}/libfamilycalls.so`,
`fgcalls-adapter-smoke`, and `fgcalls-audit`. Only the four FGCalls entry points,
JNI_OnLoad and the three FamilyCalls JNI methods are exported from the library.
Run the harnesses in `/data/local/tmp/familygram-calls`. Source provenance and
third-party notices are in `source-lock.json` and `THIRD_PARTY_NOTICES.txt`.

## Explicit limitations

* P2P ICE is not supported as a claimed behavior. Different FG UDP targets may
  have different server-side source ports; force Telegram P2P off and validate
  relay calls first. Group calls require their own test.
* Only literal endpoint IPs and numeric services are supported; nonnumeric DNS
  requests fail rather than use the device resolver outside the gateway.
* UDP payloads must be 1..7526 bytes. There are at most 64 virtual sockets and
  32 targets per UDP socket. Congested downstream UDP is dropped, not retried
  indefinitely. Destination-local listening/accept is unsupported.
* Buffer/time-out options are real private-socket options. IP/interface/TCP
  tuning options are virtual metadata and do not rebind the outer Go connection
  away from an already active phone VPN. Arrival timestamps are local samples.
* Duplicating virtual descriptors with dup/fcntl is not supported by the
  prototype; the inspected pinned socket paths do not do this. Direct syscalls
  for ordinary file IO are unchanged. This is a pinned-client adapter, not a
  general-purpose libc implementation or a security sandbox against hostile
  code already executing in the process.
* Java HTTP, WebView, plugins and other native libraries are outside this hook.
  This must not be described as an app-wide network kill switch.
* No physical-device VPN-coexistence or Telegram voice/video call result is
  established by the current build artifacts.
