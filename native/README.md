# FamilyGram native MTProto transport

This directory prepares a standalone `libfamilytgnet.so` from the exact `exteraless/exteraless` commit `c83765c4151d50ba514325ddf0a3262769729dc6`. It compiles the 19 upstream tgnet translation units, `TgNetWrapper.cpp` and the original `aes_ige.c` shim; it does not rebuild WebRTC, codecs or UI. `upstream-lock.json` records the original Git blob ID of every input, including architecture-specific crypto archives. `prepare.mjs` refuses changed or missing inputs.

## Actual boundary

Every `ConnectionSocket` stream is created by `FGDial` inside `libfamilytunnel.so`. The native library has no direct TCP socket/connect/DNS fallback. The returned AF_UNIX descriptor is already connected, nonblocking and close-on-exec; tgnet retains its existing epoll/read/write logic. Remote authentication still happens asynchronously inside the transport; a locally writable descriptor is not proof of server reachability.

Optional configured Telegram SOCKS/MTProxy endpoints are reached *through* the family transport. Their existing handshake/secret behavior is preserved. `FGRelease` runs before native `close(fd)` in both normal close and destruction, preventing descriptor-reuse races and releasing the Go peer.

This is an MTProto transport boundary. Original VoIP/WebRTC sockets, Java HTTP, browser content and other native libraries are outside it. It must not be described as a complete app-wide kill switch or as proof that calls use the family gateway.

## Bootstrap contract

In **every process entry point**, before any `ConnectionsManager` native invocation or user buffer allocation:

1. Load original `libtmessages.49.so` using the original NativeLoader.
2. Load `libfamilytunnel.so`.
3. Load `libfamilytgnet.so`. Failure must terminate initialization; never catch and continue with original tgnet.
4. Call `ConnectionsManager.native_setJava(false)` and continue the gated application bootstrap.

Root's `org.telegram.messenger.family.FamilyNative` wrapper has registered JNI methods:

```java
private static native int nativeStart(String gatewayJson);
private static native void nativeStop();
private static native boolean nativeReady();
private static native String nativeLastError();
private static native int nativeRenewLease(int seconds);
```

`nativeStart` passes only the approved per-install gateway JSON to `FGStart`; it returns 0 or -1. Ready means configured engine with an unexpired lease, not authenticated/reachable remote server. Renew accepts 1..75 seconds and must only follow a successful signed server refresh. Stop cancels existing streams. LastError returns a fixed generic code; no profile, host secret, UUID or authentication material is logged.

## JNI replacement and buffer ABI

The original native registration is replaced for both ConnectionsManager and NativeByteBuffer. The additional name-bound `native_test_AuthAuthorization` method is explicitly registered to the new implementation too. Hidden visibility, `-Bsymbolic`, a linker export map and statically linked libc++ isolate the replacement's C++ globals from the original copy. Only `JNI_OnLoad` is exported.

Original SQLite JNI also hands NativeByteBuffer pointers to Java. That means there is a real cross-library ABI boundary: matching names and JNI registration alone are insufficient. The pinned NativeByteBuffer class is standard-layout, non-polymorphic and contains no STL fields. Compile-time assertions check its size. Before replacement, JNI_OnLoad initializes the **original** native JavaVM pointer without starting a network manager, allocates a buffer through the original registrations, checks its byte address/capacity/position/limit against the new implementation, checks mutations through the original accessors, and returns it to the original pool. Any mismatch aborts library loading.

That probe reduces ABI risk; it is not proof of every Android lifecycle/cross-library ownership path. The original binary must be built from the matching pinned source. Loading the replacement after the original network thread already starts is unsupported and unsafe. Device tests must cover SQLite reads, account restoration, background processes, concurrent requests and stop/restart before release. If exact binary provenance or early bootstrap cannot be established, use the full-source mode below.

## Build

Requirements: Node 24+, CMake 3.22+, Ninja and Android NDK r27c. ARM64 and x86_64 use separately pinned crypto archives. Each selected architecture must pass its own source/archive hash check. Android API 24, C++17, static libc++, RELRO, immediate binding and 16 KiB maximum page size are configured.

Windows:

```powershell
./build.ps1 -Ndk 'C:/Android/android-ndk-r27c' -CMake 'C:/Tools/cmake/bin/cmake.exe' -Ninja 'C:/Tools/ninja.exe'
```

For an emulator build add `-Abi x86_64`. `-BuildRoot C:/Build/FamilyGramNative` puts generated files and outputs in an ASCII build directory, avoiding Windows linker problems with Unicode working directories. Source/NDK paths may also be supplied through an existing verified ASCII junction; the script does not create junctions or change system settings.

Linux:

```sh
ANDROID_NDK_HOME=/opt/android-ndk-r27c ./build.sh
```

On Linux, set `ANDROID_ABI=x86_64` and optionally `FAMILYGRAM_BUILD_ROOT=/tmp/familygram-native` for the same options. ARM64 remains the default on both platforms.

Output: `<BuildRoot>/<ABI>/libfamilytgnet.so`, defaulting to `native/build/arm64-v8a/libfamilytgnet.so`. Package it beside the matching-architecture `libfamilytunnel.so` and original `libtmessages.49.so`, preserving normal upstream media libraries. `verify-elf.mjs` rejects direct socket/connect/DNS imports, unexpected exported symbols, dynamic linkage to the original library and shared libc++ linkage. It also checks the selected ELF machine, RELRO and immediate binding. The build does not copy anything into the client tree or claim APK success. An x86 emulator result is not ARM64 device validation.

## Full-source fallback

```sh
node export-full-source.mjs
```

This creates a reviewable `native/full-source-overlay/TMessagesProj/jni` directory. Apply those files to an isolated full checkout of the **same pinned commit** and run its normal native build. The overlay patches the socket implementation, adds the bridge to the original tmessages target and registers FamilyNative from original JNI_OnLoad. There is only one tgnet/NativeByteBuffer copy in this mode, so the override ABI boundary disappears. Java should load `familytunnel` before the original NativeLoader and should **not** load `familytgnet` in this alternative mode. Full original compilation still needs the original codec/WebRTC dependencies; exporting the overlay is not evidence that it compiled.

## Checks completed

On 2026-11-08, both ARM64 and x86_64 libraries compiled and linked with NDK r27c and CMake 3.31.6. ELF inspection passed for each architecture: private exports, no direct TCP/DNS imports, no original-library or shared-libc++ dependency, RELRO, immediate binding, and 16 KiB LOAD alignment. These build results do not establish Android runtime behavior or the cross-library ownership lifecycle.

`node --test test/native.test.mjs` checks pinned inputs for each supported architecture, removal of all legacy socket/DNS paths, retained receive/write/secret logic, JNI registration ordering and refusal, exact transport ABI, and release-before-close in both native cleanup paths. There are 144 inputs per selected ABI, including upstream licenses, AES-IGE shim and its crypto archive. Missing/incomplete x86 crypto fails its check; it does not weaken ARM64 validation. Native compilation, ELF inspection, x86 emulator execution and ARM64 device execution are separate checks; consult current build/test output before making a runtime claim.

Upstream tgnet is GPL-2.0-or-later, as stated in its preserved source headers. Crypto headers preserve their upstream Apache/OpenSSL notices. This integration does not replace the upstream licensing and corresponding-source obligations when distributing binaries.
