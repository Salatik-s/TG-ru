"""Recompile only the overlay atop an explicitly hashed, resource-verified preview7 base."""
import argparse,hashlib,json,re,subprocess,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
from prepare_repack import ROOT,digest,within_work
from verify_dex import verify_unique
from dex_marker import share_marker
parser=argparse.ArgumentParser();parser.add_argument('--base-sha256',required=True);parser.add_argument('--work',type=Path,default=ROOT/'work-preview7');parser.add_argument('--docs-jars',type=Path,default=ROOT.parent/'tools/docs-jars');args=parser.parse_args()
work=within_work(args.work);base=work/'rebuilt-unsigned.apk'
if not re.fullmatch('[a-f0-9]{64}',args.base_sha256) or digest(base)!=args.base_sha256:raise ValueError('Verified preview7 base hash required')
report=json.loads((work/'report.json').read_text(encoding='utf-8'));attempt=1
while (work/f'overlay-rebuild-{attempt}').exists():attempt+=1
output=work/f'overlay-rebuild-{attempt}';output.mkdir();classes=output/'classes';classes.mkdir();dex=output/'dex';dex.mkdir()
jdk=Path('C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin');sdk=Path('C:/Users/andre/AppData/Local/Android/Sdk');bt=sdk/'build-tools/37.0.0';android=sdk/'platforms/android-35/android.jar'
sources=sorted((ROOT.parent/'overlay/java/org/telegram/messenger/family').glob('*.java'))+[work/'generated/R.java',ROOT/'FamilyBinaryGuard.java',ROOT/'FamilyBinarySettings.java',ROOT/'FamilyTelegramPreset.java']
fingerprints={str(p.resolve()):digest(p) for p in sources}
def run(*command):subprocess.run([str(x) for x in command],check=True)
run(jdk/'javac.exe','--release','17','-encoding','UTF-8','-classpath',android,'-d',classes,*sources)
if fingerprints!={str(p.resolve()):digest(p) for p in sources}:raise ValueError('Java changed during compilation')
jar=output/'overlay.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as archive:
    for item in (classes/'org/telegram/messenger/family').rglob('*.class'):archive.write(item,item.relative_to(classes).as_posix())
run(jdk/'java.exe','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','24','--lib',android,'--output',dex,jar,*sorted(args.docs_jars.glob('*.jar')))
shared=sum(share_marker(item,base) for item in dex.glob('*.dex'))
rebuilt=output/'repacked.apk'
with zipfile.ZipFile(base) as before,zipfile.ZipFile(rebuilt,'w') as after:
    for item in before.infolist():
        name=item.filename
        if re.fullmatch(r'classes(?:\d+)?\.dex',name) and name!='classes.dex' and int(name[7:-4])>=9:continue
        if name.startswith('META-INF/') and name.endswith(('.RSA','.SF','.MF','.EC','.DSA')):continue
        after.writestr(item,before.read(name))
    for index,item in enumerate(sorted(dex.glob('*.dex'),key=lambda p:1 if p.name=='classes.dex' else int(p.stem[7:])),9):after.write(item,f'classes{index}.dex',compress_type=zipfile.ZIP_DEFLATED)
result=verify_unique(rebuilt)
dump=subprocess.check_output([str(bt/'aapt2.exe'),'dump','resources',str(rebuilt)],encoding='utf-8');actual={name:value.lower() for value,name in re.findall(r'\bresource (0x[0-9a-fA-F]+) (?:[^\s:]+:)?([^\s]+)',dump)}
public={n.get('type')+'/'+n.get('name'):n.get('id').lower() for n in ET.parse(work/'decoded/res/values/public.xml').getroot()}
if any(actual.get(name)!=value for name,value in public.items()):raise ValueError('A public resource id changed')
aligned=work/'SAA-UNSIGNED.apk';run(bt/'zipalign.exe','-f','-P','16','4',rebuilt,aligned);run(bt/'zipalign.exe','-c','-P','16','4',aligned)
report.update({'status':'unsigned S. A. A. overlay compiled and verified','compiled_source_sha256':fingerprints,'dex_validation':result,'verified_public_resource_ids':len(public),'upstream_preview7_base_sha256':args.base_sha256,'private_record_marker_relocations':shared,'unsigned_apk':str(aligned),'unsigned_sha256':digest(aligned)})
report['native_sha256']={f'{abi}/{name}':digest(work/'decoded/lib'/abi/name) for abi in ['arm64-v8a','x86_64'] for name in ['libfamilytunnel.so','libfamilytgnet.so','libfamilycalls.so']}
(work/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
print('Overlay compiled; private marker relocated; all public IDs preserved:',result);print(aligned)
