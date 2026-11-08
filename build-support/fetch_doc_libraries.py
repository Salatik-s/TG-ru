"""Fetch checksum-verified Apache POI dependencies from Maven Central for local DOC text extraction."""
from pathlib import Path
import urllib.request,hashlib,json,zipfile,io
root=Path(__file__).resolve().parent.parent
artifacts=[('org.apache.poi','poi','6.0.0'),('org.apache.poi','poi-scratchpad','6.0.0'),('commons-io','commons-io','2.22.0'),('commons-codec','commons-codec','1.23.0'),('org.apache.commons','commons-collections4','4.6.0'),('org.apache.commons','commons-math3','3.6.1'),('com.zaxxer','SparseBitSet','1.3'),('org.apache.logging.log4j','log4j-api','2.25.3')]
target=root/'tools/docs-jars';target.mkdir(exist_ok=True)
licenses=root/'overlay/assets/familygram/document-licenses';licenses.mkdir(parents=True,exist_ok=True)
report=[]
for group,name,version in artifacts:
    url=f'https://repo.maven.apache.org/maven2/{group.replace(".","/")}/{name}/{version}/{name}-{version}.jar'
    data=urllib.request.urlopen(url,timeout=45).read()
    try:expected=urllib.request.urlopen(url+'.sha512',timeout=20).read().decode().strip().split()[0];algorithm='sha512'
    except Exception:expected=urllib.request.urlopen(url+'.sha1',timeout=20).read().decode().strip().split()[0];algorithm='sha1'
    if hashlib.new(algorithm,data).hexdigest()!=expected:raise ValueError('Maven checksum mismatch: '+name)
    output=target/f'{name}-{version}.jar'
    with zipfile.ZipFile(io.BytesIO(data)) as source,zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as clean:
        for member in source.namelist():
            if member.endswith('.class') and not member.startswith('META-INF/') and member!='module-info.class':clean.writestr(member,source.read(member))
            elif member.upper().startswith('META-INF/') and any(word in member.upper() for word in ['LICENSE','NOTICE']):
                (licenses/(name+'-'+Path(member).name)).write_bytes(source.read(member))
    report.append({'artifact':f'{group}:{name}:{version}','url':url,'upstream_sha256':hashlib.sha256(data).hexdigest(),'android_input_sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'derivation':'Only base class files; module metadata and multi-release variants excluded'})
(licenses/'provenance.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print('Verified and prepared',len(report),'local document libraries; licenses retained.')
