#!/usr/bin/env python3
"""Fail-closed source integration for the pinned official Telegram iOS checkout.

No downloads, signing, credentials or git writes. Run before compiling. Safe to repeat.
Also integrates the pinned resource bundle. Other agents own the native family-gate route.
"""
from pathlib import Path
import argparse, hashlib, importlib.util, json, subprocess

ROOT = Path(__file__).resolve().parent.parent
FEATURES = ROOT / 'features'
LOCK = json.loads((FEATURES / 'upstream-lock.json').read_text('utf-8'))
BASE = 'submodules/TelegramUI/Components/Chat/'
FILE = BASE + 'ChatMessageInteractiveFileNode/Sources/ChatMessageInteractiveFileNode.swift'
ROUND = BASE + 'ChatMessageInteractiveInstantVideoNode/Sources/ChatMessageInteractiveInstantVideoNode.swift'
TEXT = BASE + 'ChatMessageTextBubbleContentNode/Sources/ChatMessageTextBubbleContentNode.swift'
DOC = 'submodules/TelegramUI/Sources/DocumentPreviewController.swift'
LOCAL = 'submodules/Media/LocalAudioTranscription/Sources/LocalAudioTranscription.swift'
ROUND_BUILD = BASE + 'ChatMessageInteractiveInstantVideoNode/BUILD'
CHAT_LIST = 'submodules/ChatListUI/Sources/ChatListController.swift'
CHAT_LIST_BUILD = 'submodules/ChatListUI/BUILD'

def replace_one(source, before, after):
    if source.count(before) != 1:
        raise ValueError('Expected exactly one source anchor: ' + before[:100])
    return source.replace(before, after, 1)

def region(source, start, end, replacement):
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError('Non-unique region: ' + start)
    a, b = source.index(start), source.index(end)
    if b <= a:
        raise ValueError('Invalid region order')
    return source[:a] + replacement + source[b:]

def original(path, snapshot):
    data = (snapshot / path).read_bytes()
    if hashlib.sha256(data).hexdigest() != LOCK['files'][path]:
        raise ValueError('Pinned source snapshot changed: ' + path)
    return data.decode('utf-8')

def transformed(snapshot=FEATURES / 'upstream'):
    read = lambda path: original(path, snapshot)
    result = {}
    for path, template in [(FILE, 'Voice'), (ROUND, 'Round')]:
        source = read(path)
        source = region(source, '    private func transcribe() {', '    private func presentAudioTranscriptionTooltip(',
                        (FEATURES / f'speech/{template}Transcribe.function.swift').read_text('utf-8') + '\n')
        if path == ROUND:
            source = replace_one(source, 'import Foundation\n', 'import Foundation\nimport LocalAudioTranscription\n')
            source = region(source, '                    var displayTranscribe = false\n', '                    if displayTranscribe, let transcribeButtonBlurColor {',
                '                    var displayTranscribe = familyLocalSpeechEnabled && item.message.id.peerId.namespace != Namespaces.Peer.SecretChat && statusDisplayType == .free && !isViewOnceMessage && !item.presentationData.isPreview\n\n')
            block = '''            let currentTime = Int32(Date().timeIntervalSince1970)
            if transcribedText == nil, let cooldownUntilTime = item.associatedData.audioTranscriptionTrial.cooldownUntilTime, cooldownUntilTime > currentTime {
                updatedAudioTranscriptionState = .locked
            }
'''
        else:
            source = replace_one(source, '                var isConsumed: Bool?\n', '')
            source = replace_one(source, '                        isConsumed = attribute.consumed\n', '')
            source = region(source, '                var displayTranscribe = false\n', '                let transcribedText = forcedAudioTranscriptionText ?? transcribedText(message: EngineMessage(arguments.message))',
                '                let displayTranscribe = familyLocalSpeechEnabled && !Namespaces.Message.allNonRegular.contains(arguments.message.id.namespace) && arguments.message.id.peerId.namespace != Namespaces.Peer.SecretChat && !isViewOnceMessage && !arguments.presentationData.isPreview\n\n')
            block = '''                let currentTime = Int32(Date().timeIntervalSince1970)
                if transcribedText == nil, let cooldownUntilTime = arguments.associatedData.audioTranscriptionTrial.cooldownUntilTime, cooldownUntilTime > currentTime {
                    updatedAudioTranscriptionState = .locked
                }
'''
        source = replace_one(source, block, '')
        if 'engine.messages.transcribeAudio(' in source:
            raise ValueError('Server transcription call remains: ' + path)
        result[path] = source
    result[LOCAL] = (FEATURES / 'speech/LocalAudioTranscription.compat.swift').read_text('utf-8')
    result[ROUND_BUILD] = replace_one(read(ROUND_BUILD), '    deps = [\n', '    deps = [\n        "//submodules/Media/LocalAudioTranscription:LocalAudioTranscription",\n')
    result[DOC] = replace_one(read(DOC),
        '    let navigationBar = UINavigationBar.appearance(whenContainedInInstancesOf: [QLPreviewController.self])',
        '''    if file.mimeType == "application/pdf" || file.fileName?.lowercased().hasSuffix(".pdf") == true,
       let path = postbox.mediaBox.completedResourcePath(file.resource),
       familyPresentPDF(from: rootController, path: path, title: file.fileName ?? strings.Message_File, canShare: canShare) { return }
    let navigationBar = UINavigationBar.appearance(whenContainedInInstancesOf: [QLPreviewController.self])''')
    source = read(TEXT)
    source = replace_one(source, '    public var statusNode: ChatMessageDateAndStatusNode?\n',
        '    private var familyPythonView: FamilyPythonInlineView?\n    public var statusNode: ChatMessageDateAndStatusNode?\n')
    source = replace_one(source, '                return (suggestedBoundingWidth, { [weak self] boundingWidth in\n', '''                var familyPythonBlocks: [String] = []
                if UserDefaults.standard.bool(forKey: "family.features.python"), !item.presentationData.isPreview, !hasDraft {
                    for attribute in item.message.attributes {
                        guard let attribute = attribute as? TextEntitiesMessageAttribute else { continue }
                        for entity in attribute.entities {
                            if case let .Pre(language) = entity.type, let language, ["python", "py", "python3"].contains(language.lowercased()),
                               entity.range.lowerBound >= 0, entity.range.upperBound <= (item.message.text as NSString).length,
                               entity.range.count > 0, entity.range.count <= 65536, familyPythonBlocks.count < 8 {
                                familyPythonBlocks.append((item.message.text as NSString).substring(with: NSRange(location: entity.range.lowerBound, length: entity.range.count)))
                            }
                        }
                    }
                }
                if !familyPythonBlocks.isEmpty { suggestedBoundingWidth = max(suggestedBoundingWidth, min(constrainedSize.width, 260)) }
                return (suggestedBoundingWidth, { [weak self] boundingWidth in
''')
    source = replace_one(source, '                    boundingSize.height += topInset + bottomInset\n', '''                    boundingSize.height += topInset + bottomInset
                    let familyPythonY = boundingSize.height
                    if !familyPythonBlocks.isEmpty {
                        boundingSize.width = max(boundingSize.width, min(boundingWidth, 260))
                        boundingSize.height += 248
                    }
''')
    source = replace_one(source, '                            strongSelf.item = item\n', '''                            strongSelf.item = item
                            if !familyPythonBlocks.isEmpty {
                                let runner: FamilyPythonInlineView
                                if let existing = strongSelf.familyPythonView { runner = existing }
                                else { runner = FamilyPythonInlineView(frame: .zero); strongSelf.familyPythonView = runner; strongSelf.view.addSubview(runner) }
                                runner.configure(identity: "\\(item.context.account.id):\\(item.message.id)", blocks: familyPythonBlocks, textColor: messageTheme.primaryTextColor, accent: messageTheme.accentTextColor)
                                runner.frame = CGRect(x: layoutConstants.text.bubbleInsets.left, y: familyPythonY, width: boundingSize.width - sideInsets, height: 240)
                            } else {
                                strongSelf.familyPythonView?.removeFromSuperview(); strongSelf.familyPythonView = nil
                            }
''')
    source = replace_one(source, '    override public func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {\n', '''    override public func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if let runner = self.familyPythonView, runner.frame.contains(point) { return runner.hitTest(self.view.convert(point, to: runner), with: event) }
''')
    source = replace_one(source, '    override public func tapActionAtPoint(_ point: CGPoint, gesture: TapLongTapOrDoubleTapGesture, isEstimating: Bool) -> ChatMessageBubbleContentTapAction {\n', '''    override public func tapActionAtPoint(_ point: CGPoint, gesture: TapLongTapOrDoubleTapGesture, isEstimating: Bool) -> ChatMessageBubbleContentTapAction {
        if let runner = self.familyPythonView, runner.frame.contains(point) { return ChatMessageBubbleContentTapAction(content: .ignore) }
''')
    result[TEXT] = source
    source = read(CHAT_LIST)
    source = replace_one(source, '                title = self.presentationData.strings.DialogList_Title\n', '                title = "S.A.A."\n')
    source = replace_one(source, '                defaultTitle = presentationData.strings.DialogList_Title\n', '                defaultTitle = "S.A.A."\n')
    source = replace_one(source, '    var proxyButton: NavigationBarComponent.Button?\n', '    var familyButton: NavigationBarComponent.Button?\n    var proxyButton: NavigationBarComponent.Button?\n')
    source = replace_one(source, '        if let storyButton = self.storyButton {\n', '        if let familyButton = self.familyButton { result.append(familyButton) }\n        if let storyButton = self.storyButton {\n')
    source = replace_one(source, '        let defaultTitle: String\n', '        self.familyButton = nil\n        let defaultTitle: String\n')
    source = replace_one(source, '                isRoot = true\n', '''                isRoot = true
                if !isReorderingTabs && !stateAndFilterId.state.editing {
                    self.familyButton = NavigationBarComponent.Button(id: "familyFeatures", content: .more, action: { [weak self] _ in
                        self?.parentController?.openFamilyFeatureSettings()
                    })
                }
''')
    source = replace_one(source, '    @objc fileprivate func composePressed() {\n', '''    fileprivate func openFamilyFeatureSettings() {
        let settings = FamilyFeatureSettingsController(accountID: String(self.context.account.peerId.toInt64()))
        let navigation = UINavigationController(rootViewController: settings)
        navigation.navigationBar.tintColor = self.presentationData.theme.rootController.navigationBar.accentTextColor
        self.present(navigation, animated: true)
    }

    @objc fileprivate func composePressed() {
''')
    result[CHAT_LIST] = source
    result[CHAT_LIST_BUILD] = replace_one(read(CHAT_LIST_BUILD),
        '    deps = [\n        "//submodules/SSignalKit/SwiftSignalKit:SwiftSignalKit",',
        '    deps = [\n        "//privacy:FamilyPrivacy",\n        "//submodules/SSignalKit/SwiftSignalKit:SwiftSignalKit",')
    result[CHAT_LIST_BUILD] = replace_one(result[CHAT_LIST_BUILD],
        '    deps = [\n        ":ChatListUI",',
        '    deps = [\n        "//privacy:FamilyPrivacy",\n        ":ChatListUI",')
    return result

HELPERS = {
    'speech/FamilyLocalSpeech.swift': 'submodules/Media/LocalAudioTranscription/Sources/FamilyLocalSpeech.swift',
    'documents/FamilyPDFController.swift': 'submodules/TelegramUI/Sources/FamilyPDFController.swift',
    'settings/FamilyFeatureSettingsController.swift': 'submodules/ChatListUI/Sources/FamilyFeatureSettingsController.swift',
    'python/FamilyPythonInlineView.swift': BASE + 'ChatMessageTextBubbleContentNode/Sources/FamilyPythonInlineView.swift',
    'tests/FeaturesUIContracts.swift': 'submodules/ChatListUI/Tests/FeaturesUIContracts.swift',
}

def validate_assets():
    resource_root = FEATURES / 'python/Resources/family-python/runner'
    resource_lock = json.loads((FEATURES / 'python/Resources/runtime-lock.json').read_text('utf-8'))
    if resource_lock['version'] != '314.0.7':
        raise ValueError('Unexpected Python runtime pin')
    for name, expected in resource_lock['files'].items():
        target = (resource_root / name).resolve()
        target.relative_to(resource_root.resolve())
        if hashlib.sha256(target.read_bytes()).hexdigest() != expected:
            raise ValueError('Bundled runtime changed: ' + name)

def apply(checkout: Path, check_only=False):
    head = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', 'HEAD'], text=True).strip()
    if head != LOCK['commit']:
        raise ValueError('Official source commit mismatch: ' + head)
    patched = json.loads((FEATURES / 'integration-lock.json').read_text('utf-8'))
    validate_assets()
    for source in HELPERS:
        if hashlib.sha256((FEATURES / source).read_bytes()).hexdigest() != patched['helpers'][source]:
            raise ValueError('Native helper changed without updating its lock: ' + source)
    paths = [FILE, ROUND, TEXT, DOC, LOCAL, ROUND_BUILD, CHAT_LIST, CHAT_LIST_BUILD]
    hashes = {path: hashlib.sha256((checkout / path).read_bytes()).hexdigest() for path in paths}
    already_applied = all(hashes[path] == patched['files'][path] for path in paths)
    if already_applied:
        changes = {path: (checkout / path).read_text('utf-8') for path in paths}
    else:
        if not all(hashes[path] == LOCK['files'][path] for path in paths):
            raise ValueError('Unexpected or partially applied checkout changes')
        # Production preparation reads the pinned checkout itself, never local development snapshots.
        changes = transformed(checkout)
    # Validate every input before writing any file. Accept only exact originals or our exact outputs.
    for path, text in changes.items():
        actual = (checkout / path).read_bytes()
        if hashlib.sha256(actual).hexdigest() not in [LOCK['files'][path], patched['files'][path]]:
            raise ValueError('Unexpected checkout changes: ' + path)
        if hashlib.sha256(text.encode('utf-8')).hexdigest() != patched['files'][path]:
            raise ValueError('Feature patch output changed without updating its lock: ' + path)
    for source, target in HELPERS.items():
        if (checkout / target).exists() and (checkout / target).read_bytes() != (FEATURES / source).read_bytes():
            raise ValueError('Refusing to overwrite another helper: ' + target)
    resource_spec = importlib.util.spec_from_file_location('family_feature_resources', FEATURES / 'resourcesintegration.py')
    resources = importlib.util.module_from_spec(resource_spec)
    resource_spec.loader.exec_module(resources)
    resource_plan = resources.prepare(checkout)
    if not check_only:
        for path, text in changes.items(): (checkout / path).write_text(text, encoding='utf-8', newline='\n')
        for source, target in HELPERS.items():
            (checkout / target).parent.mkdir(parents=True, exist_ok=True)
            (checkout / target).write_bytes((FEATURES / source).read_bytes())
        resources.write_plan(resource_plan)
    print(('Validated' if check_only else 'Applied'), len(changes), 'pinned patches and', len(HELPERS), 'native helpers')
    print('Settings reachable from chat-list ellipsis; FamilyPython.bundle packaged; Speech permission template verified.')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--checkout', type=Path)
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        out = transformed()
        assert len(out) == 8
        assert all('engine.messages.transcribeAudio(' not in out[x] for x in (FILE, ROUND))
        print('Pinned feature transformations PASS:', len(out))
    elif args.checkout: apply(args.checkout, args.check_only)
    else: parser.error('--checkout or --self-test is required')
