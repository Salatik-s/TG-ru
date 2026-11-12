"""Mention fixes pinned to exteraless 13.2.1 CI 979e175.

Apply to bare decoded classes before the other overlay/title hooks. The shared
FamilyForwardMentions Java helper supports this version and preview13.
"""
import argparse
import hashlib
from pathlib import Path

from patch_mentions import ADAPTER, METHOD, once, share_patch
from patch_selector_mentions import CHAT, PARAMS, selector_patch


PINS = {
    'org/telegram/ui/Components/ShareAlert.smali': '244a1bf5b97b0a5d9ff6a3f908f5d725463ecc7cbaaae7fbb91278fb0e6389cb',
    'org/telegram/ui/Adapters/MentionsAdapter.smali': '6aa005f1eb3e00a6b4eeaffe27efe77f904be1bc66b0dd86218be5c8f49ca4af',
    'org/telegram/ui/Adapters/MentionsAdapter$7.smali': '76a7f63e3e5115b3c7fd634b3f84cb79e164a4ec18b3410160f3b07823394ccd',
    'org/telegram/ui/DialogsActivity.smali': '4482e38e4e16def939dc4a5d0b03129626161722cab0eec68e344788ca8f645a',
    'org/telegram/ui/ChatActivity.smali': '9e2ac31b7dabeb507cf3e74a366b022fee67ebc24febf7bccff29be9b2e1c4a3',
}


def adapter_patch(source):
    source = once(source,
                  '    :cond_51\n    move-wide/from16 v11, p1\n\n    .line 1313',
                  f'    :cond_51\n    iget-wide v11, v1, {ADAPTER}->threadMessageId:J\n\n    .line 1313')
    return once(source,
                '    :cond_71\n    invoke-virtual {v3}, Ljava/lang/String;->length()I\n\n    move-result v0\n\n    if-lez v0, :cond_73',
                '''    :cond_71
    invoke-virtual {v3}, Ljava/lang/String;->length()I
    move-result v0
    if-gtz v0, :family_mentions_query
    if-eqz v8, :cond_73
    iget-boolean v0, v8, Lorg/telegram/tgnet/TLRPC$Chat;->megagroup:Z
    if-eqz v0, :cond_73
    :family_mentions_query''')


def request_patch(source):
    return once(source,
                '    const/16 v1, 0x14\n\n    .line 1485\n    iput v1, v0, Lorg/telegram/tgnet/TLRPC$TL_channels_getParticipants;->limit:I',
                '    const/16 v1, 0xc8\n\n    .line 1485\n    iput v1, v0, Lorg/telegram/tgnet/TLRPC$TL_channels_getParticipants;->limit:I')


def method(source, signature, digest):
    matches = [m for m in METHOD.finditer(source) if m[0].splitlines()[0].endswith(' ' + signature)]
    if len(matches) != 1 or hashlib.sha256(matches[0][0].encode()).hexdigest() != digest:
        raise ValueError('Pinned CI 979e175 method changed: ' + signature)
    return matches[0]


def sender_patch(source):
    if 'familyApplyForwardEntities' in source:
        raise ValueError('Forward entities already patched')
    match = method(source,
                   'lambda$didSelectDialogs$413(Lorg/telegram/ui/DialogsActivity;Ljava/util/ArrayList;Ljava/lang/CharSequence;ZIILjava/util/ArrayList;Ljava/util/HashMap;)V',
                   'fb39f3b5da3d1b984af8a9c7a3ce6faf02c9bd66478d0677ab0f2abd921dfec7')
    modified = match[0]
    call = f'    invoke-static {{v0, v1, v2}}, {CHAT}->familyApplyForwardEntities({CHAT}{PARAMS}Ljava/lang/CharSequence;)V'
    for line in (37868, 37877):
        anchor = f'    move-result-object v1\n\n    .line {line}'
        modified = once(modified, anchor,
                        f'    move-result-object v1\n\n    move-object/from16 v2, p3\n\n{call}\n\n    .line {line}')
    source = source[:match.start()] + modified + source[match.end():]
    source += f'''

.method private static familyApplyForwardEntities({CHAT}{PARAMS}Ljava/lang/CharSequence;)V
    .locals 4
    invoke-virtual {{p0}}, Lorg/telegram/ui/ActionBar/BaseFragment;->getMediaDataController()Lorg/telegram/messenger/MediaDataController;
    move-result-object v0
    const/4 v1, 0x1
    new-array v1, v1, [Ljava/lang/CharSequence;
    const/4 v2, 0x0
    aput-object p2, v1, v2
    const/4 v3, 0x1
    invoke-virtual {{v0, v1, v3}}, Lorg/telegram/messenger/MediaDataController;->getEntities([Ljava/lang/CharSequence;Z)Ljava/util/ArrayList;
    move-result-object v0
    iput-object v0, p1, {PARAMS}->entities:Ljava/util/ArrayList;
    aget-object v0, v1, v2
    invoke-interface {{v0}}, Ljava/lang/CharSequence;->toString()Ljava/lang/String;
    move-result-object v0
    iput-object v0, p1, {PARAMS}->message:Ljava/lang/String;
    return-void
.end method
'''
    return source


def share_sender_patch(source):
    match = method(source, 'lambda$sendInternal$32(ZI[Ljava/lang/CharSequence;Ljava/util/ArrayList;Ljava/util/HashMap;)V',
                   'cd725d43bbc7514c75965889fd7096c1950967b49a8900f235a5f1bf108c12eb')
    original = match[0]
    start = original.index('    .line 2616\n')
    end = original.index('    :cond_1b\n', start)
    segment = once(original[start:end], '    const/16 v38, 0x0', '    move-object/from16 v38, p4')
    modified = original[:start] + segment + original[end:]
    return source[:match.start()] + modified + source[match.end():]


def patched_sources(decoded):
    transforms = [lambda s: share_sender_patch(share_patch(s)), adapter_patch, request_patch, selector_patch, sender_patch]
    changes = {}
    for (relative, digest), transform in zip(PINS.items(), transforms):
        paths = list(Path(decoded).glob('smali*/' + relative))
        if len(paths) != 1:
            raise ValueError('Expected one CI class: ' + relative)
        source = paths[0].read_text('utf-8')
        if hashlib.sha256(source.encode()).hexdigest() != digest:
            raise ValueError('Expected bare CI 979e175 class before other overlays: ' + relative)
        changes[paths[0]] = transform(source)
    return changes


def apply(decoded):
    changes = patched_sources(decoded)
    for path, source in changes.items():
        path.write_text(source, encoding='utf-8', newline='\n')
    return [str(path) for path in changes]


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('decoded', type=Path)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    if args.write:
        print('\n'.join(apply(args.decoded)))
    else:
        for path, source in patched_sources(args.decoded).items():
            print(path.name, hashlib.sha256(source.encode()).hexdigest())
