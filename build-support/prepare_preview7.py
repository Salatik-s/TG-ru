"""Derive S. A. A. from frozen preview6, preserving resource IDs and accounts."""
import json,re,shutil,xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from prepare_repack import ROOT,within_work,digest,find_smali,METHOD,A,resource_names

base=within_work(ROOT/'work-preview6');work=within_work(ROOT/'work-preview7');overlay=ROOT.parent/'overlay'
if digest(ROOT.parent/'release/FamilyGram-0.1.0-preview6.apk')!='fa5cbd820dc8d8e1c8c0e8d09558f06d6a09ba3841295fd53744ccbc60491d8c':raise ValueError('Preview6 baseline changed')
if work.exists() and ((work/'report.json').exists() or set(p.name for p in work.iterdir())-{'decoded'}):raise ValueError('Refusing to replace a prepared work directory')
work.mkdir(exist_ok=True)
def ignore_cache(directory,names):return {'build','dist'}&set(names) if Path(directory)==base/'decoded' else set()
def parallel_copy(source,target,ignore=None):
    futures=[]
    with ThreadPoolExecutor(max_workers=16) as pool:
        def queue(src,dst):futures.append(pool.submit(shutil.copy2,src,dst));return dst
        shutil.copytree(source,target,ignore=ignore,copy_function=queue,dirs_exist_ok=True)
        for future in futures:future.result()
parallel_copy(base/'decoded',work/'decoded',ignore_cache);parallel_copy(base/'generated',work/'generated')
decoded=work/'decoded';generated=work/'generated';report=json.loads((base/'report.json').read_text(encoding='utf-8'))
version=decoded/'apktool.yml';s=version.read_text(encoding='utf-8');s,n=re.subn(r'(?m)^(\s*versionCode:) 10006$',r'\g<1> 10007',s);s,m=re.subn(r'(?m)^(\s*versionName:) 0\.1\.0-preview6$',r'\g<1> 0.1.0-preview7',s)
if n!=1 or m!=1:raise ValueError('Version metadata differs')
version.write_text(s,encoding='utf-8')
public=decoded/'res/values/public.xml';tree=ET.parse(public);ids={(n.get('type'),n.get('name')):int(n.get('id'),16) for n in tree.getroot()}
for kind,name in resource_names(overlay/'res'):
    if (kind,name) not in ids:
        value=max(v for (t,_),v in ids.items() if t==kind)+1;ids[kind,name]=value
        ET.SubElement(tree.getroot(),'public',{'type':kind,'name':name,'id':f'0x{value:08x}'});report['resource_ids'][kind+'/'+name]=f'0x{value:08x}'
for source in (overlay/'res').rglob('*'):
    if source.is_file():destination=decoded/'res'/source.relative_to(overlay/'res');destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,destination)
tree.write(public,encoding='utf-8',xml_declaration=True)
lines=['package org.telegram.messenger;','public final class R {']
for kind in sorted({key.split('/')[0] for key in report['resource_ids']}):
    lines.append('public static final class '+kind+' {')
    for key,value in sorted(report['resource_ids'].items()):
        if key.startswith(kind+'/'):lines.append('public static final int '+key.split('/')[1].replace('.','_')+' = '+value+';')
    lines.append('}')
lines.append('}');(generated/'R.java').write_text('\n'.join(lines),encoding='utf-8')
brand_names={'AppName','Page1Title','AppIconExteraless','Nagram','NagramX','OpenExtera','OpenExteraPreferences','CustomTitleHint','OECrashTitle','OEGeneralResetSettingsDone','OECloudHeroText','fg_app_name'}
for source in (decoded/'res').glob('values*/*.xml'):
    if source.name=='public.xml':continue
    text=source.read_text(encoding='utf-8')
    def brand(match):
        if match.group(1) not in brand_names:return match.group(0)
        entire=match.group(0);start=entire.index('>')+1;end=entire.rindex('</string>')
        return entire[:start]+re.sub('exteraless|FamilyGram','S. A. A.',entire[start:end],flags=re.I)+entire[end:]
    changed=re.sub(r'<string name="([^"]+)"[^>]*>.*?</string>',brand,text,flags=re.S)
    if changed!=text:source.write_text(changed,encoding='utf-8')
manifest=decoded/'AndroidManifest.xml';mt=ET.parse(manifest);app=mt.getroot().find('application');app.set(A+'label','S. A. A.')
for alias in app.findall('activity-alias'):alias.set(A+'label','S. A. A.')
for suffix in ['FamilyDevicesActivity','FamilyPluginsActivity','FamilyPythonActivity','FamilyDocumentActivity']:
    ET.SubElement(app,'activity',{A+'name':'org.telegram.messenger.family.'+suffix,A+'exported':'false',A+'theme':'@style/FamilyGramTheme',A+'windowSoftInputMode':'adjustResize'})
ET.SubElement(app,'service',{A+'name':'org.telegram.messenger.family.FamilyDocumentService',A+'exported':'false',A+'isolatedProcess':'true',A+'process':':saa_documents'})
mt.write(manifest,encoding='utf-8',xml_declaration=True)
locale=find_smali(decoded,'org.telegram.messenger.LocaleController');text=locale.read_text(encoding='utf-8');old='const-string v0, "FamilyGram"'
if text.count(old)!=1:raise ValueError('App-name helper changed')
locale.write_text(text.replace(old,'const-string v0, "S. A. A."'),encoding='utf-8')

def wrap(class_name,signature,new_name,body):
    path=find_smali(decoded,class_name);text=path.read_text(encoding='utf-8');matches=[m for m in METHOD.finditer(text) if m.group('head')==signature]
    if len(matches)!=1:raise ValueError('Pinned method mismatch '+signature)
    match=matches[0];original=match.group(0);head=signature.replace(signature.split('(')[0].split()[-1],new_name,1)
    renamed=original.replace('.method '+signature,'.method '+head,1)
    path.write_text(text[:match.start()]+body+'\n'+renamed+text[match.end():],encoding='utf-8')
fill='public fillMessageMenu(Lorg/telegram/messenger/MessageObject;Ljava/util/ArrayList;Ljava/util/ArrayList;Ljava/util/ArrayList;)V'
wrap('org.telegram.ui.ChatActivity',fill,'familyOriginalFillMessageMenu','''.method '''+fill+'''
    .locals 0
    invoke-virtual/range {p0 .. p4}, Lorg/telegram/ui/ChatActivity;->familyOriginalFillMessageMenu(Lorg/telegram/messenger/MessageObject;Ljava/util/ArrayList;Ljava/util/ArrayList;Ljava/util/ArrayList;)V
    invoke-static/range {p0 .. p4}, Lorg/telegram/messenger/family/FamilyChatTools;->fillMenu(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/ArrayList;Ljava/util/ArrayList;Ljava/util/ArrayList;)V
    return-void
.end method''')
wrap('org.telegram.ui.ChatActivity','public processSelectedOption(I)V','familyOriginalProcessSelectedOption','''.method public processSelectedOption(I)V
    .locals 1
    invoke-static {p0, p1}, Lorg/telegram/messenger/family/FamilyChatTools;->select(Ljava/lang/Object;I)Z
    move-result v0
    if-eqz v0, :saa_original_option
    return-void
  :saa_original_option
    invoke-virtual {p0, p1}, Lorg/telegram/ui/ChatActivity;->familyOriginalProcessSelectedOption(I)V
    return-void
.end method''')
sig='public static openForView(Ljava/io/File;Ljava/lang/String;Ljava/lang/String;Landroid/app/Activity;Lorg/telegram/ui/ActionBar/Theme$ResourcesProvider;Z)Z'
wrap('org.telegram.messenger.AndroidUtilities',sig,'familyOriginalOpenForView','''.method '''+sig+'''
    .locals 1
    invoke-static {p0, p1, p3}, Lorg/telegram/messenger/family/FamilyChatTools;->openDocument(Ljava/io/File;Ljava/lang/String;Landroid/app/Activity;)Z
    move-result v0
    if-eqz v0, :saa_external_viewer
    return v0
  :saa_external_viewer
    invoke-static/range {p0 .. p5}, Lorg/telegram/messenger/AndroidUtilities;->familyOriginalOpenForView(Ljava/io/File;Ljava/lang/String;Ljava/lang/String;Landroid/app/Activity;Lorg/telegram/ui/ActionBar/Theme$ResourcesProvider;Z)Z
    move-result v0
    return v0
.end method''')
application=find_smali(decoded,'org.telegram.messenger.ApplicationLoader');text=application.read_text(encoding='utf-8');needle='.method public onCreate()V\n    .locals 9\n'
if text.count(needle)!=1:raise ValueError('Application bootstrap signature changed')
text=text.replace(needle,needle+'''
    invoke-static {}, Lorg/telegram/messenger/family/FamilyDocumentService;->isIsolatedDocumentProcess()Z
    move-result v0
    if-eqz v0, :saa_normal_application
    invoke-super {p0}, Landroid/app/Application;->onCreate()V
    return-void
  :saa_normal_application
''',1);application.write_text(text,encoding='utf-8')
sender=find_smali(decoded,'org.telegram.messenger.SendMessagesHelper');text=sender.read_text(encoding='utf-8');count=0
def photo_ref(match):
    global count
    if not match.group('head').startswith('private updateMediaPaths('):return match.group(0)
    def add(m):
        global count
        count+=1
        return '    invoke-static {'+m['dst']+', '+m['src']+'}, Lorg/telegram/messenger/family/FamilyChatTools;->copyPhotoReference(Ljava/lang/Object;Ljava/lang/Object;)V\n\n'+m.group(0)
    return re.sub(r'    iget-wide (?P<temp>v\d+), (?P<src>v\d+), Lorg/telegram/tgnet/TLRPC\$Photo;->access_hash:J\s+iput-wide (?P=temp), (?P<dst>v\d+), Lorg/telegram/tgnet/TLRPC\$Photo;->access_hash:J',add,match.group(0))
text=METHOD.sub(photo_ref,text)
if count!=3:raise ValueError('Expected three photo-reference update sites, got '+str(count))
sender.write_text(text,encoding='utf-8')
for source in (overlay/'assets').rglob('*'):
    if source.is_file():destination=decoded/'assets'/source.relative_to(overlay/'assets');destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,destination)
report.update({'version_code':10007,'version_name':'0.1.0-preview7','status':'prepared S. A. A. owner/settings/offline tools','preview6_signed_sha256':'fa5cbd820dc8d8e1c8c0e8d09558f06d6a09ba3841295fd53744ccbc60491d8c','photo_reference_sites':count})
for key in ['unsigned_apk','unsigned_sha256','preview6_delta_verified']:report.pop(key,None)
(work/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
print('Prepared S. A. A., stable resource IDs, explicit owner UI, disabled local plugins and isolated document parser.')
