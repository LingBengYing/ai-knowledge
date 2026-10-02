package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.ProjectionItem;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualIndexWorkerTest {
  @Test
  void realChildProjectsTheWholeUnicodeImageRecallWithoutTextLocators() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(15))) {
      String recall = "😀".repeat(4095) + "图";
      var item =
          new ProjectionItem(
              "image-synthetic-evidence", 0, recall, IndexingTestServer.sha256(recall));
      var claim =
          new IndexClaim(
              "image-job",
              "image-document",
              "image-revision",
              "org-main",
              1,
              "synthetic-claim-token",
              IndexingTestServer.sha256("synthetic PNG bytes"),
              "java-visual-ingestion-v1:synthetic",
              server.target(),
              List.of(item),
              "image-generation");
      var result = indexer.index(claim);
      String physical =
          RetrievalProjection.physicalSegmentId(claim.projectionGenerationId(), item.evidenceId());
      assertEquals(List.of(physical), result.entryDigests().keySet().stream().toList());
      assertEquals(1, result.verified().segmentCount());
      var embedding =
          server.requests.stream().filter(request -> request.path().equals("/embeddings")).toList();
      assertEquals(1, embedding.size());
      assertEquals(1, embedding.getFirst().body().path("input").size());
      assertEquals(recall, embedding.getFirst().body().path("input").get(0).asString());
      var writes = server.committedUpserts;
      assertEquals(1, writes.size());
      assertEquals(recall, writes.getFirst().body().path("data").get(0).path("text").asString());
      assertEquals(physical, writes.getFirst().body().path("data").get(0).path("id").asString());
      assertFalse(writes.getFirst().body().path("data").get(0).has("page"));
      assertFalse(writes.getFirst().body().path("data").get(0).has("start"));
      assertFalse(writes.getFirst().body().path("data").get(0).has("end"));
      assertEquals("ProjectionItem[redacted]", item.toString());
    }
  }
}
