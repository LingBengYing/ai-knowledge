package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.evidence.rag.worker.indexing.ProcessTextIndexer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisualIndexingServiceTest {
  @TempDir Path directory;

  @Test
  void zeroTextRevisionPublishesOnlyAfterTheWholeImageProjectionIsVerified() throws Exception {
    var owner = new Actor("org-main", "owner");
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB), "png", bytes));
    byte[] original = bytes.toByteArray();
    String description = "色".repeat(4095) + "😀";
    try (var store = new SqliteAuthorityStore(directory);
        var server = new IndexingTestServer();
        var worker =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(15))) {
      var management = new ManagementRepository(store);
      var permissions = new DocumentPermissionPolicy();
      var ingestion =
          new IngestionService(
              store,
              new IngestionRepository(store),
              management,
              permissions,
              null,
              new VisualIngestionOptions("vision-fixture-v1"));
      var indexing =
          new IndexingService(store, new IndexingRepository(store), management, permissions);
      var upload = ingestion.uploadDocument(owner, "no-text.png", "image/png", original);
      var source = ingestion.claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(
          ingestion.completeVisualIngestion(
              source, new ImageRecall(description, "vision-fixture-v1")));
      assertTrue(ingestion.parsedEvidence(owner, upload.documentId()).pages().isEmpty());
      assertTrue(ingestion.parsedEvidence(owner, upload.documentId()).segments().isEmpty());

      var queued = indexing.createIndexing(owner, upload.documentId(), server.target());
      var claim = indexing.claimIndexing(owner.workspaceId()).orElseThrow();
      assertEquals(queued.taskId(), claim.jobId());
      assertEquals(source.revisionId(), claim.revisionId());
      assertEquals(ModelValues.sha256(original), claim.sourceSha256());
      assertEquals(1, claim.items().size());
      assertEquals(0, claim.items().getFirst().ordinal());
      assertEquals(description, claim.items().getFirst().recallText());
      assertTrue(indexing.isIndexingClaimCurrent(claim));
      var result = worker.index(claim);
      var incomplete =
          assertThrows(
              ApplicationException.class,
              () -> indexing.completeIndexing(claim, Map.of(), result.verified()));
      assertEquals("indexing_output_invalid", incomplete.code());
      assertEquals("processing", indexing.indexingStatus(owner, queued.taskId()).state());
      assertTrue(indexing.completeIndexing(claim, result.entryDigests(), result.verified()));
      var indexed = indexing.indexingStatus(owner, queued.taskId());
      assertEquals("indexed", indexed.state());
      assertNotNull(indexed.indexPublicationId());
      assertTrue(ingestion.parsedEvidence(owner, upload.documentId()).pages().isEmpty());
      assertTrue(ingestion.parsedEvidence(owner, upload.documentId()).segments().isEmpty());
    }
  }
}
