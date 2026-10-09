package com.evidence.rag.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Real, synthetic standard-format files; no provider, business data, or native runtime needed. */
public final class DocumentFormatFixtures {
  public static final String TEXT =
      "Cedar Beacon: press the start button, then confirm the green status light. Planned budget CNY 48600.";

  private DocumentFormatFixtures() {}

  public static Map<String, byte[]> samples() throws IOException {
    var files = new LinkedHashMap<String, byte[]>();
    files.put("sample.pdf", pdf());
    files.put(
        "sample.properties",
        bytes("project=Cedar Beacon\nbudget=48600\ninstructions=Press the start button\n"));
    byte[] html =
        bytes(
            "<html><head><title>Cedar Beacon</title></head><body><h1>Cedar Beacon</h1><p>"
                + TEXT
                + "</p></body></html>");
    files.put("sample.html", html);
    files.put("sample.vtt", bytes("WEBVTT\n\n00:00:00.000 --> 00:00:03.000\n" + TEXT + "\n"));
    files.put(
        "sample.csv",
        bytes("project,budget,instructions\nCedar Beacon,48600,Press the start button\n"));
    files.put("sample.msg", msg());
    files.put("sample.markdown", bytes("# Cedar Beacon\n\n" + TEXT));
    files.put(
        "sample.eml",
        bytes(
            "From: fixture@example.invalid\r\nTo: reader@example.invalid\r\nSubject: Cedar Beacon\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
                + TEXT));
    files.put("sample.ppt", ppt());
    files.put("sample.docx", docx());
    try (var stream =
        DocumentFormatFixtures.class.getResourceAsStream("/document-formats/synthetic-cedar.doc")) {
      if (stream == null) throw new IOException("Missing synthetic DOC fixture");
      files.put("sample.doc", stream.readAllBytes());
    }
    files.put("sample.txt", bytes(TEXT));
    files.put("sample.pptx", pptx());
    files.put("sample.mdx", bytes("# Cedar Beacon\n\n" + TEXT + "\n<Instruction />"));
    files.put("sample.xls", xls());
    files.put("sample.odt", odt());
    files.put("sample.md", bytes("# Cedar Beacon\n\n" + TEXT));
    files.put("sample.xlsx", xlsx());
    files.put(
        "sample.xml",
        bytes(
            "<guide><title>Cedar Beacon</title><instructions>" + TEXT + "</instructions></guide>"));
    files.put("sample.epub", epub());
    files.put("sample.htm", html.clone());
    return Collections.unmodifiableMap(files);
  }

  private static byte[] pdf() throws IOException {
    try (var doc = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      var page = new PDPage();
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(20, 700);
        stream.showText(TEXT);
        stream.endText();
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static byte[] docx() throws IOException {
    try (var doc = new XWPFDocument();
        var out = new ByteArrayOutputStream()) {
      doc.createParagraph().createRun().setText(TEXT);
      var table = doc.createTable(2, 2);
      table.getRow(0).getCell(0).setText("Budget");
      table.getRow(0).getCell(1).setText("48600");
      table.getRow(1).getCell(0).setText("Status");
      table.getRow(1).getCell(1).setText("green");
      doc.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] xlsx() throws IOException {
    try (var book = new XSSFWorkbook();
        var out = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("Cedar Beacon");
      var row = sheet.createRow(0);
      row.createCell(0).setCellValue(TEXT);
      row.createCell(1).setCellValue(48600);
      book.createSheet("Instructions")
          .createRow(0)
          .createCell(0)
          .setCellValue("Confirm green status light");
      book.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] xls() throws IOException {
    try (var book = new HSSFWorkbook();
        var out = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("Cedar Beacon");
      var row = sheet.createRow(0);
      row.createCell(0).setCellValue(TEXT);
      row.createCell(1).setCellValue(48600);
      book.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] pptx() throws IOException {
    try (var slides = new XMLSlideShow();
        var out = new ByteArrayOutputStream()) {
      slides.createSlide().createTextBox().setText(TEXT);
      slides.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] ppt() throws IOException {
    try (var slides = new HSLFSlideShow();
        var out = new ByteArrayOutputStream()) {
      var box = new HSLFTextBox();
      box.setText(TEXT);
      slides.createSlide().addShape(box);
      slides.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] msg() throws IOException {
    try (var fs = new POIFSFileSystem();
        var out = new ByteArrayOutputStream()) {
      fs.getRoot()
          .createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[32]));
      Map.of(
              "0037",
              "Cedar Beacon",
              "1000",
              TEXT,
              "001A",
              "IPM.Note",
              "0C1A",
              "Synthetic Fixture",
              "0E04",
              "Reader")
          .forEach(
              (id, value) -> {
                try {
                  fs.getRoot()
                      .createDocument(
                          "__substg1.0_" + id + "001F",
                          new ByteArrayInputStream(
                              (value + "\0").getBytes(StandardCharsets.UTF_16LE)));
                } catch (IOException failed) {
                  throw new java.io.UncheckedIOException(failed);
                }
              });
      fs.writeFilesystem(out);
      return out.toByteArray();
    }
  }

  private static byte[] odt() throws IOException {
    var entries = new LinkedHashMap<String, String>();
    entries.put("mimetype", "application/vnd.oasis.opendocument.text");
    entries.put(
        "META-INF/manifest.xml",
        "<?xml version=\"1.0\"?><manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\"><manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/><manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/></manifest:manifest>");
    entries.put(
        "content.xml",
        "<?xml version=\"1.0\"?><office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" office:version=\"1.2\"><office:body><office:text><text:p>"
            + TEXT
            + "</text:p></office:text></office:body></office:document-content>");
    return zip(entries);
  }

  private static byte[] epub() throws IOException {
    var entries = new LinkedHashMap<String, String>();
    entries.put("mimetype", "application/epub+zip");
    entries.put(
        "META-INF/container.xml",
        "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
    entries.put(
        "OEBPS/content.opf",
        "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"bookid\" version=\"2.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Cedar Beacon</dc:title><dc:language>en</dc:language><dc:identifier id=\"bookid\">synthetic-cedar</dc:identifier></metadata><manifest><item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"chapter\"/></spine></package>");
    entries.put(
        "OEBPS/chapter.xhtml",
        "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Cedar Beacon</title></head><body><p>"
            + TEXT
            + "</p></body></html>");
    return zip(entries);
  }

  private static byte[] zip(Map<String, String> entries) throws IOException {
    try (var out = new ByteArrayOutputStream();
        var zip = new ZipOutputStream(out)) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(bytes(entry.getValue()));
        zip.closeEntry();
      }
      zip.finish();
      return out.toByteArray();
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
