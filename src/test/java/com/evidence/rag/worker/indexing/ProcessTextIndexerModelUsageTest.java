package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.domain.ModelRequestUsage;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** No child process means no model request: usage is a known zero, not uncertain. */
class ProcessTextIndexerModelUsageTest {
  @Test
  void usageIsKnownZeroWhenNoChildProcessWasStarted() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(5))) {
      assertEquals(new ModelRequestUsage(0, 0, true), indexer.modelUsage());
      Thread.currentThread().interrupt();
      try {
        var failure =
            assertThrows(ProcessTextIndexer.Failure.class, () -> indexer.index(server.claim(1)));
        assertEquals("worker_interrupted", failure.code());
      } finally {
        Thread.interrupted();
      }
      assertEquals(new ModelRequestUsage(0, 0, true), indexer.modelUsage());
    }
  }
}
