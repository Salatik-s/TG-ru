"""Mentions for DialogsActivity forwarding and native preservation of entities.

selector_patch accepts the currently edited DialogsActivity source so its
preview14 stories/title hooks can be retained. sender_patch verifies its one
untouched pinned sender method independently of unrelated ChatActivity patches.
"""
import hashlib

from patch_mentions import METHOD, once, HELPER


DIALOGS = 'Lorg/telegram/ui/DialogsActivity;'
CHAT = 'Lorg/telegram/ui/ChatActivity;'
PARAMS = 'Lorg/telegram/messenger/SendMessagesHelper$SendMessageParams;'
SENDER = 'lambda$didSelectDialogs$407(Lorg/telegram/ui/DialogsActivity;Ljava/util/ArrayList;Ljava/lang/CharSequence;ZIILjava/util/ArrayList;Ljava/util/HashMap;)V'
SENDER_SHA256 = '5e9b8deef8deb1788de44122e58cd4c2d2bb5e3fb7890722fd3ab248531aaa3e'


def selector_patch(source):
    if 'familyForwardMentions' in source:
        raise ValueError('Selector mentions already patched')
    source = once(source, '# instance fields', '# instance fields\n.field private familyForwardMentions:Ljava/lang/Object;\n')
    for name, action in [('updateSelectedCount', 'refreshSelector'), ('onResume', 'refreshSelector'), ('onFragmentDestroy', 'close')]:
        matches = [m for m in METHOD.finditer(source) if m[0].splitlines()[0].endswith(' ' + name + '()V')]
        if len(matches) != 1:
            raise ValueError('Expected one DialogsActivity method: ' + name)
        match = matches[0]
        original = match[0]
        renamed = 'familyMentionsOriginal' + name[0].upper() + name[1:]
        saved = once(original, ' ' + name + '()V', ' ' + renamed + '()V')
        native = f'    invoke-virtual {{p0}}, {DIALOGS}->{renamed}()V'
        hook = f'    invoke-static {{p0}}, {HELPER}->{action}(Ljava/lang/Object;)V'
        calls = hook + '\n' + native if action == 'close' else native + '\n' + hook
        wrapper = original.splitlines()[0] + '\n    .locals 0\n' + calls + '\n    return-void\n.end method'
        source = source[:match.start()] + saved + '\n\n' + wrapper + source[match.end():]
    return source


def sender_patch(source):
    if 'familyApplyForwardEntities' in source:
        raise ValueError('Forward entities already patched')
    matches = [m for m in METHOD.finditer(source) if m[0].splitlines()[0].endswith(' ' + SENDER)]
    if len(matches) != 1:
        raise ValueError('Expected exactly one forwarding sender callback')
    match = matches[0]
    original = match[0]
    if hashlib.sha256(original.encode()).hexdigest() != SENDER_SHA256:
        raise ValueError('Pinned forwarding sender callback changed')
    call = f'    invoke-static {{v0, v1, v2}}, {CHAT}->familyApplyForwardEntities({CHAT}{PARAMS}Ljava/lang/CharSequence;)V'
    modified = original
    for line in (37255, 37264):
        anchor = f'    move-result-object v1\n\n    .line {line}'
        modified = once(modified, anchor,
                        f'    move-result-object v1\n\n    move-object/from16 v2, p3\n\n{call}\n\n    .line {line}')
    source = source[:match.start()] + modified + source[match.end():]
    # Native extraction handles URLSpanUserMention, text formatting and any
    # normalized text together. Both before/after-forward comment paths call it.
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
    """Retain the already-extracted entities when commenting on a shared story."""
    signature = 'lambda$sendInternal$30(ZI[Ljava/lang/CharSequence;Ljava/util/ArrayList;Ljava/util/HashMap;)V'
    matches = [m for m in METHOD.finditer(source) if m[0].splitlines()[0].endswith(' ' + signature)]
    if len(matches) != 1:
        raise ValueError('Expected one ShareAlert sender callback')
    match = matches[0]
    original = match[0]
    if hashlib.sha256(original.encode()).hexdigest() != 'c9f8a0c7659206bd17aba2a825194a0d5f1763c81674b62624a27f32ac497358':
        raise ValueError('Pinned ShareAlert sender changed')
    start = original.index('    .line 2646\n')
    end = original.index('    :cond_1b\n', start)
    segment = once(original[start:end], '    const/16 v38, 0x0', '    move-object/from16 v38, p4')
    modified = original[:start] + segment + original[end:]
    return source[:match.start()] + modified + source[match.end():]
