package com.evidence.rag.model.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Supported original document formats and their canonical download media types. */
public enum DocumentFormat {
  PDF("pdf", "application/pdf"),
  TEXT("txt", "text/plain"),
  MARKDOWN("md", "text/markdown"),
  MARKDOWN_LONG("markdown", "text/markdown"),
  MDX("mdx", "text/markdown"),
  PROPERTIES("properties", "text/x-java-properties"),
  HTML("html", "text/html"),
  HTM("htm", "text/html"),
  XML("xml", "application/xml"),
  VTT("vtt", "text/vtt"),
  CSV("csv", "text/csv"),
  MSG("msg", "application/vnd.ms-outlook"),
  EML("eml", "message/rfc822"),
  DOC("doc", "application/msword"),
  DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
  PPT("ppt", "application/vnd.ms-powerpoint"),
  PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
  XLS("xls", "application/vnd.ms-excel"),
  XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
  ODT("odt", "application/vnd.oasis.opendocument.text"),
  EPUB("epub", "application/epub+zip");

  private final String extension;
  private final String mediaType;

  DocumentFormat(String extension, String mediaType) {
    this.extension = extension;
    this.mediaType = mediaType;
  }

  public String extension() {
    return extension;
  }

  public String mediaType() {
    return mediaType;
  }

  public boolean legacy() {
    return this == PDF || this == TEXT || this == MARKDOWN;
  }

  public boolean plainText() {
    return Set.of(TEXT, MARKDOWN, MARKDOWN_LONG, MDX, PROPERTIES, VTT, CSV).contains(this);
  }

  public boolean ole() {
    return Set.of(DOC, XLS, PPT, MSG).contains(this);
  }

  public boolean zip() {
    return Set.of(DOCX, XLSX, PPTX, ODT, EPUB).contains(this);
  }

  public boolean accepts(String mime) {
    if (mediaType.equals(mime) || "application/octet-stream".equals(mime)) return true;
    if ((this == TEXT || this == MARKDOWN) && Set.of("text/plain", "text/markdown").contains(mime))
      return true;
    return this == XML && "text/xml".equals(mime);
  }

  public static Optional<DocumentFormat> fromFilename(String filename) {
    if (filename == null) return Optional.empty();
    String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    return Arrays.stream(values()).filter(format -> format.extension.equals(extension)).findFirst();
  }

  public static Set<String> mediaTypes() {
    return Arrays.stream(values())
        .map(DocumentFormat::mediaType)
        .collect(Collectors.toUnmodifiableSet());
  }
}
