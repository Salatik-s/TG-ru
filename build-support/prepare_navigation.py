"""Apply the verified preview9 navigation atop preview8; preserve all existing public IDs."""
import json,re,shutil,xml.etree.ElementTree as ET
from prepare_repack import ROOT,within_work,find_smali,METHOD,A,resource_names

work=within_work(ROOT/'work-preview7');decoded=work/'decoded';overlay=ROOT.parent/'overlay'
version=decoded/'apktool.yml';s=version.read_text(encoding='utf-8')
if 'versionCode: 10008' not in s:raise ValueError('Expected named preview8 baseline')
public=decoded/'res/values/public.xml';tree=ET.parse(public);ids={(n.get('type'),n.get('name')):int(n.get('id'),16) for n in tree.getroot()}
report=json.loads((work/'report.json').read_text(encoding='utf-8'))
for kind,name in resource_names(overlay/'res'):
    if (kind,name) not in ids:
        value=max(v for (t,_),v in ids.items() if t==kind)+1;ids[kind,name]=value
        ET.SubElement(tree.getroot(),'public',{'type':kind,'name':name,'id':f'0x{value:08x}'})
        report['resource_ids'][kind+'/'+name]=f'0x{value:08x}'
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
name='org.telegram.messenger.family.FamilyPrivacyActivity'
if any(n.get(A+'name')==name for n in app.findall('activity')):raise ValueError('Navigation already applied')
ET.SubElement(app,'activity',{A+'name':name,A+'exported':'false',A+'theme':'@style/FamilyGramTheme',A+'windowSoftInputMode':'adjustResize'})
mt.write(manifest,encoding='utf-8',xml_declaration=True)

path=find_smali(decoded,'org.telegram.ui.DialogsActivity');text=path.read_text(encoding='utf-8');count=0
def menu(match):
    global count
    if match.group('head')!='private showItemOptions()V':return match.group(0)
    body=match.group(0);needle='    invoke-virtual {v0, v3, v2}, Lorg/telegram/ui/Components/ItemOptions;->setColors(II)Lorg/telegram/ui/Components/ItemOptions;'
    if body.count(needle)!=1:raise ValueError('Pinned overflow shape changed')
    body=body.replace(needle,needle+'\n\n    invoke-static {v0, p0}, Lorg/telegram/messenger/family/FamilyNavigation;->addGhostMenu(Ljava/lang/Object;Ljava/lang/Object;)V')
    old='    sget-object v1, Ltw/nekomimi/nekogram/NekoConfig;->showGhostInDrawer:Ltw/nekomimi/nekogram/config/ConfigItem;\n\n    invoke-virtual {v1}, Ltw/nekomimi/nekogram/config/ConfigItem;->Bool()Z\n\n    move-result v1'
    if body.count(old)!=1:raise ValueError('Pinned legacy ghost menu changed')
    count+=1;return body.replace(old,'    const/4 v1, 0x0')
text=METHOD.sub(menu,text)
if count!=1:raise ValueError('Missing pinned main overflow')
path.write_text(text,encoding='utf-8')

path=find_smali(decoded,'app.exteraless.drawer.MainMenuHelper');text=path.read_text(encoding='utf-8')
signature='public static resolveMenuItem(ILapp/exteraless/drawer/MainMenuHelper$MenuContext;)Lapp/exteraless/drawer/MainMenuHelper$MenuItemInfo;'
matches=[m for m in METHOD.finditer(text) if m.group('head')==signature]
if len(matches)!=1:raise ValueError('Pinned custom menu signature changed')
match=matches[0];original=match.group(0).replace('resolveMenuItem(','familyOriginalResolveMenuItem(',1)
wrapper='''.method '''+signature+'''
    .locals 1
    const/16 v0, 0x6b
    if-ne p0, v0, :saa_original_menu
    invoke-static {p1}, Lorg/telegram/messenger/family/FamilyNavigation;->ghostMenuInfo(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Lapp/exteraless/drawer/MainMenuHelper$MenuItemInfo;
    return-object v0
  :saa_original_menu
    invoke-static {p0, p1}, Lapp/exteraless/drawer/MainMenuHelper;->familyOriginalResolveMenuItem(ILapp/exteraless/drawer/MainMenuHelper$MenuContext;)Lapp/exteraless/drawer/MainMenuHelper$MenuItemInfo;
    move-result-object v0
    return-object v0
.end method
'''
path.write_text(text[:match.start()]+wrapper+original+text[match.end():],encoding='utf-8')
version.write_text(s.replace('versionCode: 10008','versionCode: 10009').replace('versionName: 0.1.0-preview8','versionName: 0.1.0-preview9'),encoding='utf-8')
report.update({'version_code':10009,'version_name':'0.1.0-preview9','navigation':'Main overflow privacy; ordinary settings privacy/plugins/owner devices; optional system settings last'})
(work/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
print('Preview9 navigation prepared; existing public IDs and native transports preserved.')
