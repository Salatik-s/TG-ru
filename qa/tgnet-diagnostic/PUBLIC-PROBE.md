# Public gateway test preparation

This is a separate same-signer instrumentation APK for task-owned
`emulator-5582`, not a modification of the FamilyGram release APK.

`Run-Public.ps1 -Mode both` accepts the owner-prepared profile at
`C:/Users/andre/.codex/private/familygram-network-debug/profile.json`.
It never reads its contents into PowerShell output or a command-line argument.
The existing private PC file is retained for the owner/debug workflow.

Each variant force-stops the emulator target app, stages the profile at the
app-scoped external file `files/network-debug/profile.json`, and starts the
test runner. The runner reads and immediately deletes that file before
starting the native engine. A script finally block repeats file removal and
force-stops the emulator app. The runner never packages the supplied profile
into an APK. `build.py` includes only the separately generated local fixture
asset from the OS temporary directory.

Modes:

* `public-fqdn`: gateway host is `24projects.ru`.
* `public-ip`: gateway host is `157.228.191.231`; original REALITY server name,
  public key, short ID and device UUID remain unchanged in process memory.

The test performs actual LaunchActivity initialization and sends only anonymous
`help.getNearestDc`, flags 0x1b, through actual Java/JNI/tgnet. It waits at most
60 seconds for the callback. It never supplies a phone number, requests a login
code, reads messages or authenticates a Telegram user. The in-process access
lease injection is test-only and explicitly bypasses the enrollment UI for this
owner-authorized diagnostic; it is not shipped in the production application.

The owner's alternate six-field export (camel-case publicKey/shortId/serverName)
is normalized in test memory to the existing REALITY profile contract. Test
foreground state and the bounded lease are refreshed before the request.

Output is limited to stage names, native readiness/state, auth-key presence
(a boolean only), request/write counts,
callback class name and numeric error code. No UUID, profile, raw response,
error text or server key is printed. Public modes disable application network
logging before ConnectionsManager initialization. Do not collect raw logcat
or change this diagnostic to print profiles/responses.

`TELEGRAM_CALLBACK_OK` requires the actual `TL_nearestDc` callback with no
error. `TELEGRAM_TIMEOUT`, `TELEGRAM_CALLBACK_ERROR` and unexpected-response
results are separate. `NATIVE_REQUEST_WRITE` is advisory only: the real successful
RPC produced zero write callbacks, so zero does not establish failure to send.
Pinned tgnet calls onWriteToSocket for download/upload requests, not the generic
connection used by help.getNearestDc.
FQDN/IP outcomes are
independent fresh processes, but may reuse anonymous MTProto transport keys
stored by the target app; no Telegram user session is created.

Prepared and installed successfully on API 30. A missing-profile negative test
fails at `read_profile_public-fqdn` without starting the transport or falling
back to the local fixture.

On 2026-11-08 the first public FQDN and IP attempts timed out after 60 seconds.
The subsequent IP run, with foreground state confirmed and a fresh bounded
lease, succeeded through the unchanged preview5 native stack: auth-key presence
changed false to true, TL_nearestDc returned without error, and native state
became 3 (Connected). This demonstrates full real Telegram DH and an encrypted
anonymous RPC, not just the earlier synthetic reqPQ/resPQ fixture. The reason
for the initial timeouts is not established. This emulator evidence does not
establish the physical phone's network reliability.

A following FQDN run reused the anonymous transport key (presence stayed true)
but timed out after 60 seconds with native state 1 and a live engine lease.
Consequently the public REALITY path is demonstrably capable of full Telegram
RPC, but these runs did not establish consistent connection reliability or a
DNS-specific cause. The app-scoped profile was verified absent after testing,
and the emulator target app was stopped. The owner PC profile was retained for
the parent diagnostic workflow.
