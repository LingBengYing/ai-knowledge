package com.evidence.rag.tool.parser;

import com.evidence.rag.model.domain.DocumentFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.epub.EpubParser;
import org.apache.tika.parser.html.JSoupParser;
import org.apache.tika.parser.mail.RFC822Parser;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.odf.OpenDocumentParser;
import org.apache.tika.parser.txt.TXTParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Local-only format Adapter. One extraction unit is not a rendered Office page. */
final class LocalDocumentParser {
  private static final int MAX_UTF16 = 2_000_000;

  String extract(DocumentFormat format, byte[] content) {
    try {
      if (format == DocumentFormat.XML) return xml(content);
      Parser parser =
          switch (format) {
            case HTML, HTM -> new JSoupParser();
            case DOC, XLS, PPT, MSG -> new OfficeParser();
            case DOCX, XLSX, PPTX -> new OOXMLParser();
            case ODT -> new OpenDocumentParser();
            case EPUB -> new EpubParser();
            case EML -> new RFC822Parser();
            default -> throw new TextParser.Failure("unsupported_document");
          };
      var context = new ParseContext();
      // Mail bodies may be inline HTML or text; never dispatch arbitrary embedded parsers.
      context.set(
          Parser.class,
          new CompositeParser(
              MediaTypeRegistry.getDefaultRegistry(), new JSoupParser(), new TXTParser()));
      context.set(EmbeddedDocumentExtractor.class, new NoAttachments());
      var office = new OfficeParserConfig();
      office.setExtractMacros(false);
      context.set(OfficeParserConfig.class, office);
      var metadata = new Metadata();
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "source." + format.extension());
      metadata.set(Metadata.CONTENT_TYPE, format.mediaType());
      var handler = new BodyContentHandler(MAX_UTF16);
      try (var stream = new ByteArrayInputStream(content)) {
        parser.parse(stream, handler, metadata, context);
      }
      String detected = metadata.get(Metadata.CONTENT_TYPE);
      if (detected != null
          && !format.mediaType().equals(MediaType.parse(detected).getBaseType().toString())) {
        throw new TextParser.Failure("unsupported_document");
      }
      return handler.toString();
    } catch (Exception failure) {
      // Keep document/library details inside the parser process, never task errors or logs.
      throw new TextParser.Failure("unsupported_document");
    }
  }

  private static String xml(byte[] content) throws Exception {
    var factory = SAXParserFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setXIncludeAware(false);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    var reader = factory.newSAXParser().getXMLReader();
    reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    reader.setEntityResolver(
        (publicId, systemId) -> {
          throw new SAXException("External entities disabled");
        });
    var text = new StringBuilder();
    var handler =
        new DefaultHandler() {
          @Override
          public void startElement(String uri, String localName, String name, Attributes attributes)
              throws SAXException {
            append("<" + name);
            for (int i = 0; i < attributes.getLength(); i++) {
              append(" " + attributes.getQName(i) + "=\"" + attributes.getValue(i) + "\"");
            }
            append(">");
          }

          private void append(String value) throws SAXException {
            if (value.length() > MAX_UTF16 - text.length())
              throw new SAXException("Text limit exceeded");
            text.append(value);
          }

          @Override
          public void characters(char[] ch, int start, int length) throws SAXException {
            if (length > MAX_UTF16 - text.length()) throw new SAXException("Text limit exceeded");
            text.append(ch, start, length);
          }

          @Override
          public void endElement(String uri, String localName, String name) throws SAXException {
            append("</" + name + ">\n");
          }
        };
    reader.setContentHandler(handler);
    reader.setErrorHandler(handler);
    try (var stream = new ByteArrayInputStream(content)) {
      reader.parse(new InputSource(stream));
    }
    return text.toString();
  }

  private static final class NoAttachments implements EmbeddedDocumentExtractor {
    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
      return false;
    }

    @Override
    public void parseEmbedded(
        InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml)
        throws SAXException, IOException {
      // Attachments are not the original document's body and are deliberately not traversed.
    }
  }
}
