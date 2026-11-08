# Local speech in the native Telegram bubble

Production helper: `overlay/java/org/telegram/messenger/family/FamilyVoiceTranscription.java`.
Engine is root-owned `FamilyLocalTranscriber`, not a screen. This helper uses Telegram's
existing `TranscribeButton`, custom in-memory `voiceTranscription*` fields and account
`NotificationCenter.voiceTranscriptionUpdate`. It does not send a message, invoke the
Telegram transcription RPC or write `MessagesStorage`.

## Pinned integration

`build-support/patch_voice_transcription.py` verifies all six method hashes before any
write. Run **after** the existing inline-Python patch. Default operation audits only.
`--output DIRECTORY` makes separate full class copies. Root can explicitly use
`--apply --decoded DIRECTORY` after review. Repeated application and mismatched pins
fail closed. The original methods remain renamed for audit; neither native button
entrypoint falls back to them.

1. `ChatMessageCell.setMessageObject(...ZZZZ)` restores the keyed local result before
   the original caption layout, wrapping the existing Python wrapper.
2. `updateWaveform()` retains its waveform calculation, then supplies local eligibility
   and recalculates native seekbar width. Premium and trial quota do not gate local speech.
3. `TranscribeButton.onTap()` invokes the helper, including pressed/ripple state cleanup.
4. Static `transcribePressed(...)` also invokes the helper, never its old RPC path.
5. `isTranscribing(...)` reads the local running state.
6. `showTranscribeLock(...)` returns false. Unsupported messages have no local button.

Root context action can check `handles(Object message)` and call `start(Context,Object,int)`
or `runFromChat(Object chat,Object message)`. The native button toggles a successful result,
cancels a running job, or retries a previous error. All engine callbacks are checked
against a task epoch and the current account owner/dialog/message/document/language identity.
Changing the configured language starts a new task on the next explicit tap rather than
reusing the preceding language's cached result.
Cache is memory-only, up to 32 entries and 1 MiB, each result at most 64 KiB UTF-8.
Activity references are weak. Language-model download requires one explicit confirmation
per cached message; results and errors remain in the native bubble.

## Host validation

`python qa/native-transcription/check.py` compiles the production helper with narrowly
scoped JVM doubles and verifies pending/final fields, native notification arguments,
caption refresh, no Premium dependency, native waveform visibility, no automatic run,
collapse/reopen, rebinding, account-slot owner/chat/document isolation, cancellation races,
fixed errors, secret/TTL/access guards, Unicode truncation and LRU limits. It also verifies
the six pinned wrappers and compatibility with the current Python wrapper without
modifying staging. These doubles do not validate Android rendering or speech accuracy.

Separately compiled the helper with the **real** LocalTranscriber, SystemSpeech and
PcmDecoder against Android 35. Full patched ChatMessageCell and TranscribeButton copies
assemble into DEX using `CheckSmali`; this is syntax validation, not ART/device evidence.

## Root-owned physical QA (Saved Messages only)

- Use the newly authorized voice attachment, with no transcript or personal audio in
  screenshots, logs or source files. Do not open or test other chats.
- Confirm the actual native voice button starts `FamilyLocalTranscriber`; observe its
  local running state and custom negative transcription ID rather than printing text.
- Assert pending state and native caption/bubble growth; after completion assert nonempty
  text length and `voiceTranscriptionFinal=true`, no separate Activity or panel.
- Tap to collapse/reopen without increasing engine-start count; start/cancel/retry a
  synthetic clip, scroll offscreen and back, then revisit the chat to check cell reuse.
- Confirm local microphone permission remains absent/blocked for file-only recognition.
- Verify a forced non-premium fixture still shows the same button; the real user's
  Premium account alone cannot prove that gate. Host mock already lacks `isPremium()`.
- Synthetic failure/unsupported/provider/model cases must show a fixed inline status;
  model installation can show a confirmation, never a cloud-transcription fallback.

No device was modified by these host checks. Root owns APK packaging and actual UI QA.
