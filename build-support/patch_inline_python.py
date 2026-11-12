"""Pinned preview7 ChatMessageCell wrappers. Default is audit only, never mutation."""
from pathlib import Path
import argparse,hashlib,json,re

CELL='Lorg/telegram/ui/Cells/ChatMessageCell;'
HELPER='Lorg/telegram/messenger/family/FamilyInlinePython;'
PIN={
 'onMeasure(II)V':'4f3a376c8e4a66a4668810d05041ea322aa3275e3c4ad22196c2732d20d31e9f',
 'onLayout(ZIIII)V':'6b25e86786ce4d3e10915550ef96392112a4dabe7f9f1a8804209322924998b5',
 'onDetachedFromWindow()V':'8232fe5ecc08ad3c247c1dea3e6565b0f75ac5b1cff24d737947416366c4327e',
 'setMessageObject(Lorg/telegram/messenger/MessageObject;Lorg/telegram/messenger/MessageObject$GroupedMessages;ZZZZ)V':'b37e78869ae4aebdc0f0e4a742885f21d20b60322ca506b96b07b7036489ba6a',
}
METHOD=re.compile(r'(?ms)^\.method [^\n]+\n.*?^\.end method')
def patch(source, pins=None):
 if pins is None:pins=PIN
 if 'familyInlineOriginal' in source:raise ValueError('Already patched; use a clean pinned cell')
 if '.field private additionalPaddingHeight:I' not in source:raise ValueError('Missing original padding field')
 for signature,expected in pins.items():
  matches=[m for m in METHOD.finditer(source) if m.group(0).splitlines()[0].endswith(' '+signature)]
  if len(matches)!=1:raise ValueError('Method count changed: '+signature)
  match=matches[0];original=match.group(0)
  if hashlib.sha256(original.encode()).hexdigest()!=expected:raise ValueError('Pinned method changed: '+signature)
  name,desc=signature.split('(',1);renamed='familyInlineOriginal'+name[0].upper()+name[1:]
  renamed_source=original.replace(' '+name+'(', ' '+renamed+'(',1)
  if name=='onMeasure':
   body=f'''    .locals 4
    invoke-virtual {{p0, p1, p2}}, {CELL}->{renamed}(II)V
    invoke-static {{p0}}, {HELPER}->measure(Landroid/view/ViewGroup;)I
    move-result v0
    if-lez v0, :family_inline_done
    iget v1, p0, {CELL}->additionalPaddingHeight:I
    add-int/2addr v1, v0
    iput v1, p0, {CELL}->additionalPaddingHeight:I
    invoke-virtual {{p0}}, Landroid/view/View;->getMeasuredWidth()I
    move-result v1
    invoke-virtual {{p0}}, Landroid/view/View;->getMeasuredHeight()I
    move-result v2
    add-int/2addr v2, v0
    invoke-virtual {{p0, v1, v2}}, Landroid/view/View;->setMeasuredDimension(II)V
    :family_inline_done
    return-void'''
  elif name=='onLayout':
   body=f'''    .locals 0
    invoke-virtual/range {{p0 .. p5}}, {CELL}->{renamed}(ZIIII)V
    invoke-static {{p0}}, {HELPER}->layout(Landroid/view/ViewGroup;)V
    return-void'''
  elif name=='onDetachedFromWindow':
   body=f'''    .locals 0
    invoke-static {{p0}}, {HELPER}->detach(Landroid/view/ViewGroup;)V
    invoke-virtual {{p0}}, {CELL}->{renamed}()V
    return-void'''
  else:
   body=f'''    .locals 0
    invoke-static {{p0, p1}}, {HELPER}->beforeBind(Landroid/view/ViewGroup;Ljava/lang/Object;)V
    invoke-virtual/range {{p0 .. p6}}, {CELL}->{renamed}({desc}
    return-void'''
  wrapper='.method public '+signature+'\n'+body+'\n.end method'
  source=source[:match.start()]+renamed_source+'\n\n'+wrapper+source[match.end():]
 return source

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--decoded',type=Path,default=Path(__file__).parent/'work-preview7/decoded')
 parser.add_argument('--output',type=Path,help='Write a separate patched smali copy; original remains untouched')
 parser.add_argument('--apply',action='store_true',help='Explicitly apply to the supplied staging tree')
 args=parser.parse_args()
 files=list(args.decoded.glob('smali*/org/telegram/ui/Cells/ChatMessageCell.smali'))
 if len(files)!=1:raise SystemExit('Expected exactly one pinned ChatMessageCell')
 file=files[0];source=file.read_text(encoding='utf-8');updated=patch(source)
 if args.output and args.apply:raise SystemExit('Choose output or apply, not both')
 if args.output:args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(updated,encoding='utf-8',newline='\n')
 if args.apply:file.write_text(updated,encoding='utf-8',newline='\n')
 print(json.dumps({'methods_verified':len(PIN),'applied':args.apply,'separate_output':bool(args.output),'sha256':hashlib.sha256(updated.encode()).hexdigest()}))
