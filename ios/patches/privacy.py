#!/usr/bin/env python3
"""Narrow, hash-guarded privacy overlay. Apply AFTER network.py; no downloads.

This does not replace Telegram's database or fake TL replies. All edits are
prepared before writes. A failed build must discard its temporary checkout.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PRIVACY = ROOT / "privacy"
CORE = "submodules/TelegramCore/"
NETWORK = CORE + "Sources/Network/Network.swift"
REPLAY = CORE + "Sources/State/Sync/StateReplay+Messages.swift"
BRIDGE = CORE + "Sources/Network/FamilyPrivacyBridge.swift"
BUILD = CORE + "BUILD"

def once(source, old, new):
    if source.count(old) != 1:
        raise ValueError("Privacy source anchor changed: " + old[:90])
    return source.replace(old, new, 1)

def network(source):
    # Limit edits to these two audited generic RPC entry points. They are shared
    # by the app's MTProtoKit engine, including requestWithAdditionalInfo.
    for name in ["requestWithAdditionalInfo", "request"]:
        start = source.index("    func " + name + "<T>(")
        end = source.index("\n    }", start) + len("\n    }")
        region = source[start:end]
        region = once(region, "onFloodWaitError: ((String) -> Void)? = nil)",
                      "onFloodWaitError: ((String) -> Void)? = nil, familyPrivacyReadEligible: Bool = false)")
        region = once(region, "        return Signal { [requestService] subscriber in\n", """        return Signal { [requestService] subscriber in
            if familyPrivacySuppressRequest(data.0, readEligible: familyPrivacyReadEligible) {
                // Local cancellation, not a server success. Existing catch paths
                // clear pending actions without inventing affectedMessages/PTS.
                subscriber.putError(MTRpcError(errorCode: 400, errorDescription: "FAMILY_PRIVACY_SUPPRESSED"))
                return EmptyDisposable
            }
""")
        source = source[:start] + region + source[end:]
    return source

def requests(source, methods, expected, *, eligible=None, wrapper=None, content_ids=None):
    """The pinned sites have a single-line call. Balance its actual parentheses,
    not the method's arguments; never run an unrestricted request-string replace.
    """
    regex = re.compile(r"([A-Za-z_][A-Za-z_0-9.]*)\.request\(Api\.functions\.(" + "|".join(re.escape(m) for m in methods) + r")\(")
    matches = list(regex.finditer(source))
    if len(matches) != expected:
        raise ValueError("Read call count changed")
    for match in reversed(matches):
        a = match.start(); opening = source.index("(", a)
        depth = 1; cursor = opening + 1
        while depth and cursor < len(source):
            if source[cursor] == "(": depth += 1
            elif source[cursor] == ")": depth -= 1
            elif source[cursor] == "\n": raise ValueError("Unexpected multiline request")
            cursor += 1
        if depth: raise ValueError("Unbalanced request")
        arguments = source[opening + 1:cursor - 1]
        if content_ids is not None:
            replacement = "familyPrivacyContentsRequest(postbox: postbox, ids: " + content_ids + ", network: " + match.group(1) + ", data: " + arguments + ")"
        elif wrapper is not None:
            replacement = "familyPrivacyReadRequest(postbox: " + wrapper + ", peerId: peerId, network: " + match.group(1) + ", data: " + arguments + ")"
        else:
            replacement = source[a:cursor - 1] + ", familyPrivacyReadEligible: " + eligible + ")"
        source = source[:a] + replacement + source[cursor:]
    return source

def transforms(original):
    out = dict(original)
    out[NETWORK] = network(out[NETWORK])
    out[BUILD] = once(out[BUILD], '        "//transport:FamilyTransport",',
                      '        "//transport:FamilyTransport",\n        "//privacy:FamilyPrivacy",')
    out[REPLAY] = once(out[REPLAY], "    func applyDeleteMessagesWithGlobalIds(_ ids: [Int32]) {\n", "    func applyDeleteMessagesWithGlobalIds(_ ids: [Int32]) {\n        familyArchiveReceivedDeletion(transaction: transaction, accountPeerId: accountPeerId, ids: transaction.messageIdsForGlobalIds(ids))\n")
    out[REPLAY] = once(out[REPLAY], "    func applyDeleteMessages(_ ids: [MessageId]) {\n", "    func applyDeleteMessages(_ ids: [MessageId]) {\n        familyArchiveReceivedDeletion(transaction: transaction, accountPeerId: accountPeerId, ids: ids)\n")
    history = ["messages.readHistory", "channels.readHistory"]
    contents = ["messages.readMessageContents", "channels.readMessageContents"]
    p = CORE + "Sources/State/SynchronizePeerReadState.swift"
    out[p] = requests(out[p], history, 2, wrapper="postbox")
    p = CORE + "Sources/TelegramEngine/Messages/MarkAllChatsAsRead.swift"
    out[p] = requests(out[p], history, 2, eligible="familyPrivacyCanHideHistory(transaction: transaction, peerId: peerId)")
    p = CORE + "Sources/State/ManagedSynchronizeConsumeMessageContentsOperations.swift"
    out[p] = requests(out[p], contents, 2, eligible="familyPrivacyCanHideContents(transaction: transaction, ids: operation.messageIds)")
    p = CORE + "Sources/State/ManagedConsumePersonalMessagesActions.swift"
    out[p] = requests(out[p], contents, 4, eligible="familyPrivacyCanHideContents(transaction: transaction, ids: [id])")
    p = CORE + "Sources/State/AccountViewTracker.swift"
    out[p] = requests(out[p], contents, 2, eligible="familyPrivacyCanHideContents(transaction: transaction, ids: messageIds)")
    p = CORE + "Sources/State/ManagedSynchronizeMarkAllUnseenPersonalMessagesOperations.swift"
    out[p] = requests(out[p], contents, 2, content_ids="filteredIds")
    p = CORE + "Sources/TelegramEngine/Messages/ApplyMaxReadIndexInteractively.swift"
    out[p] = requests(out[p], ["messages.readDiscussion"], 3, eligible="familyPrivacyCanHideHistory(transaction: transaction, peerId: peerId)")
    p = CORE + "Sources/TelegramEngine/Messages/ReplyThreadHistory.swift"
    out[p] = requests(out[p], ["messages.readDiscussion"], 1, wrapper="strongSelf.account.postbox")
    return out

def prepare(checkout):
    pins = json.loads((PRIVACY / "upstream-lock.json").read_text("utf-8"))["sources_after_network_patch"]
    original = {}
    for path, digest in pins.items():
        target = checkout / path
        if target.is_symlink() or not target.resolve().is_relative_to(checkout.resolve()):
            raise ValueError("Source link/path escape")
        raw = target.read_bytes()
        if hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError("Privacy input hash mismatch: " + path)
        original[path] = raw.decode("utf-8")
    if (checkout / BRIDGE).exists(): raise ValueError("Privacy bridge already present")
    patched = transforms(original)
    patched[BRIDGE] = (PRIVACY / "integration/FamilyPrivacyBridge.swift").read_text("utf-8")
    return patched

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    checkout = args.checkout.resolve()
    if checkout == ROOT: raise ValueError("Refuse to patch overlay workspace")
    patched = prepare(checkout)
    if args.apply:
        for path, source in patched.items():
            (checkout / path).write_bytes(source.encode("utf-8"))
    print(json.dumps({"applied": args.apply, "outputs": {p: hashlib.sha256(s.encode()).hexdigest() for p, s in patched.items()}}, sort_keys=True))

if __name__ == "__main__": main()
