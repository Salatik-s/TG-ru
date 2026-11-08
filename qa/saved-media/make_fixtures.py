from pathlib import Path
import io,zipfile,urllib.request,json,hashlib
root=Path(__file__).resolve().parent;target=root/'fixtures';target.mkdir(exist_ok=True)
objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>']
for page in [1,2]:
    index=3 if page==1 else 5
    objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents {index+1} 0 R >>'.encode())
    text=f'BT /F1 24 Tf 50 740 Td (SAA_QA_PDF_PAGE_{page}) Tj ET'.encode();objects.append(f'<< /Length {len(text)} >>\nstream\n'.encode()+text+b'\nendstream')
objects.append(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
pdf=io.BytesIO();pdf.write(b'%PDF-1.4\n');offsets=[0]
for n,data in enumerate(objects,1):offsets.append(pdf.tell());pdf.write(f'{n} 0 obj\n'.encode()+data+b'\nendobj\n')
xref=pdf.tell();pdf.write(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
for offset in offsets[1:]:pdf.write(f'{offset:010d} 00000 n \n'.encode())
pdf.write(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode());(target/'fixture.pdf').write_bytes(pdf.getvalue())
with zipfile.ZipFile(target/'fixture.docx','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
    z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
    z.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>SAA_QA_DOCX &amp; &lt; &gt;</w:t></w:r></w:p><w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p></w:body></w:document>')
(target/'fixture.rtf').write_text(r'{\rtf1\ansi SAA_QA_RTF\par Second paragraph}',encoding='ascii')
(target/'fixture.txt').write_text('SAA_QA_TEXT\nSecond line',encoding='utf-8')
with zipfile.ZipFile(target/'fixture.odt','w',zipfile.ZIP_DEFLATED) as z:z.writestr('content.xml','<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text><text:p>SAA_QA_ODT</text:p></office:text></office:body></office:document-content>')
# Public Apache POI blank template only; the generated fixture replaces text/metadata.
request=urllib.request.Request('https://api.github.com/repos/apache/poi/commits/trunk',headers={'User-Agent':'SAA-build'})
commit=json.load(urllib.request.urlopen(request,timeout=30))['sha']
url=f'https://raw.githubusercontent.com/apache/poi/{commit}/test-data/document/empty.doc';data=urllib.request.urlopen(url,timeout=30).read();(target/'empty-template.doc').write_bytes(data)
(target/'template-provenance.json').write_text(json.dumps({'url':url,'sha256':hashlib.sha256(data).hexdigest()},indent=2))
print('Generated synthetic PDF/DOCX/ODT/RTF/text fixtures and verified blank DOC template origin.')
