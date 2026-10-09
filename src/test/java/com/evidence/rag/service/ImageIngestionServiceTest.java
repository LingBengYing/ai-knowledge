package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageIngestionServiceTest {
  @TempDir Path directory;

  @Test
  void longerDocumentBudgetKeepsImageOcrWithinItsExistingNativeLimit() throws Exception {
    Path executable = directory.resolve("ocr-fixture");
    Files.writeString(
        executable,
        "#!/bin/sh\n/bin/cat >/dev/null\n/bin/cat <<'TSV'\n"
            + "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
            + "1\t1\t0\t0\t0\t0\t0\t0\t7\t11\t-1\t\n"
            + "5\t1\t1\t1\t1\t1\t0\t0\t7\t11\t99\tbudget\nTSV\n");
    assertTrue(executable.toFile().setExecutable(true));
    var options = new ImageOcrOptions(executable, "eng", "budget-fixture-v1");
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB), "png", bytes));
    var owner = new Actor("org", "owner");
    try (var context = new AuthorityTestContext(directory.resolve("data"))) {
      var ingestion =
          new IngestionService(
              context.store(),
              new IngestionRepository(context.store()),
              new ManagementRepository(context.store()),
              new DocumentPermissionPolicy(),
              options);
      var task =
          ingestion.uploadDocument(
              owner, "synthetic.png", "application/octet-stream", bytes.toByteArray());
      var processor =
          new IngestionTaskProcessor(ingestion, "org", Duration.ofSeconds(300), options);
      processor.process(processor.claim().orElseThrow());
      assertEquals("parsed", ingestion.ingestionStatus(owner, task.taskId()).state());
      assertEquals(
          "budget\n", ingestion.parsedEvidence(owner, task.documentId()).pages().getFirst().text());
    }
  }

  @Test
  void explicitImageAdmissionKeepsOriginalAndAcceptsOnePageMachineTranscript() throws Exception {
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB), "png", bytes));
    byte[] original = bytes.toByteArray();
    var owner = new Actor("org", "owner");
    var options = new ImageOcrOptions(Path.of("/synthetic/tesseract"), "eng", "5.5.3");
    try (var context = new AuthorityTestContext(directory)) {
      var ingestion =
          new IngestionService(
              context.store(),
              new IngestionRepository(context.store()),
              new ManagementRepository(context.store()),
              new DocumentPermissionPolicy(),
              options);
      assertEquals("image/png", ingestion.prepareUpload("synthetic.PNG"));
      var task =
          ingestion.uploadDocument(owner, "synthetic.PNG", "application/octet-stream", original);
      assertEquals("queued", task.state());
      var claim = ingestion.claimIngestion("org").orElseThrow();
      assertEquals("java-image-ocr-v2-tsv:5.5.3:eng", claim.parserRevision());
      assertEquals("image/png", claim.mimeType());
      assertArrayEquals(original, claim.content());
      var transcript =
          new TextParser()
              .parse("ocr.txt", "text/plain", "预算470万元。".getBytes(StandardCharsets.UTF_8));
      assertTrue(
          ingestion.completeImageIngestion(
              claim,
              new ParsedImage(
                  transcript,
                  new ImageDimensions(7, 11),
                  List.of(new ImageTextRegion(0, 8, 0, 0, 7, 11)))));
      assertEquals("parsed", ingestion.ingestionStatus(owner, task.taskId()).state());
      assertEquals(
          "预算470万元。", ingestion.parsedEvidence(owner, task.documentId()).pages().getFirst().text());
      var items = (List<?>) context.listDocuments(owner, Map.of()).get("items");
      var document = (Map<?, ?>) items.getFirst();
      assertEquals("image", document.get("document_type"));
      assertNull(document.get("active_revision_id"));
    }
  }
}
