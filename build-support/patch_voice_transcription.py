"""Pinned native voice bubble hooks, applied AFTER inline Python. Audit-only by default."""
from pathlib import Path
import argparse, hashlib, json, re

CELL = 'Lorg/telegram/ui/Cells/ChatMessageCell;'
HELPER = 'Lorg/telegram/messenger/family/FamilyVoiceTranscription;'
METHOD = re.compile(r'(?ms)^\.method [^\n]+\n.*?^\.end method')
PINS = {
 'ChatMessageCell': {
  'updateWaveform()V': '2c2c0702f38b7cc380b00b8b665be3f38b95aba8d048bd2da41a7dd419cf995c',
  'setMessageObject(Lorg/telegram/messenger/MessageObject;Lorg/telegram/messenger/MessageObject$GroupedMessages;ZZZZ)V': '80c3e7311d7d41a06572f8dda1f36824b5554b9134a1959b3e92e204700d1b79',
 },
 'TranscribeButton': {
  'onTap()V': '2052583733fcb6a397d89cd0521177551b0377c1d7ab540d024a195ef882ffcc',
  'transcribePressed(Lorg/telegram/messenger/MessageObject;ZLorg/telegram/ui/Cells/ChatMessageCell$ChatMessageCellDelegate;)V': 'a7b5daf40f6c4f7349fe403199805a8b86f3e2a4489cba5954084437cae62a0c',
  'isTranscribing(Lorg/telegram/messenger/MessageObject;)Z': '06432e6e2a26a4976d04147c1453101e7bf893887f6f4e02617a11e91e995ad6',
  'showTranscribeLock(Lorg/telegram/messenger/MessageObject;)Z': 'f14a0fffecac15245e1498fe17fd43daaa85d8fae8afe023d88d7a6c5d5043a4',
 }
}

def patch(source, name):
 if 'familyVoiceOriginal' in source: raise ValueError('Already patched: ' + name)
 if name == 'ChatMessageCell' and 'familyInlineOriginalSetMessageObject' not in source:
  raise ValueError('Apply pinned inline Python wrappers first')
 for signature, expected in PINS[name].items():
  matches = [m for m in METHOD.finditer(source) if m.group(0).splitlines()[0].endswith(' ' + signature)]
  if len(matches) != 1: raise ValueError('Method count changed: ' + signature)
  match = matches[0]; original = match.group(0)
  if hashlib.sha256(original.encode()).hexdigest() != expected: raise ValueError('Pinned method changed: ' + signature)
  method, descriptor = signature.split('(', 1)
  renamed = 'familyVoiceOriginal' + method[0].upper() + method[1:]
  saved = original.replace(' ' + method + '(', ' ' + renamed + '(', 1)
  header = original.splitlines()[0]
  if method == 'updateWaveform':
   body = f'''    .locals 1
    invoke-virtual {{p0}}, {CELL}->{renamed}()V
    invoke-static {{p0}}, {HELPER}->canShow(Ljava/lang/Object;)Z
    move-result v0
    iput-boolean v0, p0, {CELL}->useTranscribeButton:Z
    const/4 v0, 0x0
    invoke-virtual {{p0, v0}}, {CELL}->updateSeekBarWaveformWidth(Landroid/graphics/Canvas;)V
    return-void'''
  elif method == 'setMessageObject':
   body = f'''    .locals 0
    invoke-static {{p1}}, {HELPER}->restore(Ljava/lang/Object;)V
    invoke-virtual/range {{p0 .. p6}}, {CELL}->{renamed}({descriptor}
    return-void'''
  elif method == 'onTap':
   body = f'''    .locals 0
    invoke-static {{p0}}, {HELPER}->onButton(Ljava/lang/Object;)Z
    return-void'''
  elif method == 'transcribePressed':
   body = f'''    .locals 0
    invoke-static {{p0, p1, p2}}, {HELPER}->pressed(Ljava/lang/Object;ZLjava/lang/Object;)V
    return-void'''
  elif method == 'isTranscribing':
   body = f'''    .locals 1
    invoke-static {{p0}}, {HELPER}->isRunning(Ljava/lang/Object;)Z
    move-result v0
    return v0'''
  else:
   body = '''    .locals 1
    const/4 v0, 0x0
    return v0'''
  source = source[:match.start()] + saved + '\n\n' + header + '\n' + body + '\n.end method' + source[match.end():]
 return source

def prepare(decoded):
 changes = []
 for name, path in [('ChatMessageCell', 'Cells'), ('TranscribeButton', 'Components')]:
  files = list(decoded.glob('smali*/org/telegram/ui/' + path + '/' + name + '.smali'))
  if len(files) != 1: raise ValueError('Expected exactly one ' + name)
  file = files[0]; changes.append((file, patch(file.read_text(encoding='utf-8'), name)))
 return changes

if __name__ == '__main__':
 parser = argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--decoded', type=Path, default=Path(__file__).parent/'work-preview7/decoded')
 parser.add_argument('--output', type=Path, help='Separate directory for the two patched classes')
 parser.add_argument('--apply', action='store_true')
 args = parser.parse_args()
 if args.output and args.apply: raise SystemExit('Choose output or apply')
 changes = prepare(args.decoded)  # Check BOTH pins before writing anything.
 for file, updated in changes:
  if args.apply: file.write_text(updated, encoding='utf-8', newline='\n')
  if args.output:
   target = args.output/file.name; target.parent.mkdir(parents=True, exist_ok=True)
   target.write_text(updated, encoding='utf-8', newline='\n')
 print(json.dumps({'verified_methods': 6, 'applied': args.apply,
                   'sha256': {file.stem: hashlib.sha256(updated.encode()).hexdigest() for file, updated in changes}}))
