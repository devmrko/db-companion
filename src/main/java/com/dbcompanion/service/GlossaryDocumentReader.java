package com.dbcompanion.service;

import com.dbcompanion.model.BusinessGlossary;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;

/** No filesystem extraction, remote resources, macros, or OCR. Reject rather than silently truncate. */
@Component
public class GlossaryDocumentReader {
    public static final int MAX_BYTES = 4_000_000, MAX_TEXT = 40_000;
    public record Section(String location, String text) {}
    public List<Section> read(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        String name = Objects.toString(filename, "").toLowerCase(Locale.ROOT);
        try {
            List<Section> sections;
            if (name.endsWith(".pdf")) sections = pdf(bytes);
            else if (name.endsWith(".docx")) sections = docx(bytes);
            else if (name.endsWith(".txt") || name.endsWith(".md")) {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                sections = List.of(new Section("text", text.replace("\uFEFF", "")));
            } else throw invalid();
            int length = sections.stream().mapToInt(s -> s.text().length()).sum();
            if (length > MAX_TEXT || sections.stream().allMatch(s -> s.text().isBlank())) throw invalid();
            if (sections.stream().anyMatch(s -> s.text().indexOf('\0') >= 0)) throw invalid();
            return sections.stream().filter(s -> !s.text().isBlank()).toList();
        } catch (IOException | javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException ex) {
            throw invalid();
        }
    }
    private List<Section> pdf(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent() || document.getNumberOfPages() > 80) throw invalid();
            var result = new ArrayList<Section>(); int total = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                var stripper = new PDFTextStripper(); stripper.setStartPage(page); stripper.setEndPage(page);
                var writer = new BoundedWriter(MAX_TEXT - total);
                stripper.writeText(document, writer);
                String text = writer.toString(); total += text.length();
                result.add(new Section("page " + page, text));
            }
            return result;
        }
    }
    private List<Section> docx(byte[] bytes) throws IOException, javax.xml.parsers.ParserConfigurationException, org.xml.sax.SAXException {
        byte[] xml = null; int total = 0, entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 300) throw invalid();
                byte[] content = zip.readNBytes(8_000_001 - total); total += content.length;
                if (total > 8_000_000) throw invalid();
                if (entry.getName().equals("word/document.xml")) {
                    if (xml != null) throw invalid(); xml = content;
                }
            }
        }
        if (xml == null) throw invalid();
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("jdk.xml.maxElementDepth", "100");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException ex) throws org.xml.sax.SAXException { throw ex; }
        });
        var document = builder.parse(new ByteArrayInputStream(xml));
        var paragraphs = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p");
        var text = new StringBuilder();
        for (int i = 0; i < paragraphs.getLength(); i++) { appendText(paragraphs.item(i), text); text.append('\n'); if (text.length() > MAX_TEXT) throw invalid(); }
        return List.of(new Section("document body (paragraphs / table cells)", text.toString()));
    }
    private void appendText(Node node, StringBuilder text) {
        if ("t".equals(node.getLocalName())) text.append(node.getTextContent());
        else if ("tab".equals(node.getLocalName())) text.append('\t');
        else if ("br".equals(node.getLocalName())) text.append('\n');
        else for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) appendText(child, text);
    }
    private static RuntimeException invalid() {
        return BusinessGlossary.failure(422, "문서를 읽을 수 없습니다. UTF-8 TXT·MD, DOCX 본문, 텍스트 PDF만 지원합니다. 파일 4 MB·추출 문자 40,000자·PDF 80쪽 한도이며 스캔·암호 문서는 지원하지 않습니다.");
    }
    private static final class BoundedWriter extends Writer {
        private final int limit; private final StringBuilder text = new StringBuilder();
        BoundedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            if (text.length() + length > limit) throw new IOException("Text limit exceeded"); text.append(chars, offset, length);
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() { return text.toString(); }
    }
}
