# Native call transport verification, 2026-11-08

Environment: Android API 30 x86_64 emulator, original pinned libtmessages.49.so,
real Go libfamilytunnel.so. No Telegram account or application UI was used.

## Results

1. `fgcalls-adapter-smoke`: PASS. Stable epoll descriptor before Connect/Bind;
   TCP; multi-target UDP and source addresses; MSG_PEEK; scatter receive,
   truncation and timestamps; mapped IPv6; numeric-only DNS and raw-socket
   rejection; syscall gate; lease loss and explicit stop/resume; 64 concurrent
   close/reused-descriptor cycles. Every mock backend released exactly once;
   process descriptor count returned to its baseline.
2. `fgcalls-audit`: PASS against the actual original ELF. Installation returned
   0 and installed status 1; original GOT calls reached virtual sockets whose
   kernel family is AF_UNIX. Address/bind/close and preapproval/revoked gates
   passed. The real Go library remained loaded separately.
3. Actual encrypted integration: PASS. Original ELF GOT -> socket adapter ->
   Android Go Xray -> VLESS/REALITY -> Windows loopback TCP echo and two distinct
   UDP echo destinations. A temporary adb reverse exposed only the local test
   gateway; destination echo servers ran on Windows, so Android loopback could
   not answer them directly. The fixture generated ephemeral gateway keys and
   used a public fixed test UUID. Test profile, fixture process and adb reverse
   were removed after the run.
4. Final linker export restriction: both ABI rebuilt; Android smoke and
   original ELF audit repeated successfully. Exactly eight public FGCalls/JNI
   entry points remain; all LOAD segments align to 0x4000. Runtime dependencies
   are libdl, liblog, libm and libc. arm64-v8a is compiled and inspected only.
5. UDP/443 regression: the pinned Xray client's normal Vision flow rejected
   destination UDP/443. A real encrypted loopback test reproduced EOF before
   the fix. The internal client flow now adds Xray's `-udp443` switch, which
   the core removes before wire encoding; public profiles and server flow
   remain unchanged. Windows tests passed at sizes 1, 53, 1200 and 7526 bytes
   on both random UDP and UDP/443 destinations. The actual Android original-GOT
   encrypted integration was repeated with UDP echo destinations 63501 and
   443 and passed, plus TCP. Both Go ABI libraries were rebuilt; their final
   hashes are recorded in `../tunnel/README.md`. Calls library hashes below
   remain unchanged. The temporary fixture, profile and adb reverse were removed.

Final `libfamilycalls.so` SHA256:

| ABI | Bytes | SHA256 |
| --- | ---: | --- |
| arm64-v8a | 432912 | 2e15de9cc74c85f99c35d626e865007fb532509741b20f94810a40d37fa3620d |
| x86_64 | 381384 | 9fbaf1d14bcda71604e7141d68807b28c89786e9d9f70a2787689434419b06a9 |

## Remaining validation

Actual Telegram voice/video relay and group calls, media quality, full Android
foreground/background lifecycle, physical arm64 devices, existing phone VPN
coexistence and 16 KiB page devices were not tested. This verifies a native
transport implementation, not end-to-end Telegram calling. Force P2P off before
app integration. Java HTTP, WebView and other libraries are outside this hook.
