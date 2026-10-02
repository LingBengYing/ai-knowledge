package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisualIngestionServiceTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final VisualIngestionOptions VISUAL = new VisualIngestionOptions("vision-v1");
  @TempDir Path directory;

  @Test
  void visualOnlyProfileAdmitsImageAndFreezesPreparationIdentityWithoutOcr() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store);
      assertEquals("image/png", assertDoesNotThrow(() -> service.prepareUpload("plain.png")));
      var task = service.uploadDocument(OWNER, "plain.png", "image/png", image());
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();

      assertEquals(task.documentId(), claim.documentId());
      assertEquals("java-image-visual-v1:vision-v1", claim.parserRevision());
      assertEquals("image/png", claim.mimeType());
    }
  }

  @Test
  void completeVisualClaimPreservesFullDescriptionWithoutAnyTextPagesOrSegments() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      var ingestion = new IngestionRepository(store);
      var management = new ManagementRepository(store);
      var service = service(store);
      byte[] original = image();
      String document = UUID.randomUUID().toString();
      String revision = UUID.randomUUID().toString();
      String job = UUID.randomUUID().toString();
      String now = Instant.now().toString();
      store.transaction(
          () -> {
            management.insertDocument(
                OWNER,
                new SyntheticDocument(
                    document,
                    "plain.png",
                    "image",
                    "image/png",
                    revision,
                    ModelValues.sha256(original),
                    original.length),
                now);
            management.insertGrant(document, OWNER.principalId(), "owner");
            ingestion.insertOriginal(
                document,
                revision,
                VISUAL.parserRevision(),
                ModelValues.sha256(original),
                original,
                now);
            ingestion.insertJob(job, document, revision, OWNER.principalId(), now);
            return null;
          });
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
      String fullDescription = "图".repeat(4096);

      assertTrue(
          service.completeVisualIngestion(claim, new ImageRecall(fullDescription, "vision-v1")));

      assertEquals("parsed", service.ingestionStatus(OWNER, job).state());
      var text = service.parsedEvidence(OWNER, document);
      assertTrue(text.pages().isEmpty());
      assertTrue(text.segments().isEmpty());
    }
  }

  private static IngestionService service(SqliteAuthorityStore store) {
    return new IngestionService(
        store,
        new IngestionRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        null,
        VISUAL);
  }

  private static byte[] image() throws Exception {
    var output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB), "png", output));
    return output.toByteArray();
  }
}
