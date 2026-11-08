# FamilyTransport (TCP source implementation)

Native Swift VLESS v0 over `wss://24projects.ru:8443/v1/transport`.
MTProtoKit keeps the MTProto encryption, DC selection, authorization and media
sessions. Each connection's original IPv4, IPv6 or DNS destination goes in the
VLESS request. The phone opens only the fixed gateway WSS for these connections.
This is an application transport; it does not create a system VPN, loopback proxy,
NetworkExtension or TUN interface. It can coexist with the phone's VPN.

## Status and verification

Source and pinned patch are implemented. **Not Apple-compiled or device-tested.**
This Windows workspace has no Swift toolchain or Apple SDK. Do not describe this
as a working iOS release until the checks below pass on macOS and an iPhone.

Executed here: `python patches/network_test.py -v` — 4 tests passed. They check
exact upstream hashes, removed direct socket/HTTP paths, fail-before-write on
every changed input, preservation of unrelated files and rejection of reapply.
These are patch tests, not Swift runtime evidence.

Authored Swift XCTest cases cover VLESS wire bytes and every split of the response
header, IPv4/IPv6/domain destinations, >256 KiB tail at EOF, partial read progress,
buffer limits, one send in flight, sliced Data offsets, repeated chunks, lease
renew/rotate/revoke and expiry after simulated device sleep. Run on macOS:

```sh
swift test --package-path transport
```

That package tests `Sources/Core` only. The actual adapter must also compile in
Telegram's Bazel build (`//transport:FamilyTransport` depends on MtProtoKit), then
be exercised with a real trusted WSS fixture and real MTProto anonymous requests.
Required device cases: initial DH/help.getNearestDc, DC migration, multi-DC media,
large upload/download with digest comparison, background/resume, slow fragmented
responses, abnormal EOF, gateway loss, revoke and lease expiry during traffic.
Capture destination metadata to establish that no Telegram socket bypasses WSS.
No real account or private message is needed for the transport fixture tests.

## Root integration contract

No dependency on FamilyCore; composition belongs to the app:

```swift
FamilyAccess.shared.onAuthorizationChanged = { uuid, seconds in
    if let uuid {
        FamilyTransportLease.shared.authorize(uuid: uuid, validFor: seconds)
    } else {
        FamilyTransportLease.shared.invalidate()
    }
}
// Also check before resuming Telegram work when entering the foreground:
FamilyTransportLease.shared.checkValidity()
```

`authorize` rejects zero UUIDs and intervals outside `(0, 75]`. A same-UUID refresh
keeps live streams; rotation, expiry or invalidate cancels every registered flow.
Darwin's continuous monotonic clock includes device sleep. Timers run when iOS
schedules the process; every I/O and queued data callback checks the clock, so
resume cannot reuse an expired lease. Each extension process starts unauthorized
and needs its own authenticated refresh. Never embed or persist a gateway UUID
in a build artifact, shared preference or source file.

The network patch installs this factory at initial Network creation and on proxy
settings updates; it pins the MTProtoKit engine and disables engine switching:

```swift
context.makeTcpConnectionInterface = { delegate, delegateQueue in
    FamilyTcpConnectionInterface(delegate: delegate, delegateQueue: delegateQueue)
}
```

All existing derived MTContext instances must retain the factory. The pinned
backup-address source is checked for that copy. MTProtoKit now refuses a missing
factory or conflicting proxy settings instead of constructing a direct socket.
Application minimum deployment target must be iOS 13 or later.

## Patch application

Official source revision: `24638bc69bd083981b178dd1e7f921eebbf7fc46`.
`patches/network-fixtures` contains the six original, Git-blob-verified small
files used for offline tests. The upstream GPL licensing remains applicable;
these files are not a separately licensed replacement for Telegram.

```sh
python patches/network.py /path/to/pinned/Telegram-iOS --diff
python patches/network.py /path/to/pinned/Telegram-iOS --apply
```

Run this before privacy or other overlays that edit Network.swift/TelegramCore
BUILD. All six SHA256 values are checked before the first write. Five files are
changed. The command prints only revision and public source hashes. A changed,
partially patched or different checkout is rejected; discard that build checkout
and begin again from the pinned source. Copy this directory to checkout/transport
before Bazel compilation. No CI or host mutation is performed by these tools.

## Stream and TLS properties

System certificate chain, expiration and hostname verification stay enabled.
Redirects, HTTP authentication and text WS messages are rejected. No cookies,
credential storage or URL cache is used. Request UUID and destination are inside
TLS; there is no custom HTTP authorization header. DNS for the gateway itself uses
the system resolver. DC DNS names are transported to the gateway without local
DC resolution. The code does not promise to tunnel system DNS, APNs or other apps.

One WS send is in flight at a time, sliced into at most 64 KiB frames. Write
admission is bounded before dispatch captures Data; saturation closes the flow
rather than silently dropping bytes. Exact reads retain arbitrary frame splits,
report partial progress to MTProtoKit and drain buffered tail before EOF. Read
storage and pending requests are bounded; terminal draining has a five-second
deadline. WS has no TCP half-close API: remote closure drains already delivered
messages, then emits one disconnect; a local disconnect cancels the flow.

## Explicit limitations

This first implementation covers MTProtoKit TCP only. Voice/video/group calls
still use tgcalls/WebRTC packet socket factories; **keep those features disabled**
until a separate UDP/RTC transport is integrated and tested. No claim of a whole
application network kill switch is made. In-app browser/WebView, external URLs,
maps, APNs and third-party SDK networking need separate policy/integration.

Direct MTHttpRequestOperation and fetchHttpResource paths deliberately return an
error. This blocks direct backup-address HTTP and raw HTTP resources rather than
letting them bypass the gateway. Ordinary MTProto media uses its DC TCP sessions.
HTTP resource features need a future tunneled implementation before enabling.

The first factory sends no Vision flow or XUDP. Server inbound must accept plain
VLESS TCP over WS at the existing path, with the approved per-device UUID and
the server's selected encrypted VPN egress. No server change is made here.
