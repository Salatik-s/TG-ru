"""Install native chat transcription, wider inline Python and shared 10-second runner."""
import hashlib,json,shutil,xml.etree.ElementTree as ET
from prepare_repack import ROOT,within_work,A,resource_names
from patch_voice_transcription import prepare
work=within_work(ROOT/'work-preview7');decoded=work/'decoded';overlay=ROOT.parent/'overlay'
version=decoded/'apktool.yml';text=version.read_text(encoding='utf-8')
if 'versionCode: 10010' not in text:raise ValueError('Expected preview10 resource baseline')
changes=prepare(decoded)  # Verify all six pinned methods before mutations.
report=json.loads((work/'report.json').read_text(encoding='utf-8'));public=decoded/'res/values/public.xml';tree=ET.parse(public)
ids={(n.get('type'),n.get('name')):int(n.get('id'),16) for n in tree.getroot()}
for kind,name in resource_names(overlay/'res'):
 if (kind,name) not in ids:
  value=max(v for (t,_),v in ids.items() if t==kind)+1;ids[kind,name]=value
  ET.SubElement(tree.getroot(),'public',{'type':kind,'name':name,'id':f'0x{value:08x}'});report['resource_ids'][kind+'/'+name]=f'0x{value:08x}'
tree.write(public,encoding='utf-8',xml_declaration=True)
for category in ['res','assets']:
 for source in (overlay/category).rglob('*'):
  if source.is_file():
   target=decoded/category/source.relative_to(overlay/category);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
lines=['package org.telegram.messenger;','public final class R {']
for kind in sorted({key.split('/')[0] for key in report['resource_ids']}):
 lines.append('public static final class '+kind+' {')
 for key,value in sorted(report['resource_ids'].items()):
  if key.startswith(kind+'/'):lines.append('public static final int '+key.split('/')[1].replace('.','_')+' = '+value+';')
 lines.append('}')
lines.append('}');(work/'generated/R.java').write_text('\n'.join(lines),encoding='utf-8')
manifest=decoded/'AndroidManifest.xml';mt=ET.parse(manifest);app=mt.getroot().find('application')
for activity in list(app.findall('activity')):
 if activity.get(A+'name')=='org.telegram.messenger.family.FamilyTranscriptionActivity':app.remove(activity)
mt.write(manifest,encoding='utf-8',xml_declaration=True)
for path,source in changes:path.write_text(source,encoding='utf-8')
version.write_text(text.replace('versionCode: 10010','versionCode: 10011').replace('versionName: 0.1.0-preview10','versionName: 0.1.0-preview11'),encoding='utf-8')
report.update({'version_code':10011,'version_name':'0.1.0-preview11','features':'Native chat speech bubble on-device only; wide inline Python with 10-second execution and actual tracebacks; PDF pan/zoom/high resolution; local Office formatting','voice_hook_sha256':{path.name:hashlib.sha256(source.encode()).hexdigest() for path,source in changes},'overlay_asset_sha256':{p.relative_to(overlay/'assets').as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in (overlay/'assets').rglob('*') if p.is_file()}})
(work/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
print('Preview11 native voice hooks, shared controller assets and wide inline UI prepared. Separate speech Activity removed.')
