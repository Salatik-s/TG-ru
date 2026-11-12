"""Add forward-comment mentions and broaden native group mention search.

Input is the verified preview13 tree. All inputs are checked before writes.
The overlay helper uses Telegram's existing search, account and mention entities.
"""
import argparse
import hashlib
from pathlib import Path
import re


PINS = {
    'org/telegram/ui/Components/ShareAlert.smali': '3cf00d9f3777687b6886baee48dadab9d9e801b652a54a455fef8b776ad64f6f',
    'org/telegram/ui/Adapters/MentionsAdapter.smali': 'b075ebe28d94bd37abec2e478a9b9bb1ba73fb78e923e3322f3209b577c4a0ac',
    'org/telegram/ui/Adapters/MentionsAdapter$7.smali': '0db288b0e067418290b09c1b25ba54bdb5c55a10393957076408085c164d33bf',
}
HELPER = 'Lorg/telegram/messenger/family/FamilyForwardMentions;'
SHARE = 'Lorg/telegram/ui/Components/ShareAlert;'
ADAPTER = 'Lorg/telegram/ui/Adapters/MentionsAdapter;'
METHOD = re.compile(r'(?ms)^\.method [^\n]+\n.*?^\.end method')


def once(source, old, new):
    if source.count(old) != 1:
        raise ValueError('Pinned mention anchor changed: ' + old[:120])
    return source.replace(old, new, 1)


def share_patch(source):
    source = once(source, '# instance fields', '# instance fields\n.field private familyForwardMentions:Ljava/lang/Object;\n')
    for name, descriptor, body in [
        ('updateSelectedCount', '(I)V', f'''    .locals 0
    invoke-virtual {{p0, p1}}, {SHARE}->familyMentionsOriginalUpdateSelectedCount(I)V
    invoke-static {{p0}}, {HELPER}->refresh(Ljava/lang/Object;)V
    return-void'''),
        ('dismissInternal', '()V', f'''    .locals 0
    invoke-static {{p0}}, {HELPER}->close(Ljava/lang/Object;)V
    invoke-virtual {{p0}}, {SHARE}->familyMentionsOriginalDismissInternal()V
    return-void'''),
    ]:
        matches = [m for m in METHOD.finditer(source) if m[0].splitlines()[0].endswith(' ' + name + descriptor)]
        if len(matches) != 1:
            raise ValueError('Expected one ShareAlert method: ' + name)
        match = matches[0]
        original = match[0]
        saved = once(original, ' ' + name + descriptor,
                     ' familyMentionsOriginal' + name[0].upper() + name[1:] + descriptor)
        wrapper = original.splitlines()[0] + '\n' + body + '\n.end method'
        source = source[:match.start()] + saved + '\n\n' + wrapper + source[match.end():]
    return source


def adapter_patch(source):
    # With no ChatActivity parent, forward comments still have a destination topic.
    source = once(source,
                  '    :cond_51\n    move-wide/from16 v11, p1\n\n    .line 1310',
                  f'    :cond_51\n    iget-wide v11, v1, {ADAPTER}->threadMessageId:J\n\n    .line 1310')
    # An empty @ query may fetch accessible participants of a megagroup. Preserve
    # the original nonempty-query path used by other adapter callers.
    source = once(source,
                  '    :cond_71\n    invoke-virtual {v3}, Ljava/lang/String;->length()I\n\n    move-result v0\n\n    if-lez v0, :cond_73',
                  '''    :cond_71
    invoke-virtual {v3}, Ljava/lang/String;->length()I

    move-result v0

    if-gtz v0, :family_mentions_query

    if-eqz v8, :cond_73

    iget-boolean v0, v8, Lorg/telegram/tgnet/TLRPC$Chat;->megagroup:Z

    if-eqz v0, :cond_73

    :family_mentions_query''')
    return source


def deduplicate_results(source):
    anchor = '.method public notifyDataSetChanged()V\n    .locals 9\n'
    return once(source, anchor, anchor + f"""
    iget-object v0, p0, {ADAPTER}->searchResultUsernames:Ljava/util/ArrayList;

    invoke-static {{v0}}, {HELPER}->deduplicate(Ljava/util/ArrayList;)V
""")


def request_patch(source):
    # Telegram's filtered participants endpoint caps a page at 200. Typing a
    # name/username keeps doing server-side filtering for larger groups.
    return once(source,
                '    const/16 v1, 0x14\n\n    .line 1482\n    iput v1, v0, Lorg/telegram/tgnet/TLRPC$TL_channels_getParticipants;->limit:I',
                '    const/16 v1, 0xc8\n\n    .line 1482\n    iput v1, v0, Lorg/telegram/tgnet/TLRPC$TL_channels_getParticipants;->limit:I')


def patched_sources(decoded):
    decoded = Path(decoded)
    result = {}
    transforms = [share_patch, adapter_patch, request_patch]
    for (relative, digest), transform in zip(PINS.items(), transforms):
        paths = list(decoded.glob('smali*/' + relative))
        if len(paths) != 1:
            raise ValueError('Expected exactly one ' + relative)
        path = paths[0]
        source = path.read_text('utf-8')
        if hashlib.sha256(source.encode()).hexdigest() != digest:
            raise ValueError('Pinned preview13 class changed: ' + relative)
        result[path] = transform(source)
    return result


def apply(decoded):
    changes = patched_sources(decoded)
    for path, source in changes.items():
        path.write_text(source, encoding='utf-8', newline='\n')
    return [str(path) for path in changes]


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('decoded', type=Path)
    parser.add_argument('--write', action='store_true')
    arguments = parser.parse_args()
    if arguments.write:
        print('\n'.join(apply(arguments.decoded)))
    else:
        for path, source in patched_sources(arguments.decoded).items():
            print(path.name, hashlib.sha256(source.encode()).hexdigest())
