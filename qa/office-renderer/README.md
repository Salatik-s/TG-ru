# Local Office HTML renderer

Production source: `overlay/java/org/telegram/messenger/family/FamilyOfficeRenderer.java` (relative to the familygram root).

Contract: `public static String render(byte[] data, String extension) throws IOException, UnsupportedOperationException`. Returns a complete static HTML document of at most 350,000 UTF-16 characters, including CSP, inline style and notices. Leading dots and extension case are normalized. No network/file resolution or JavaScript is used.

- DOCX: namespace-aware paragraphs/headings, inherited paragraph/run styles, bold/italic/underline/strike, bounded font sizes and font-family mappings, alignment/colors, tables with column spans, line breaks and embedded PNG/JPEG. External relationships are never loaded or turned into links. Deleted text and field instructions are omitted. Lists/vertical merged cells and complex layout are simplified; this is not a pixel-identical Word engine.
- ODT: paragraphs/headings/spans, automatic/named style inheritance, tables/spans, basic lists and embedded PNG/JPEG. Numbered/custom lists use basic list presentation with an explicit simplification notice.
- DOC: existing POI WordExtractor/Word6Extractor via reflection; plain text with an explicit simplified-display notice. No extra dependency or download.
- TXT/log/CSV/TSV/JSON/XML/Markdown: escaped plain text. HTML/HTM is deliberately displayed as escaped inert source, with a notice.
- RTF/other unsupported types: `UnsupportedOperationException`; caller can use its existing plain-text fallback. Malformed/budget-exceeding containers or XML throw `IOException`.

The caller should invoke this only in the existing isolated document service, send the returned string as `html`, and retain the service's timeout. The WebView must keep JavaScript/file/content/network access and DOM storage disabled, deny all navigation, use only the fixed document origin, and load the returned HTML once with `loadDataWithBaseURL`. This source does not modify the Activity, service, manifest, PDF path or phone.

Limits: input32MiB; all ZIP entries together expanded16MiB, including unused entries; ≤4096 entries; required XML≤2MiB each; namespace-aware streaming preflight rejects excess depth/nodes before DOM allocation. Decoded DTD/ENTITY declarations are rejected independently of provider features; both streaming and DOM resolvers reject external entities. DOM entity references are rejected. No filesystem extraction occurs. Images are PNG/JPEG only, dimensions≤4096 per side and≤4M pixels; APNG is omitted. Each data image base64≤128,000 chars, total≤200,000. Overall output truncation closes open tags and appends an explicit incomplete-document notice.

Paper width794px, neutral white page, readable default text and wrapped content; viewport does not force a zoom level. Native/WebView zoom and fit remain the caller's responsibility.

Run `./Run.ps1` for pure-JVM fixture checks. They include generated rich DOCX/ODT, safe image embedding, total image budget, inert HTML, exact `&<>` handling, UTF16, DTD/XXE rejection (including UTF16), ZIP path traversal and expansion, early XML depth/node rejection, bounded/balanced truncation, unsupported RTF routing, and the existing synthetic DOC/DOCX/ODT fixtures. All25 checks pass with the repository's existing8 POI/dependency jars. POI emits its existing missing Log4j-provider warning; conversion still succeeds, and no logging implementation is added.

Android35 javac compilation and D8 with min API24 also pass. No Android-device or WebView visual/pinch test was performed by this renderer subtask; root owns actual service integration and device QA.
