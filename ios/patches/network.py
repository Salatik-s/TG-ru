#!/usr/bin/env python3
"""Hash-guarded, offline network overlay for one Telegram-iOS revision.

Default is audit/diff only. --apply writes only after every input validates.
"""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import re

REVISION = "24638bc69bd083981b178dd1e7f921eebbf7fc46"
PINS = {
    "submodules/TelegramCore/Sources/Network/Network.swift": "ed034f1d93279c860c514a63925b76b8c3b9df0aaa4e7141d65129d42adc27d6",
    "submodules/MtProtoKit/Sources/MTTcpConnection.m": "e05e9a7534f103b8fc933d182d29533291e2aec793878302b9a09aaabfbe56da",
    "submodules/MtProtoKit/Sources/MTHttpRequestOperation.m": "dc06d22a21c591fe1a517310dcf60db491a977a48fc2175f7fb33f32ff4ecb4d",
    "submodules/TelegramCore/Sources/Network/FetchHttpResource.swift": "3a84b9486150e55f37940f6d0bbf7300ede4647fc8c953cbaf7b9e40099d1d50",
    "submodules/TelegramCore/BUILD": "08bee2425c1791989633823e20c0f6fa7d6fb359a62733be569db02de819bf02",
    "submodules/MtProtoKit/Sources/MTBackupAddressSignals.m": "14d01befcc0d2f0ed8403cb247aa64e0647e341d48b0e2be483e1ce05c8d6824",
}


def once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("network patch anchor changed")
    return text.replace(old, new, 1)


def between(text, start, end, replacement):
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError("network patch region changed")
    a, b = text.index(start), text.index(end)
    if a >= b:
        raise ValueError("network patch anchor order changed")
    return text[:a] + replacement + text[b:]


def function(text, declaration, indent, body):
    # Exact input hash is checked first. Closing braces at the declaration's
    # indentation identify these particular pinned methods (not a Swift parser).
    pattern = re.escape(declaration) + r".*?^" + re.escape(indent) + r"\}"
    result, count = re.subn(pattern, lambda _: declaration + "\n" + body + indent + "}", text,
                            flags=re.MULTILINE | re.DOTALL)
    if count != 1:
        raise ValueError("network method anchor changed")
    return result


def network(text):
    text = "import FamilyTransport\n" + text
    declaration = "private func resolveNetworkEngine(accountId: AccountRecordId, context: MTContext, factory: NetworkEngineFactory?, settings: NetworkEngineSettings?, rustDisabledReason: String?, isAppExtension: Bool) -> NetworkEngine {"
    text = function(text, declaration, "", "    return MtProtoKitEngine(context: context)\n")
    text = once(text,
        "            if let effectiveActiveServer = proxySettings?.effectiveActiveServer {\n                apiEnvironment = apiEnvironment.withUpdatedSocksProxySettings(effectiveActiveServer.mtProxySettings)\n            }",
        "            apiEnvironment = apiEnvironment.withUpdatedSocksProxySettings(nil)")
    text = between(text, "            if let networkSettings = networkSettings {", "            let seedAddressList: [Int: [String]]", """            context.makeTcpConnectionInterface = { delegate, delegateQueue in
                return FamilyTcpConnectionInterface(delegate: delegate, delegateQueue: delegateQueue)
            }
            let baseTcpConnectionInterfaceFactory = context.makeTcpConnectionInterface
            let isAppExtension = Bundle.main.bundlePath.hasSuffix(".appex")
            let initialActiveServer: ProxyServerSettings? = nil
            WebProxyTransport.shared.apply(configuration: nil)

""")
    text = function(text, "    func updateProxySettings(_ activeServer: ProxyServerSettings?) {", "    ", """        WebProxyTransport.shared.apply(configuration: nil)
        self.webProxyActive.set(false)
        self.context.makeTcpConnectionInterface = { delegate, delegateQueue in
            return FamilyTcpConnectionInterface(delegate: delegate, delegateQueue: delegateQueue)
        }
        self.context.updateApiEnvironment { environment in
            guard environment?.socksProxySettings != nil else { return nil }
            self.dropConnectionStatus()
            return environment?.withUpdatedSocksProxySettings(nil)
        }
""")
    text = function(text, "    public func switchEngine(to kind: NetworkEngineKind, reason: String) -> Bool {", "    ", "        return false\n")
    return text


def tcp(text):
    text = once(text, "    bool _isWebProxy;", "    bool _isWebProxy;\n    bool _familyProxyRejected;")
    text = between(text, "        if (context.apiEnvironment.socksProxySettings != nil) {", "        if (_mtpSecret != nil) {\n            if ([_mtpSecret isKindOfClass:[MTProxySecretType1 class]]", "        _familyProxyRejected = context.apiEnvironment.socksProxySettings != nil;\n\n")
    # Start at the distinctive pinned comment; retain surrounding socket guard.
    text = between(text, "            // The injected interface must be paired", "            [_socket setGetLogPrefix:_getLogPrefix];", """            if (_familyProxyRejected) {
                [self closeAndNotifyWithError:true];
                return;
            }
            _socket = _makeTcpConnectionInterface ? _makeTcpConnectionInterface(self, [[MTTcpConnection tcpQueue] nativeQueue]) : nil;
            if (_socket == nil || ([_socket respondsToSelector:@selector(isWebProxyCarrier)] && [_socket isWebProxyCarrier])) {
                [_socket resetDelegate];
                _socket = nil;
                [self closeAndNotifyWithError:true];
                return;
            }

""")
    text = between(text, "            if (_isWebProxy) {\n                // Resolve NOTHING", "            __weak MTTcpConnection *weakSelf = self;\n            [_resolveDisposable setDisposable:[resolveSignal", "            // FamilyTransport sends the original DC address through VLESS; no local DC DNS or proxy lookup.\n\n")
    return text


def http(text):
    return function(text, "+ (MTSignal *)dataForHttpUrl:(NSURL *)url headers:(NSDictionary *)headers {", "", """    // Direct backup discovery and raw HTTP are unsupported until tunneled.
    return [[MTSignal alloc] initWithGenerator:^id<MTDisposable>(MTSubscriber *subscriber) {
        [subscriber putError:[NSError errorWithDomain:@"FamilyTransport" code:3 userInfo:nil]];
        return [[MTBlockDisposable alloc] initWithBlock:^{}];
    }];
""")


def fetch(text):
    return function(text, "public func fetchHttpResource(url: String, preserveExactUrl: Bool = false) -> Signal<MediaResourceDataFetchResult, MediaResourceDataFetchError> {", "", """    return Signal { subscriber in
        subscriber.putError(.generic)
        return EmptyDisposable
    }
""")


def build(text):
    needle = '        "//submodules/MtProtoKit:MtProtoKit",'
    if needle not in text:
        raise ValueError("TelegramCore dependency anchor changed")
    return text.replace(needle, needle + '\n        "//transport:FamilyTransport",', 1)


TRANSFORMS = [network, tcp, http, fetch, build]


def prepare(root):
    original = {}
    for path, digest in PINS.items():
        raw = (root / path).read_bytes()
        if hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError(f"upstream hash mismatch: {path}")
        original[path] = raw
    backup = original[list(PINS)[5]].decode("utf-8")
    if "context.makeTcpConnectionInterface = currentContext.makeTcpConnectionInterface;" not in backup:
        raise ValueError("derived MTContext no longer copies the socket factory")
    patched = {}
    for path, transform in zip(PINS, TRANSFORMS):
        patched[path] = transform(original[path].decode("utf-8")).encode("utf-8")
    return original, patched


def apply(root, patched):
    # All hashes/regions are validated by prepare before any write. An interrupted
    # build should discard its checkout, not try to patch a partially written tree.
    for path, raw in patched.items():
        (root / path).write_bytes(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--diff", action="store_true")
    args = parser.parse_args()
    original, patched = prepare(args.checkout.resolve())
    if args.diff:
        for path, raw in patched.items():
            print("".join(difflib.unified_diff(original[path].decode().splitlines(True), raw.decode().splitlines(True), fromfile="a/" + path, tofile="b/" + path)), end="")
    if args.apply:
        apply(args.checkout.resolve(), patched)
    print(json.dumps({"upstream_revision": REVISION, "applied": args.apply, "outputs": {path: hashlib.sha256(raw).hexdigest() for path, raw in patched.items()}}, sort_keys=True))


if __name__ == "__main__":
    main()
