package org.telegram.messenger.family;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;

/** Bounded local document conversion. Does not resolve URLs or emit document-supplied HTML/CSS. */
public final class FamilyOfficeRenderer {
    private static final int INPUT = 32 * 1024 * 1024, EXPANDED = 16 * 1024 * 1024;
    private static final int XML = 2 * 1024 * 1024, OUTPUT = 350000, IMAGE = 128000, IMAGES = 200000;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String WS = "http://purl.oclc.org/ooxml/wordprocessingml/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String RS = "http://purl.oclc.org/ooxml/officeDocument/relationships";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String AS = "http://purl.oclc.org/ooxml/drawingml/main";
    private static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    private static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    private static final String XLINK = "http://www.w3.org/1999/xlink";
    private FamilyOfficeRenderer() { }

    public static String render(byte[] data, String extension) throws IOException, UnsupportedOperationException {
        if (data == null || data.length > INPUT) throw new IOException("document_limit");
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        Out out = new Out();
        try {
            if (ext.equals("docx") || ext.equals("odt")) {
                Package pack = unpack(data, ext.equals("docx"));
                Engine engine = new Engine(pack, out, ext.equals("docx"));
                engine.render();
            } else if (ext.equals("doc")) {
                out.notice("Упрощённое отображение: текст документа без исходного оформления.");
                out.open("pre", ""); out.text(legacy(data)); out.close();
            } else if (Arrays.asList("txt", "text", "log", "csv", "tsv", "json", "xml", "md", "html", "htm").contains(ext)) {
                if (ext.equals("html") || ext.equals("htm")) out.notice("HTML показан как текст. Активные элементы отключены.");
                out.open("pre", ""); out.text(decode(data)); out.close();
            } else throw new UnsupportedOperationException("unsupported_format");
        } catch (Limit exhausted) { out.truncated = true; }
        return out.finish();
    }

    private static final class Package {
        final Map<String, byte[]> files = new HashMap<>();
    }
    private static Package unpack(byte[] bytes, boolean word) throws IOException {
        Package result = new Package(); Set<String> names = new HashSet<>();
        int expanded = 0, count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (++count > 4096 || !safePath(name) || !names.add(name)) throw new IOException("archive_entries");
                boolean xml = word ? name.equals("word/document.xml") || name.equals("word/styles.xml") || name.equals("word/_rels/document.xml.rels")
                        : name.equals("content.xml") || name.equals("styles.xml");
                boolean image = word ? name.startsWith("word/media/") : name.startsWith("Pictures/");
                ByteArrayOutputStream keep = xml || image ? new ByteArrayOutputStream() : null;
                int entryBytes = 0; byte[] block = new byte[8192];
                for (int n; (n = zip.read(block)) != -1;) {
                    entryBytes += n; expanded += n;
                    if (expanded > EXPANDED || (xml && entryBytes > XML)) throw new IOException("archive_expansion");
                    // Large images are omitted; all archive entries are still consumed under the total budget.
                    if (keep != null && (!image || entryBytes <= IMAGE)) keep.write(block, 0, n);
                }
                if (keep != null && (!image || entryBytes <= IMAGE)) result.files.put(name, keep.toByteArray());
            }
        }
        if (!result.files.containsKey(word ? "word/document.xml" : "content.xml")) throw new IOException("document_missing");
        return result;
    }
    private static boolean safePath(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || value.startsWith("/") || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || value.indexOf('\0') >= 0) return false;
        for (String part : value.split("/")) if (part.equals("..") || part.equals(".")) return false;
        return true;
    }

    private static Document xml(byte[] bytes) throws IOException {
        String source = xmlText(bytes);
        // Check decoded characters before parsing, including UTF-16 input. No DTD is ever needed by these formats.
        if (source.contains("<!DOCTYPE") || source.contains("<!ENTITY")) throw new IOException("xml_declaration");
        try {
            // Reject excessive nesting/node counts before allocating a DOM tree.
            SAXParserFactory streaming = SAXParserFactory.newInstance(); streaming.setNamespaceAware(true);
            XMLReader reader = streaming.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("external_entity"); });
            org.xml.sax.helpers.DefaultHandler bounds = new org.xml.sax.helpers.DefaultHandler() {
                int depth, count;
                @Override public void startElement(String uri, String local, String name, Attributes attrs) throws SAXException {
                    count += 1 + attrs.getLength(); if (++depth > 90 || count > 60000) throw new SAXException("xml_complexity");
                }
                @Override public void endElement(String uri, String local, String name) { depth--; }
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            };
            reader.setContentHandler(bounds); reader.setErrorHandler(bounds);
            reader.parse(new InputSource(new StringReader(source)));
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true); factory.setExpandEntityReferences(false);
            try { factory.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) { }
            feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            feature(factory, "http://xml.org/sax/features/external-general-entities", false);
            feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            feature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("external_entity"); });
            builder.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) throws SAXException { throw e; }
                public void error(SAXParseException e) throws SAXException { throw e; }
                public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            Document document = builder.parse(new InputSource(new StringReader(source)));
            int[] nodes = {0}; validateTree(document, 0, nodes); return document;
        } catch (ParserConfigurationException | SAXException e) { throw new IOException("invalid_xml", e); }
    }
    private static void feature(DocumentBuilderFactory f, String name, boolean value) {
        // Android XML providers differ. Decoded DTD rejection and the resolver remain mandatory independently.
        try { f.setFeature(name, value); } catch (ParserConfigurationException | AbstractMethodError ignored) { }
    }
    private static void validateTree(Node node, int depth, int[] count) throws IOException {
        if (depth > 96 || ++count[0] > 60000 || node.getNodeType() == Node.DOCUMENT_TYPE_NODE || node.getNodeType() == Node.ENTITY_REFERENCE_NODE) throw new IOException("xml_complexity");
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) validateTree(child, depth + 1, count);
    }
    private static String xmlText(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > XML) throw new IOException("xml_size");
        try {
            Charset encoding = StandardCharsets.UTF_8; int offset = 0;
            if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) { encoding = StandardCharsets.UTF_16LE; offset = 2; }
            else if (bytes.length >= 2 && bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff) { encoding = StandardCharsets.UTF_16BE; offset = 2; }
            else if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == '<') encoding = StandardCharsets.UTF_16BE;
            else if (bytes.length >= 4 && bytes[0] == '<' && bytes[1] == 0) encoding = StandardCharsets.UTF_16LE;
            String value = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            return value.startsWith("\ufeff") ? value.substring(1) : value;
        } catch (CharacterCodingException e) { throw new IOException("xml_encoding", e); }
    }
    private static String decode(byte[] bytes) {
        if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return value.startsWith("\ufeff") ? value.substring(1) : value;
        } catch (CharacterCodingException e) { return new String(bytes, Charset.forName("windows-1251")); }
    }
    private static String legacy(byte[] bytes) throws IOException {
        Object extractor = null;
        try {
            Class.forName("org.apache.poi.util.IOUtils").getMethod("setByteArrayMaxOverride", int.class).invoke(null, INPUT);
            try { extractor = Class.forName("org.apache.poi.hwpf.extractor.WordExtractor").getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(bytes)); }
            catch (InvocationTargetException old) { extractor = Class.forName("org.apache.poi.hwpf.extractor.Word6Extractor").getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(bytes)); }
            return (String)extractor.getClass().getMethod("getText").invoke(extractor);
        } catch (ClassNotFoundException e) { throw new UnsupportedOperationException("doc_decoder_unavailable"); }
        catch (ReflectiveOperationException | ClassCastException e) { throw new IOException("invalid_doc", e); }
        finally { if (extractor instanceof Closeable) ((Closeable)extractor).close(); }
    }

    private static final class Format {
        final LinkedHashMap<String, String> css = new LinkedHashMap<>(); String parent = ""; int heading;
        Format copy() { Format f = new Format(); f.css.putAll(css); f.parent = parent; f.heading = heading; return f; }
        void merge(Format f) { css.putAll(f.css); if (f.heading != 0) heading = f.heading; }
        String css() { StringBuilder b = new StringBuilder(); for (Map.Entry<String,String> e : css.entrySet()) b.append(e.getKey()).append(':').append(e.getValue()).append(';'); return b.toString(); }
    }
    private static final class Engine {
        final Package pack; final Out out; final boolean word;
        final Map<String, Format> styles = new HashMap<>(); final Map<String, String> images = new HashMap<>();
        Format defaults = new Format(); int embedded; boolean simplified;
        Engine(Package pack, Out out, boolean word) { this.pack = pack; this.out = out; this.word = word; }
        void render() throws IOException {
            if (word) {
                if (pack.files.containsKey("word/styles.xml")) wordStyles(xml(pack.files.get("word/styles.xml")));
                if (pack.files.containsKey("word/_rels/document.xml.rels")) relationships(xml(pack.files.get("word/_rels/document.xml.rels")));
                Document doc = xml(pack.files.get("word/document.xml"));
                Element body = first(doc.getDocumentElement(), true, "body");
                if (body == null) throw new IOException("word_body");
                wordBlocks(body);
            } else {
                if (pack.files.containsKey("styles.xml")) odtStyles(xml(pack.files.get("styles.xml")));
                Document doc = xml(pack.files.get("content.xml")); odtStyles(doc);
                Element body = descendant(doc.getDocumentElement(), OFFICE, "text");
                if (body == null) throw new IOException("odt_body");
                odtBlocks(body);
            }
            if (simplified) out.notice("Некоторые элементы документа показаны упрощённо.");
        }
        void wordStyles(Document doc) {
            Element def = first(doc.getDocumentElement(), true, "docDefaults");
            if (def != null) {
                Element rd = first(def, true, "rPrDefault"), pd = first(def, true, "pPrDefault");
                if (rd != null) defaults.merge(wordFormat(first(rd, true, "rPr")));
                if (pd != null) defaults.merge(wordFormat(first(pd, true, "pPr")));
            }
            for (Element e : children(doc.getDocumentElement())) if (isWord(e, "style") && styles.size() < 512) {
                Format f = wordFormat(first(e, true, "pPr")); f.merge(wordFormat(first(e, true, "rPr")));
                f.parent = val(first(e, true, "basedOn"));
                String name = val(first(e, true, "name")).toLowerCase(Locale.ROOT).replace(" ", "");
                String id = wordAttr(e, "styleId");
                if (name.matches("heading[1-6]") || id.matches("Heading[1-6]")) f.heading = Character.digit((name.matches("heading[1-6]") ? name : id).charAt(7), 10);
                styles.put(id, f);
            }
        }
        Format resolve(String name) {
            Format result = defaults.copy(); result.merge(resolveOverlay(name)); return result;
        }
        Format resolveOverlay(String name) {
            LinkedList<Format> chain = new LinkedList<>(); Set<String> visited = new HashSet<>();
            while (!name.isEmpty() && visited.add(name) && chain.size() < 16) { Format f = styles.get(name); if (f == null) break; chain.addFirst(f); name = f.parent; }
            Format result = new Format(); for (Format f : chain) result.merge(f); return result;
        }
        void relationships(Document doc) {
            for (Element e : children(doc.getDocumentElement())) {
                if (!"Relationship".equals(e.getLocalName()) || !("http://schemas.openxmlformats.org/package/2006/relationships".equals(e.getNamespaceURI()))) continue;
                if ("External".equalsIgnoreCase(e.getAttribute("TargetMode"))) continue;
                String target = e.getAttribute("Target");
                try {
                    URI uri = URI.create("word/").resolve(target).normalize(); String path = uri.getPath();
                    if (!uri.isAbsolute() && uri.getAuthority() == null && uri.getQuery() == null && uri.getFragment() == null && safePath(path) && path.startsWith("word/media/")) images.put(e.getAttribute("Id"), path);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        void wordBlocks(Element parent) {
            for (Element e : children(parent)) {
                if (isWord(e, "p")) {
                    Element prop = first(e, true, "pPr"); Format f = resolve(val(prop == null ? null : first(prop, true, "pStyle"))); f.merge(wordFormat(prop));
                    if (prop != null && first(prop, true, "numPr") != null) { f.css.put("padding-left", "1.5em"); simplified = true; }
                    out.open(f.heading > 0 ? "h" + f.heading : "p", style(f.css()));
                    wordInline(e, f); out.close();
                } else if (isWord(e, "tbl")) {
                    out.open("table", "");
                    for (Element row : children(e)) if (isWord(row, "tr")) {
                        out.open("tr", "");
                        for (Element cell : children(row)) if (isWord(cell, "tc")) {
                            Element pr = first(cell, true, "tcPr");
                            int span = number(val(pr == null ? null : first(pr, true, "gridSpan")), 1, 32, 1);
                            if (pr != null && first(pr, true, "vMerge") != null) simplified = true;
                            out.open("td", " colspan=\"" + span + "\"" + style(wordFormat(pr).css())); wordBlocks(cell); out.close();
                        }
                        out.close();
                    }
                    out.close();
                } else if (isWord(e, "sdt")) {
                    Element content = first(e, true, "sdtContent"); if (content != null) wordBlocks(content);
                } else if (!isWord(e, "sectPr")) simplified = true;
            }
        }
        void wordInline(Element parent, Format paragraph) {
            for (Element e : children(parent)) {
                if (isWord(e, "r")) {
                    Element pr = first(e, true, "rPr"); Format f = paragraph.copy();
                    String named = val(pr == null ? null : first(pr, true, "rStyle"));
                    if (!named.isEmpty()) f.merge(resolveOverlay(named)); f.merge(wordFormat(pr));
                    out.open("span", style(f.css())); wordInline(e, f); out.close();
                } else if (isWord(e, "t")) out.text(e.getTextContent());
                else if (isWord(e, "tab")) out.text("\u2003");
                else if (isWord(e, "br") || isWord(e, "cr")) out.add("<br>");
                else if (isWord(e, "noBreakHyphen")) out.text("\u2011");
                else if (isWord(e, "hyperlink") || isWord(e, "ins") || isWord(e, "smartTag") || isWord(e, "sdtContent")) wordInline(e, paragraph);
                else if (isWord(e, "drawing") || isWord(e, "pict")) drawing(e);
                else if (isWord(e, "sdt")) { Element c = first(e, true, "sdtContent"); if (c != null) wordInline(c, paragraph); }
                // Field instructions, deleted text and document-supplied links are never emitted.
            }
        }
        void drawing(Element e) {
            if ((A.equals(e.getNamespaceURI()) || AS.equals(e.getNamespaceURI())) && "blip".equals(e.getLocalName())) {
                String id = e.getAttributeNS(R, "embed"); if (id.isEmpty()) id = e.getAttributeNS(RS, "embed"); image(images.get(id)); return;
            }
            if ("urn:schemas-microsoft-com:vml".equals(e.getNamespaceURI()) && "imagedata".equals(e.getLocalName())) { image(images.get(e.getAttributeNS(R, "id"))); return; }
            for (Element child : children(e)) drawing(child);
        }
        void odtStyles(Document doc) {
            scanOdtStyles(doc.getDocumentElement());
        }
        void scanOdtStyles(Element e) {
            if (STYLE.equals(e.getNamespaceURI()) && ("style".equals(e.getLocalName()) || "default-style".equals(e.getLocalName()))) {
                Format f = new Format(); f.parent = e.getAttributeNS(STYLE, "parent-style-name");
                for (Element p : children(e)) if (STYLE.equals(p.getNamespaceURI())) {
                    put(f, "font-weight", p.getAttributeNS(FO, "font-weight"), "bold", "normal");
                    put(f, "font-style", p.getAttributeNS(FO, "font-style"), "italic", "normal");
                    put(f, "text-align", p.getAttributeNS(FO, "text-align"), "left", "right", "center", "justify", "start", "end");
                    String underline = p.getAttributeNS(STYLE, "text-underline-style"); if (!underline.isEmpty()) f.css.put("text-decoration", underline.equals("none") ? "none" : "underline");
                    color(f, "color", p.getAttributeNS(FO, "color")); color(f, "background-color", p.getAttributeNS(FO, "background-color"));
                    String font = p.getAttributeNS(FO, "font-size"); if (font.matches("[0-9]{1,3}(\\.[0-9]{1,2})?pt")) f.css.put("font-size", decimal(font.substring(0, font.length() - 2), 7, 72, 12) + "pt");
                    font(f, p.getAttributeNS(STYLE, "font-name"));
                }
                if ("default-style".equals(e.getLocalName()) && "paragraph".equals(e.getAttributeNS(STYLE, "family"))) defaults.merge(f);
                else if (styles.size() < 512) styles.put(e.getAttributeNS(STYLE, "name"), f);
            }
            for (Element child : children(e)) scanOdtStyles(child);
        }
        void odtBlocks(Element parent) {
            for (Element e : children(parent)) {
                String ns = e.getNamespaceURI(), name = e.getLocalName();
                if (TEXT.equals(ns) && (name.equals("p") || name.equals("h"))) {
                    Format f = resolve(e.getAttributeNS(TEXT, "style-name"));
                    out.open(name.equals("h") ? "h" + number(e.getAttributeNS(TEXT, "outline-level"), 1, 6, 1) : "p", style(f.css())); odtInline(e); out.close();
                } else if (TABLE.equals(ns) && name.equals("table")) {
                    out.open("table", ""); odtRows(e); out.close();
                } else if (TEXT.equals(ns) && name.equals("list")) {
                    out.open("ul", ""); for (Element item : children(e)) if (TEXT.equals(item.getNamespaceURI()) && item.getLocalName().equals("list-item")) { out.open("li", ""); odtBlocks(item); out.close(); } out.close();
                    simplified = true;
                } else if (TEXT.equals(ns) && name.equals("section")) odtBlocks(e);
                else if (DRAW.equals(ns) && name.equals("frame")) odtInline(e);
            }
        }
        void odtRows(Element parent) {
            for (Element row : children(parent)) if (TABLE.equals(row.getNamespaceURI())) {
                String name = row.getLocalName();
                if (name.equals("table-header-rows") || name.equals("table-rows")) odtRows(row);
                else if (name.equals("table-row")) {
                    int repeat = number(row.getAttributeNS(TABLE, "number-rows-repeated"), 1, 128, 1);
                    for (int r = 0; r < repeat; r++) {
                        out.open("tr", "");
                        for (Element cell : children(row)) if (TABLE.equals(cell.getNamespaceURI()) && cell.getLocalName().equals("table-cell")) {
                            int copies = number(cell.getAttributeNS(TABLE, "number-columns-repeated"), 1, 32, 1);
                            for (int c = 0; c < copies; c++) {
                                int cols = number(cell.getAttributeNS(TABLE, "number-columns-spanned"), 1, 32, 1), rows = number(cell.getAttributeNS(TABLE, "number-rows-spanned"), 1, 32, 1);
                                out.open("td", " colspan=\"" + cols + "\" rowspan=\"" + rows + "\"" + style(resolve(cell.getAttributeNS(TABLE, "style-name")).css())); odtBlocks(cell); out.close();
                            }
                        }
                        out.close();
                    }
                }
            }
        }
        void odtInline(Element parent) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) { out.text(node.getNodeValue()); continue; }
                if (!(node instanceof Element)) continue; Element e = (Element)node;
                String ns = e.getNamespaceURI(), name = e.getLocalName();
                if (TEXT.equals(ns) && name.equals("span")) { out.open("span", style(resolveOverlay(e.getAttributeNS(TEXT, "style-name")).css())); odtInline(e); out.close(); }
                else if (TEXT.equals(ns) && name.equals("s")) { int count = number(e.getAttributeNS(TEXT, "c"), 1, 256, 1); for (int i = 0; i < count; i++) out.text("\u00a0"); }
                else if (TEXT.equals(ns) && name.equals("tab")) out.text("\u2003");
                else if (TEXT.equals(ns) && name.equals("line-break")) out.add("<br>");
                else if (TEXT.equals(ns) && name.equals("a")) odtInline(e);
                else if (DRAW.equals(ns) && name.equals("frame")) odtInline(e);
                else if (DRAW.equals(ns) && name.equals("image")) { String path = e.getAttributeNS(XLINK, "href"); image(safePath(path) && path.startsWith("Pictures/") ? path : null); }
            }
        }
        void image(String path) {
            byte[] bytes = pack.files.get(path); String mime = mime(bytes);
            int encoded = bytes == null ? 0 : ((bytes.length + 2) / 3) * 4;
            if (mime == null || encoded > IMAGE || embedded + encoded > IMAGES) { simplified = true; out.add("<span class=\"omitted\">[Изображение не показано]</span>"); return; }
            embedded += encoded;
            out.add("<img alt=\"Изображение документа\" src=\"data:" + mime + ";base64," + base64(bytes) + "\">");
        }
    }

    private static Format wordFormat(Element properties) {
        Format f = new Format(); if (properties == null) return f;
        for (Element e : children(properties)) if (W.equals(e.getNamespaceURI()) || WS.equals(e.getNamespaceURI())) {
            String name = e.getLocalName(), value = val(e);
            if (name.equals("b")) f.css.put("font-weight", off(value) ? "normal" : "bold");
            else if (name.equals("i")) f.css.put("font-style", off(value) ? "normal" : "italic");
            else if (name.equals("u")) f.css.put("text-decoration", value.equals("none") || off(value) ? "none" : "underline");
            else if (name.equals("strike")) f.css.put("text-decoration", off(value) ? "none" : "line-through");
            else if (name.equals("jc")) put(f, "text-align", value.equals("both") ? "justify" : value, "left", "right", "center", "justify", "start", "end");
            else if (name.equals("color")) color(f, "color", "#" + value);
            else if (name.equals("shd")) color(f, "background-color", "#" + wordAttr(e, "fill"));
            else if (name.equals("sz") && value.matches("[0-9]{1,3}")) f.css.put("font-size", Math.max(7, Math.min(72, Integer.parseInt(value) / 2.0)) + "pt");
            else if (name.equals("rFonts")) font(f, wordAttr(e, "ascii"));
            else if (name.equals("outlineLvl")) { if (value.matches("[0-8]")) f.heading = Math.min(6, Integer.parseInt(value) + 1); else if (value.equals("9")) f.heading = -1; }
            else if (name.equals("vertAlign")) { if (value.equals("superscript")) f.css.put("vertical-align", "super"); else if (value.equals("subscript")) f.css.put("vertical-align", "sub"); }
        }
        return f;
    }
    private static void put(Format f, String key, String value, String... allowed) { for (String s : allowed) if (s.equals(value)) { f.css.put(key, value); return; } }
    private static void color(Format f, String key, String value) { if (value.matches("#[0-9a-fA-F]{6}")) f.css.put(key, value); }
    private static void font(Format f, String value) {
        String name = value.toLowerCase(Locale.ROOT).replace(" ", "").replace("'", "");
        if (Arrays.asList("timesnewroman", "cambria", "georgia", "liberationserif").contains(name)) f.css.put("font-family", "Georgia,serif");
        else if (Arrays.asList("couriernew", "consolas", "liberationmono").contains(name)) f.css.put("font-family", "monospace");
        else if (Arrays.asList("arial", "calibri", "aptos", "helvetica", "liberationsans").contains(name)) f.css.put("font-family", "Arial,sans-serif");
    }
    private static boolean off(String value) { return value.equals("0") || value.equals("false") || value.equals("off"); }
    private static String val(Element e) { return e == null ? "" : wordAttr(e, "val"); }
    private static String wordAttr(Element e, String name) { String v = e.getAttributeNS(W, name); return v.isEmpty() ? e.getAttributeNS(WS, name) : v; }
    private static boolean isWord(Element e, String name) { return name.equals(e.getLocalName()) && (W.equals(e.getNamespaceURI()) || WS.equals(e.getNamespaceURI())); }
    private static Element first(Element e, boolean word, String name) { if (e == null) return null; for (Element c : children(e)) if (word && isWord(c, name)) return c; return null; }
    private static Element descendant(Element e, String ns, String name) { if (ns.equals(e.getNamespaceURI()) && name.equals(e.getLocalName())) return e; for (Element c : children(e)) { Element found = descendant(c, ns, name); if (found != null) return found; } return null; }
    private static List<Element> children(Element e) { List<Element> list = new ArrayList<>(); for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element) list.add((Element)c); return list; }
    private static int number(String value, int min, int max, int fallback) { try { return Math.max(min, Math.min(max, Integer.parseInt(value))); } catch (NumberFormatException e) { return fallback; } }
    private static double decimal(String value, double min, double max, double fallback) { try { double n = Double.parseDouble(value); return Double.isFinite(n) ? Math.max(min, Math.min(max, n)) : fallback; } catch (NumberFormatException e) { return fallback; } }
    private static String style(String css) { return css.isEmpty() ? "" : " style=\"" + css + "\""; }

    private static String mime(byte[] b) {
        if (b == null || b.length < 24) return null;
        if ((b[0] & 255) == 137 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 13 && b[5] == 10 && b[6] == 26 && b[7] == 10) {
            if (u32(b, 8) != 13 || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') return null;
            if (!dimensions(u32(b, 16), u32(b, 20))) return null;
            for (int at = 8; at + 12 <= b.length;) {
                long size = u32(b, at); if (size > b.length - at - 12) return null;
                if (b[at + 4] == 'a' && b[at + 5] == 'c' && b[at + 6] == 'T' && b[at + 7] == 'L') return null;
                at += (int)size + 12;
            }
            return "image/png";
        }
        if ((b[0] & 255) == 255 && (b[1] & 255) == 216) {
            for (int at = 2; at + 8 < b.length;) {
                if ((b[at++] & 255) != 255) return null;
                int marker; do { if (at >= b.length) return null; marker = b[at++] & 255; } while (marker == 255);
                if (marker == 217 || marker == 218) return null;
                if (marker == 1 || (marker >= 208 && marker <= 215)) continue;
                if (at + 2 > b.length) return null;
                int size = ((b[at] & 255) << 8) | (b[at + 1] & 255);
                if (size < 2 || size > b.length - at) return null;
                if ((marker >= 192 && marker <= 195) || (marker >= 197 && marker <= 199) || (marker >= 201 && marker <= 203) || (marker >= 205 && marker <= 207)) {
                    if (size < 8) return null;
                    return dimensions(((b[at + 5] & 255) << 8) | (b[at + 6] & 255), ((b[at + 3] & 255) << 8) | (b[at + 4] & 255)) ? "image/jpeg" : null;
                }
                at += size;
            }
        }
        return null;
    }
    private static long u32(byte[] b, int at) { return ((long)(b[at] & 255) << 24) | ((long)(b[at + 1] & 255) << 16) | ((long)(b[at + 2] & 255) << 8) | (b[at + 3] & 255); }
    private static boolean dimensions(long w, long h) { return w > 0 && h > 0 && w <= 4096 && h <= 4096 && w * h <= 4000000; }
    private static String base64(byte[] bytes) {
        // Avoid java.util.Base64 (Android API26) and Android-only APIs so this code is fixture-testable on a JVM.
        char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray(); StringBuilder s = new StringBuilder(((bytes.length + 2) / 3) * 4);
        for (int i = 0; i < bytes.length; i += 3) { int n = (bytes[i] & 255) << 16; if (i + 1 < bytes.length) n |= (bytes[i + 1] & 255) << 8; if (i + 2 < bytes.length) n |= bytes[i + 2] & 255; s.append(alphabet[(n >>> 18) & 63]).append(alphabet[(n >>> 12) & 63]).append(i + 1 < bytes.length ? alphabet[(n >>> 6) & 63] : '=').append(i + 2 < bytes.length ? alphabet[n & 63] : '='); }
        return s.toString();
    }
    private static final class Limit extends RuntimeException { }
    private static final class Out {
        final StringBuilder body = new StringBuilder(); final Deque<String> tags = new ArrayDeque<>(); boolean truncated;
        void add(String s) { if (body.length() + s.length() > OUTPUT - 6000) throw new Limit(); body.append(s); }
        void open(String tag, String attributes) { add("<" + tag + attributes + ">"); tags.push(tag); }
        void close() { String tag = tags.peek(); add("</" + tag + ">"); tags.pop(); }
        void text(String value) {
            StringBuilder chunk = new StringBuilder(2048);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '&') chunk.append("&amp;"); else if (c == '<') chunk.append("&lt;"); else if (c == '>') chunk.append("&gt;"); else if (c == '"') chunk.append("&quot;"); else if (c == '\'') chunk.append("&#39;"); else if (c >= 32 || c == '\n' || c == '\r' || c == '\t') chunk.append(c);
                if (chunk.length() >= 1024) { add(chunk.toString()); chunk.setLength(0); }
            }
            add(chunk.toString());
        }
        void notice(String message) { open("aside", " class=\"notice\""); text(message); close(); }
        String finish() {
            if (body.length() > 0 && Character.isHighSurrogate(body.charAt(body.length() - 1))) body.setCharAt(body.length() - 1, '\ufffd');
            while (!tags.isEmpty()) body.append("</").append(tags.pop()).append('>');
            if (truncated) body.append("<aside class=\"notice\">Документ показан не полностью: достигнут предел просмотра.</aside>");
            String prefix = "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=794\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src 'none'; base-uri 'none'; form-action 'none'\"><title>Документ</title><style>html{background:#e8eaed;color:#202124}body{box-sizing:border-box;width:794px;min-height:1123px;margin:16px auto;padding:48px;background:#fff;font:16px/1.5 Arial,sans-serif;overflow-wrap:anywhere}p{margin:0 0 .65em;white-space:pre-wrap;min-height:1.5em}h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1em 0 .5em}table{border-collapse:collapse;max-width:100%;margin:.75em 0}td,th{border:1px solid #b5bbc3;vertical-align:top;padding:6px 9px;min-width:24px}td p:last-child{margin-bottom:0}pre{white-space:pre-wrap;word-break:break-word;font:14px/1.5 monospace}img{max-width:100%;height:auto;vertical-align:middle}ul{padding-left:1.5em}.notice,.omitted{font:13px/1.45 Arial,sans-serif;color:#5f6368}.notice{padding:10px 0;border-top:1px solid #dadce0;margin:16px 0}</style></head><body>";
            String result = prefix + body + "</body></html>";
            if (result.length() > OUTPUT) throw new IllegalStateException("output_budget");
            return result;
        }
    }
}


