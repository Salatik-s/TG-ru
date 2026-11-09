"""Keep unsupported voice/video call paths closed on the TCP-only transport."""
import argparse
import hashlib
from pathlib import Path
import re

MANAGER = "submodules/TelegramCallsUI/Sources/PresentationCallManager.swift"
PIN = "648e88733c2c5721c1fced9b49e27cd6e2f64d09dfd88fc2af3b59e354b543a1"


def patch(raw):
    if hashlib.sha256(raw).hexdigest() != PIN:
        raise ValueError("Call manager input hash mismatch")
    source = raw.decode("utf-8")
    for method, body in {
        "ringingStatesUpdated": "        return\n",
        "startCall": "        return .single(false)\n",
        "requestCall": "        self.showFamilyCallsUnavailable(context: context)\n        return .requested\n",
        "scheduleGroupCall": "        self.showFamilyCallsUnavailable(context: context)\n        return .success\n",
        "joinGroupCall": "        self.showFamilyCallsUnavailable(context: context)\n        return .joined\n",
        "joinConferenceCall": "        self.showFamilyCallsUnavailable(context: accountContext)\n        return .joined\n",
    }.items():
        pattern = rf"^    (?:public|private) func {method}\(.*?\{{\n"
        indented_body = "".join("    " + line for line in body.splitlines(keepends=True))
        guard = "        guard self.familyCallTransportAvailable else {\n" + indented_body + "        }\n"
        source, count = re.subn(pattern, lambda m: m[0] + guard, source, flags=re.M | re.S)
        if count != 1:
            raise ValueError("Call manager method changed: " + method)
    assignment = "        self.callKitIntegration = CallKitIntegration.shared"
    anchor = "    public func requestCall("
    if source.count(assignment) != 1 or source.count(anchor) != 1:
        raise ValueError("Call manager anchor changed")
    source = source.replace(assignment, "        self.callKitIntegration = nil")
    source = source.replace(anchor, '''    private var familyCallTransportAvailable: Bool { false }

    private func showFamilyCallsUnavailable(context: AccountContext) {
        let (presentationData, present, _) = self.getDeviceAccessData()
        present(textAlertController(sharedContext: context.sharedContext,
            title: presentationData.strings.Call_StatusFailed,
            text: "Звонки пока недоступны через семейное подключение.",
            actions: [TextAlertAction(type: .defaultAction, title: presentationData.strings.Common_OK, action: {})]), nil)
    }

''' + anchor, 1)
    return source.encode("utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    root = parser.parse_args().source.resolve()
    path = root / MANAGER
    if path.is_symlink() or not path.resolve().is_relative_to(root):
        raise ValueError("Call manager path leaves source")
    path.write_bytes(patch(path.read_bytes()))
    print("Unrouted audio, video, group and conference call entry points disabled")
