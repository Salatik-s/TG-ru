"""User's final names: TG-ru app/file, S.A.A. only the chat heading."""
from pathlib import Path
import json,re,xml.etree.ElementTree as ET
from prepare_repack import ROOT,within_work,A,find_smali,METHOD
work=within_work(ROOT/'work-preview7');decoded=work/'decoded'
for path in (decoded/'res').glob('values*/*.xml'):
    if path.name=='public.xml':continue
    text=path.read_text(encoding='utf-8')
    def replace(m):
        entire=m.group(0);a=entire.index('>')+1;b=entire.rindex('</string>');value=entire[a:b]
        value=value.replace('S. A. A.','S.A.A.' if m.group(1)=='CustomTitleHint' else 'TG-ru')
        return entire[:a]+value+entire[b:]
    changed=re.sub(r'<string name="([^"]+)"[^>]*>.*?</string>',replace,text,flags=re.S)
    if changed!=text:path.write_text(changed,encoding='utf-8')
# Keep source overlays consistent with generated staging resources.
features=ROOT.parent/'overlay/res/values/fg_features.xml';text=features.read_text(encoding='utf-8').replace('>FamilyGram<','>TG-ru<');features.write_text(text,encoding='utf-8')
manifest=decoded/'AndroidManifest.xml';tree=ET.parse(manifest);app=tree.getroot().find('application');app.set(A+'label','TG-ru')
for node in app.findall('activity-alias'):node.set(A+'label','TG-ru')
tree.write(manifest,encoding='utf-8',xml_declaration=True)
locale=find_smali(decoded,'org.telegram.messenger.LocaleController');text=locale.read_text(encoding='utf-8');needle='const-string v0, "S. A. A."'
if text.count(needle)!=1:raise ValueError('Application name helper differs')
locale.write_text(text.replace(needle,'const-string v0, "TG-ru"'),encoding='utf-8')
font=find_smali(decoded,'tw.nekomimi.nekogram.helpers.TypefaceHelper');text=font.read_text(encoding='utf-8');matched=0
def title(m):
    global matched
    if m.group('head')!='public static getTitleText(I)Landroid/text/SpannableStringBuilder;':return m.group(0)
    needle='    iget-object v1, v1, Ltw/nekomimi/nekogram/config/ConfigItem;->defaultValue:Ljava/lang/Object;'
    if m.group(0).count(needle)!=1:raise ValueError('Pinned chat heading input changed')
    matched+=1;return m.group(0).replace(needle,'    const-string v1, "S.A.A."')
text=METHOD.sub(title,text)
if matched!=1:raise ValueError('Pinned chat heading method missing')
font.write_text(text,encoding='utf-8')
version=decoded/'apktool.yml';text=version.read_text(encoding='utf-8').replace('versionCode: 10007','versionCode: 10008').replace('versionName: 0.1.0-preview7','versionName: 0.1.0-preview8');version.write_text(text,encoding='utf-8')
report=json.loads((work/'report.json').read_text(encoding='utf-8'));report.update({'version_code':10008,'version_name':'0.1.0-preview8','application_label':'TG-ru','chat_heading':'S.A.A.','artifact_filename':'TG-ru.apk'})
report.pop('shared_semantically_verified_record_markers',None)
(work/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
print('Applied separate app/file and chat-heading names; preserved title typography and status badge.')
