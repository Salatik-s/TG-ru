import org.telegram.messenger.family.FamilyOfficeRenderer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Pure JVM fixture checks; no network, account, Android runtime or production mutations. */
public final class OfficeRendererTest {
    private static int passed;
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String O="urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String T="urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String S="urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String F="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    public static void main(String[] args) throws Exception {
        String out=render(word("<w:p><w:pPr><w:jc w:val='center'/></w:pPr><w:r><w:rPr><w:b/><w:i/><w:u w:val='single'/><w:sz w:val='30'/></w:rPr><w:t>A &amp; B &lt; C &gt; D</w:t></w:r></w:p>"),"docx");
        check(out.contains("A &amp; B &lt; C &gt; D")&&out.contains("font-weight:bold")&&out.contains("font-style:italic")&&out.contains("text-decoration:underline")&&out.contains("15.0pt")&&out.contains("text-align:center"),"word_format_and_escaping");
        check(out.contains("default-src 'none'")&&out.contains("img-src data:")&&out.contains("base-uri 'none'")&&!out.contains("<script"),"document_csp");
        Map<String,byte[]> styles=word("<w:p><w:pPr><w:pStyle w:val='Heading1'/></w:pPr><w:r><w:t>Heading</w:t></w:r></w:p>");
        styles.put("word/styles.xml",utf("<w:styles xmlns:w='"+W+"'><w:style w:styleId='Base'><w:rPr><w:b/><w:sz w:val='48'/></w:rPr></w:style><w:style w:styleId='Heading1'><w:basedOn w:val='Base'/><w:name w:val='heading 1'/></w:style></w:styles>"));
        out=render(styles,"docx"); check(out.contains("<h1")&&out.contains("24.0pt")&&out.contains("font-weight:bold"),"heading_and_style_inheritance");
        out=render(word("<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val='2'/><w:shd w:fill='ffeeaa'/></w:tcPr><w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"),"docx");
        check(out.contains("<table>")&&out.contains("colspan=\"2\"")&&out.contains("background-color:#ffeeaa")&&out.contains("cell"),"word_table");
        out=render(word("<w:p><w:hyperlink xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships' r:id='external'><w:r><w:t>visible label</w:t></w:r></w:hyperlink><w:r><w:instrText>https://private.invalid</w:instrText></w:r></w:p>"),"docx");
        check(out.contains("visible label")&&!out.contains("<a ")&&!out.contains("https://private.invalid"),"inert_links_and_fields");
        byte[] png=png(); Map<String,byte[]> pictures=pictureDoc(); pictures.put("word/media/image.png",png);
        out=render(pictures,"docx"); String b64=Base64.getEncoder().encodeToString(png);
        check(out.contains("data:image/png;base64,"+b64),"bounded_embedded_png");
        pictures.put("word/_rels/document.xml.rels",utf("<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='img' TargetMode='External' Target='https://example.invalid/a.png'/></Relationships>"));
        out=render(pictures,"docx"); check(!out.contains("<img ")&&!out.contains("example.invalid"),"external_image_omitted");
        pictures=pictureDoc(); byte[] huge=png.clone(); huge[16]=0x7f; pictures.put("word/media/image.png",huge);
        check(!render(pictures,"docx").contains("<img "),"image_dimensions_rejected");
        Map<String,byte[]> budget=pictureDoc();
        String drawing="<w:r><w:drawing><a:blip xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships' r:embed='img'/></w:drawing></w:r>";
        budget.put("word/document.xml",word("<w:p>"+drawing.repeat(4)+"</w:p>").get("word/document.xml"));
        BufferedImage noise=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB); Random random=new Random(7);
        for(int y=0;y<128;y++)for(int x=0;x<128;x++)noise.setRGB(x,y,random.nextInt());
        ByteArrayOutputStream imageBytes=new ByteArrayOutputStream(); ImageIO.write(noise,"png",imageBytes); budget.put("word/media/image.png",imageBytes.toByteArray());
        out=render(budget,"docx"); check(out.split("<img ",-1).length-1==3&&out.contains("Изображение не показано")&&out.length()<=350000,"total_base64_image_budget");
        Map<String,byte[]> alternate=new HashMap<>(); alternate.put("word/document.xml",utf("<x:document xmlns:x='"+W+"'><x:body><x:p><x:r><x:t>prefix-independent</x:t></x:r></x:p></x:body></x:document>"));
        check(render(alternate,"docx").contains("prefix-independent"),"namespace_prefix_independent");
        check(!render(word("<fake:p xmlns:fake='urn:not-word'><fake:r><fake:t>wrongnamespace</fake:t></fake:r></fake:p>"),"docx").contains("wrongnamespace"),"namespace_spoof_ignored");
        Map<String,byte[]> odt=new HashMap<>();
        odt.put("content.xml",utf("<o:document-content xmlns:o='"+O+"' xmlns:t='"+T+"' xmlns:s='"+S+"' xmlns:f='"+F+"' xmlns:tb='urn:oasis:names:tc:opendocument:xmlns:table:1.0'><o:automatic-styles><s:style s:name='em' s:family='text'><s:text-properties f:font-weight='bold' f:font-style='italic'/></s:style></o:automatic-styles><o:body><o:text><t:h t:outline-level='2'>Title</t:h><t:p>left<t:s t:c='3'/><t:span t:style-name='em'>right &amp; other</t:span></t:p><tb:table><tb:table-row><tb:table-cell tb:number-columns-spanned='2'><t:p>ODT cell</t:p></tb:table-cell></tb:table-row></tb:table></o:text></o:body></o:document-content>"));
        out=render(odt,"odt"); check(out.contains("<h2")&&out.contains("font-weight:bold")&&out.contains("font-style:italic")&&out.contains("right &amp; other")&&out.contains("ODT cell")&&out.contains("colspan=\"2\""),"odt_structure_and_styles");
        out=FamilyOfficeRenderer.render(utf("<script>alert(1)</script><img src='https://example.invalid/a'>"),"html");
        check(out.contains("&lt;script&gt;")&&!out.contains("<script>")&&!out.contains("<img src="),"html_is_inert_text");
        out=FamilyOfficeRenderer.render(new byte[]{(byte)0xff,(byte)0xfe,0x1f,0x04,0x40,0x04},"txt");
        check(out.contains("Пр"),"utf16_plain_text");
        out=FamilyOfficeRenderer.render(utf("A<&".repeat(160000)),"txt");
        check(out.length()<=350000&&out.contains("Документ показан не полностью")&&out.endsWith("</body></html>"),"bounded_balanced_truncation");
        reject(()->render(wordWithXml("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///nonexistent'>]><w:document xmlns:w='"+W+"'><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>"),"docx"),"doctype_rejected");
        Map<String,byte[]> utf16=new HashMap<>(); utf16.put("word/document.xml",("<?xml version='1.0' encoding='UTF-16'?><!DOCTYPE x [<!ENTITY x 'expanded'>]><w:document xmlns:w='"+W+"'><w:body/></w:document>").getBytes(StandardCharsets.UTF_16));
        reject(()->render(utf16,"docx"),"utf16_doctype_rejected");
        Map<String,byte[]> traversal=word("<w:p/>"); traversal.put("../outside",new byte[]{1}); reject(()->render(traversal,"docx"),"archive_traversal_rejected");
        Map<String,byte[]> bomb=word("<w:p/>"); bomb.put("unused.bin",new byte[17*1024*1024]); reject(()->render(bomb,"docx"),"unused_zip_bomb_bounded");
        reject(()->render(wordWithXml("<w:document xmlns:w='"+W+"'><w:body>"+"<w:sdt>".repeat(100)+"x"+"</w:sdt>".repeat(100)+"</w:body></w:document>"),"docx"),"xml_depth_rejected");
        reject(()->render(word("<w:p>"+"<w:r/>".repeat(60001)+"</w:p>"),"docx"),"xml_nodes_rejected_before_dom");
        try { FamilyOfficeRenderer.render(utf("{\\rtf1 test}"),"rtf"); throw new AssertionError("rtf_fallback"); } catch(UnsupportedOperationException expected) { passed++; }
        if(args.length==1) {
            Path fixture=Paths.get(args[0]);
            out=FamilyOfficeRenderer.render(Files.readAllBytes(fixture.resolve("fixture.doc")),"doc");
            check(out.contains("Упрощённое отображение")&&out.contains("SAA_QA_DOC_BINARY")&&out.contains("Second paragraph"),"real_poi_doc_fixture");
            for(String ext:new String[]{"docx","odt"}) check(FamilyOfficeRenderer.render(Files.readAllBytes(fixture.resolve("fixture."+ext)),ext).endsWith("</body></html>"),"existing_"+ext+"_fixture");
        }
        System.out.println("PASS "+passed+" document rendering and safety fixtures");
    }
    interface Check { void run()throws Exception; }
    static void reject(Check run,String name)throws Exception{try{run.run();throw new AssertionError(name);}catch(IOException expected){passed++;}}
    static void check(boolean yes,String name){if(!yes)throw new AssertionError(name);passed++;}
    static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static Map<String,byte[]> word(String body){return wordWithXml("<w:document xmlns:w='"+W+"'><w:body>"+body+"</w:body></w:document>");}
    static Map<String,byte[]> wordWithXml(String xml){Map<String,byte[]> m=new LinkedHashMap<>();m.put("word/document.xml",utf(xml));return m;}
    static String render(Map<String,byte[]> files,String ext)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){for(Map.Entry<String,byte[]> e:files.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}return FamilyOfficeRenderer.render(b.toByteArray(),ext);}
    static byte[] png()throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",b);return b.toByteArray();}
    static Map<String,byte[]> pictureDoc(){Map<String,byte[]> m=word("<w:p><w:r><w:drawing><a:blip xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships' r:embed='img'/></w:drawing></w:r></w:p>");m.put("word/_rels/document.xml.rels",utf("<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='img' Target='media/image.png'/></Relationships>"));return m;}
}
