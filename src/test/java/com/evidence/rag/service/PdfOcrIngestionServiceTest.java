package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite authority checks. OCR recognition itself is exercised by the native HTTP test. */
class PdfOcrIngestionServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");
  private final PdfOcrOptions options =
      new PdfOcrOptions(new ImageOcrOptions(Path.of("/synthetic/tesseract"), "eng", "5.5.3"));

  @Test
  void admittedPdfPinsProfileAndCommitsAllPagesWithoutPublishingAnIndex() throws Exception {
    byte[] original = pdf();
    String documentId;
    String revisionId;
    try (var context = new AuthorityTestContext(directory)) {
      var service = service(context, options);
      assertEquals("application/pdf", service.prepareUpload("synthetic.PDF"));
      var task =
          service.uploadDocument(owner, "synthetic.PDF", "application/octet-stream", original);
      var claim = service.claimIngestion(owner.workspaceId()).orElseThrow();
      documentId = claim.documentId();
      revisionId = claim.revisionId();
      assertEquals(options.parserRevision(), claim.parserRevision());
      assertEquals("application/pdf", claim.mimeType());
      assertArrayEquals(original, claim.content());
      assertEquals("queued", task.state());
      assertTrue(service.completeIngestion(claim, transcript()));
      assertEquals("parsed", service.ingestionStatus(owner, claim.jobId()).state());
      assertEquals(transcript(), service.parsedEvidence(owner, documentId));
      assertFalse(service.completeIngestion(claim, transcript()));
      var item =
          (Map<?, ?>) ((List<?>) context.listDocuments(owner, Map.of()).get("items")).getFirst();
      assertEquals("document", item.get("document_type"));
      assertNull(item.get("active_revision_id"));
      assertEquals(false, item.get("can_answer"));
      assertEquals(3, scalar("SELECT COUNT(*) FROM corpus_pages"));
      assertEquals(2, scalar("SELECT COUNT(*) FROM corpus_segments"));
    }
    try (var context = new AuthorityTestContext(directory)) {
      assertEquals(transcript(), context.ingestion().parsedEvidence(owner, documentId));
      assertEquals(options.parserRevision(), storedParserRevision(revisionId));
    }
  }

  @Test
  void disabledOrChangedProfileCannotCompleteAnAlreadyClaimedOcrPdf() throws Exception {
    try (var context = new AuthorityTestContext(directory)) {
      var service = service(context, options);
      service.uploadDocument(owner, "synthetic.pdf", "application/pdf", pdf());
      var claim = service.claimIngestion(owner.workspaceId()).orElseThrow();
      var changed =
          new PdfOcrOptions(
              new ImageOcrOptions(Path.of("/synthetic/tesseract"), "eng+chi_sim", "5.5.3"));
      for (var other : List.of(context.ingestion(), service(context, changed))) {
        assertEquals(
            "parser_output_invalid",
            assertThrows(
                    ApplicationException.class, () -> other.completeIngestion(claim, transcript()))
                .code());
        assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_pages"));
        assertEquals("processing", service.ingestionStatus(owner, claim.jobId()).state());
      }
      assertTrue(service.completeIngestion(claim, transcript()));
    }
  }

  @Test
  void enablingOcrDoesNotRelabelOrAcceptLegacyPdfResults() throws Exception {
    try (var context = new AuthorityTestContext(directory)) {
      var legacy = context.ingestion();
      legacy.uploadDocument(owner, "legacy.pdf", "application/pdf", pdf());
      var claim = legacy.claimIngestion(owner.workspaceId()).orElseThrow();
      assertEquals(TextParser.REVISION, claim.parserRevision());
      var enabled = service(context, options);
      assertEquals(
          "parser_output_invalid",
          assertThrows(
                  ApplicationException.class, () -> enabled.completeIngestion(claim, transcript()))
              .code());
      assertEquals(TextParser.REVISION, storedParserRevision(claim.revisionId()));
      assertTrue(legacy.completeIngestion(claim, transcript()));
      assertEquals(TextParser.REVISION, storedParserRevision(claim.revisionId()));
    }
  }

  @Test
  void invalidTailLocatorsChangedOriginalAndForgedClaimsNeverPartiallyPersist() throws Exception {
    try (var context = new AuthorityTestContext(directory)) {
      var service = service(context, options);
      service.uploadDocument(owner, "synthetic.pdf", "application/pdf", pdf());
      var claim = service.claimIngestion(owner.workspaceId()).orElseThrow();
      var valid = transcript();
      var segments = new ArrayList<>(valid.segments());
      segments.set(1, new TextSegment(1, 3, 0, 999, "Cedar launch code is 73921."));
      var invalid = new ParsedText(valid.pages(), segments);
      assertEquals(
          "parser_output_invalid",
          assertThrows(ApplicationException.class, () -> service.completeIngestion(claim, invalid))
              .code());
      var changed =
          copyClaim(claim, claim.parserRevision(), "%PDF changed".getBytes(StandardCharsets.UTF_8));
      assertEquals(
          "parser_output_invalid",
          assertThrows(ApplicationException.class, () -> service.completeIngestion(changed, valid))
              .code());
      var forged = copyClaim(claim, TextParser.REVISION, claim.content());
      assertFalse(service.completeIngestion(forged, valid));
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_pages"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_segments"));
      assertTrue(service.isIngestionClaimCurrent(claim));
      service.cancelIngestion(owner, claim.jobId());
      service.retryIngestion(owner, claim.jobId());
      var current = service.claimIngestion(owner.workspaceId()).orElseThrow();
      assertFalse(service.completeIngestion(claim, valid));
      assertEquals(options.parserRevision(), current.parserRevision());
      assertTrue(service.completeIngestion(current, valid));
    }
  }

  @Test
  void currentCreatorRevocationCancelsPdfCompletionAndLeavesNoPages() throws Exception {
    try (var context = new AuthorityTestContext(directory)) {
      var service = service(context, options);
      service.uploadDocument(owner, "synthetic.pdf", "application/pdf", pdf());
      var claim = service.claimIngestion(owner.workspaceId()).orElseThrow();
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement =
              connection.prepareStatement(
                  "UPDATE document_acl SET role='reader' WHERE document_id=? AND principal_id=?")) {
        statement.setString(1, claim.documentId());
        statement.setString(2, owner.principalId());
        assertEquals(1, statement.executeUpdate());
      }
      assertFalse(service.completeIngestion(claim, transcript()));
      assertEquals("cancelled", service.ingestionStatus(owner, claim.jobId()).state());
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_pages"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_segments"));
    }
  }

  @Test
  void processorRejectsOldDisabledAndChangedProfilesBeforeStartingOcr() throws Exception {
    var changed =
        new PdfOcrOptions(
            new ImageOcrOptions(Path.of("/synthetic/other-tesseract"), "eng", "5.5.3"));
    try (var context = new AuthorityTestContext(directory)) {
      var legacy = context.ingestion();
      var current = service(context, options);
      legacy.uploadDocument(owner, "old.pdf", "application/pdf", pdf());
      processAndRequireProfileFailure(current, options);
      current.uploadDocument(owner, "disabled.pdf", "application/pdf", pdf());
      processAndRequireProfileFailure(legacy, null);
      current.uploadDocument(owner, "changed.pdf", "application/pdf", pdf());
      processAndRequireProfileFailure(service(context, changed), changed);
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_pages"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_segments"));
    }
  }

  @Test
  void enabledPdfOcrLeavesTxtAndMarkdownOnTheirOriginalProcessAndRevision() throws Exception {
    try (var context = new AuthorityTestContext(directory)) {
      var service = service(context, options);
      for (String filename : List.of("synthetic.txt", "synthetic.MD")) {
        String text = "Cedar launch code is 73921.\n中文尾部😀";
        service.uploadDocument(
            owner, filename, "application/octet-stream", text.getBytes(StandardCharsets.UTF_8));
        var processor = processor(service, options);
        var claim = processor.claim().orElseThrow();
        assertEquals(TextParser.REVISION, claim.parserRevision());
        processor.process(claim);
        assertEquals("parsed", service.ingestionStatus(owner, claim.jobId()).state());
        assertEquals(
            text, service.parsedEvidence(owner, claim.documentId()).pages().getFirst().text());
      }
    }
  }

  private void processAndRequireProfileFailure(IngestionService service, PdfOcrOptions pdfs) {
    var processor = processor(service, pdfs);
    var claim = processor.claim().orElseThrow();
    processor.process(claim);
    var task = service.ingestionStatus(owner, claim.jobId());
    assertEquals("failed", task.state());
    assertEquals("parser_output_invalid", task.errorCode());
  }

  private static IngestionTaskProcessor processor(IngestionService service, PdfOcrOptions pdfs) {
    return new IngestionTaskProcessor(
        service, "org", Duration.ofSeconds(10), null, null, null, null, pdfs);
  }

  private static IngestionService service(AuthorityTestContext context, PdfOcrOptions pdfs) {
    return new IngestionService(
        context.store(),
        new IngestionRepository(context.store()),
        new ManagementRepository(context.store()),
        new DocumentPermissionPolicy(),
        null,
        null,
        null,
        null,
        false,
        pdfs);
  }

  private static ParsedText transcript() {
    String first = "Project Cedar opens on Friday.";
    String last = "Cedar launch code is 73921.";
    return new ParsedText(
        List.of(new TextPage(1, first), new TextPage(2, ""), new TextPage(3, last)),
        List.of(
            new TextSegment(0, 1, 0, first.length(), first),
            new TextSegment(1, 3, 0, last.length(), last)));
  }

  private static byte[] pdf() throws Exception {
    try (var document = new PDDocument();
        var output = new ByteArrayOutputStream()) {
      for (var text : transcript().pages()) {
        var page = new PDPage();
        document.addPage(page);
        if (!text.text().isEmpty()) {
          try (var stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 20);
            stream.newLineAtOffset(60, 700);
            stream.showText(text.text());
            stream.endText();
          }
        }
      }
      document.save(output);
      return output.toByteArray();
    }
  }

  private static IngestionClaim copyClaim(IngestionClaim claim, String revision, byte[] content) {
    return new IngestionClaim(
        claim.jobId(),
        claim.documentId(),
        claim.revisionId(),
        claim.workspaceId(),
        claim.attempt(),
        claim.token(),
        claim.filename(),
        claim.mimeType(),
        revision,
        content);
  }

  private int scalar(String sql) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getInt(1);
    }
  }

  private String storedParserRevision(String revisionId) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement =
            connection.prepareStatement(
                "SELECT parser_revision FROM corpus_revisions WHERE id=?")) {
      statement.setString(1, revisionId);
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next());
        return rows.getString(1);
      }
    }
  }
}
