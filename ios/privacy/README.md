# Native privacy overlay

Upstream: Telegram-iOS `24638bc69bd083981b178dd1e7f921eebbf7fc46`.
Module: `//privacy:FamilyPrivacy`. All five choices are opt-in and initially off.
Existing persisted choices are not reset at launch or upgrade.

`patches/privacy.py --checkout <upstream> --apply` runs **after** `patches/network.py`.
It checks every source hash, every call count, the transport-patched Network/BUILD,
and absence of the new bridge before writing. The app build copies `privacy/` to
the checkout root. The bridge is copied to TelegramCore's existing Sources glob.

## RPC behavior

The two generic `Network.request` / `requestWithAdditionalInfo` entry points check
the opt-in policy on subscription, before `requestService.add`. Suppressed requests
emit local `MTRpcError(400, FAMILY_PRIVACY_SUPPRESSED)`. They emit no successful TL
response, acknowledgement, affectedMessages or fake PTS. Audited caller catch paths
finish their pending actions normally; a global empty completion would strand some
content-read actions because their cleanup lives inside `mapToSignal`.

Exact methods:

* Typing: `messages.setTyping` (including live draft actions on that RPC).
* Presence: `account.updateStatus` only when typed `offline` is `.boolFalse`.
  Offline announcements remain intact. This cannot hide every online implication
  of other Telegram activity, including sending a message.
* Story views: `stories.readStories`, `stories.incrementStoryViews`.
* Read receipts: `messages.readHistory`, `channels.readHistory`,
  `messages.readDiscussion`, `messages.readMessageContents`,
  `channels.readMessageContents`, only when an audited caller supplies eligibility.

The patched 18 read call sites cover ordinary history synchronization, mark-all,
thread reads, content/mention/reaction consumption and live-location consumption.
Eligibility requires a cloud peer with known disabled autoremove. Content reads
also require every message to exist locally and lack both autoremove and autoclear
attributes. A mixed, missing or more-than-512-item batch follows upstream behavior.
Secret-chat RPCs, encrypted service messages, TTL handling, unknown cached peer
state, saved-history bookkeeping and other RPCs are unchanged. No read data are
queued for delayed replay by this module; switching the option off does not promise
to re-emit receipts suppressed earlier. Other clients can still send receipts.

The adapter covers these native generic request entry points, not arbitrary raw
MTProto callers or future methods outside the explicit policy. It makes no blanket
"invisible account" claim. Actual server-visible behavior still needs iOS/account
QA after a successful full app build.

## Received-message archive

StateReplay's two explicit cloud deletion handlers copy eligible, already stored
incoming text before the original deletion. Only value DTOs escape via
`Transaction.afterCommit`; no ReplayContext/Postbox/Message object is retained.
The original delete, media cleanup, tombstones, PTS and returned deletion events
are unchanged. Archive failure cannot cancel or roll back Telegram deletion.

Exclusions: outgoing, secret, TTL/autoclear, copy-protected peer/message, unknown
autoremove state, empty text. Media is never retained. Messages never received,
history clears, unavailable offline gaps, range deletions and cache eviction are
not recovered. A received deletion update is not proof of who deleted it.

Data are local AES-256-GCM with authenticated format context. The random key is in
the app-private Keychain with `AfterFirstUnlockThisDeviceOnly`; there is no plaintext
fallback. The private archive folder is excluded from backup, mode 0700; data mode
0600 and iOS UntilFirstUserAuthentication protection. No transcript/message logging,
network upload or Telegram authentication-key access is added.

Bounds: 500 records / ~2 MB text budget, 4096 characters per record, 256 records per
deletion batch, at most 8 admitted pending writes. Oldest overflow is discarded.
Entries older than 30 days are excluded on read and removed on the next successful
write; this is not a guarantee of physical erasure exactly at the 30-day boundary.
Current-account UI reads are filtered by account peer ID. Clear supports current
account or all accounts; the UI reloads instead of claiming unconditional success
if local storage/keychain is unavailable. Explicit account deletion/logout cleanup
must invoke clear if the product wants immediate archive removal at logout.

## Validation

`python privacy/test_patch.py`: fixture integrity, network ordering/dependency,
all-input validation before mutation, no changed deletion body/PTS, secret history
unchanged, audited call counts and double-application rejection.

`swift test --package-path privacy` (macOS): opt-in persistence, exact method policy,
offline/unknown passthrough, eligibility gates, bounded Unicode records, retention,
encrypted persistence, account separation, clear, wrong-key and tamper rejection.
Swift tests are authored; they were not run on Windows. Full Telegram compilation,
StateSync regression suite and device QA remain required before release claims.
