"""Prepare local document zoom, inline Python and system speech atop preview9."""
import json,shutil,xml.etree.ElementTree as ET
from prepare_repack import ROOT,within_work,A,resource_names
from patch_inline_python import patch
work=within_work(ROOT/'work-preview7');decoded=work/'decoded';overlay=ROOT.parent/'overlay'
version=decoded/'apktool.yml';s=version.read_text(encoding='utf-8')
if 'versionCode: 10009' not in s:raise ValueError('Expected verified preview9 baseline')
report=json.loads((work/'report.json').read_text(encoding='utf-8'))
public=decoded/'res/values/public.xml';tree=ET.parse(public);ids={(n.get('type'),n.get('name')):int(n.get('id'),16) for n in tree.getroot()}
for kind,name in resource_names(overlay/'res'):
 if (kind,name) not in ids:
  value=max(v for (t,_),v in ids.items() if t==kind)+1;ids[kind,name]=value
  ET.SubElement(tree.getroot(),'public',{'type':kind,'name':name,'id':f'0x{value:08x}'});report['resource_ids'][kind+'/'+name]=f'0x{value:08x}'
for source in (overlay/'res').rglob('*'):
 if source.is_file():
  target=decoded/'res'/source.relative_to(overlay/'res');target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
tree.write(public,encoding='utf-8',xml_declaration=True)
lines=['package org.telegram.messenger;','public final class R {']
for kind in sorted({key.split('/')[0] for key in report['resource_ids']}):
 lines.append('public static final class '+kind+' {')
 for key,value in sorted(report['resource_ids'].items()):
  if key.startswith(kind+'/'):lines.append('public static final int '+key.split('/')[1].replace('.','_')+' = '+value+';')
 lines.append('}')
lines.append('}');(work/'generated/R.java').write_text('\n'.join(lines),encoding='utf-8')
manifest=decoded/'AndroidManifest.xml';mt=ET.parse(manifest);app=mt.getroot().find('application')
name='org.telegram.messenger.family.FamilyTranscriptionActivity'
if not any(n.get(A+'name')==name for n in app.findall('activity')):
 ET.SubElement(app,'activity',{A+'name':name,A+'exported':'false',A+'theme':'@style/FamilyGramTheme',A+'windowSoftInputMode':'adjustResize'})
queries=mt.getroot().find('queries')
if queries is None:queries=ET.SubElement(mt.getroot(),'queries')
if not any(n.get(A+'name')=='android.speech.RecognitionService' for n in queries.findall('intent/action')):
 intent=ET.SubElement(queries,'intent');ET.SubElement(intent,'action',{A+'name':'android.speech.RecognitionService'})
mt.write(manifest,encoding='utf-8',xml_declaration=True)
cell=decoded/'smali_classes5/org/telegram/ui/Cells/ChatMessageCell.smali';cell.write_text(patch(cell.read_text(encoding='utf-8')),encoding='utf-8')
version.write_text(s.replace('versionCode: 10009','versionCode: 10010').replace('versionName: 0.1.0-preview9','versionName: 0.1.0-preview10'),encoding='utf-8')
report.update({'version_code':10010,'version_name':'0.1.0-preview10','features':'Local PDF pan/zoom/high resolution; formatted Office; inline sandbox Python; system on-device speech (device support required)'})
(work/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
print('Preview10 resources, manifest and all four hash-verified inline Python wrappers prepared.')
