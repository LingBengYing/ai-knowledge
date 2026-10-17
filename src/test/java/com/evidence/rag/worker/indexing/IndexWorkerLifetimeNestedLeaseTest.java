package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The collection initialization lease is taken while the document lease is held. */
class IndexWorkerLifetimeNestedLeaseTest {
  @Test
  void initializationLeaseNeverWaitsOnTheHeldDocumentLeaseStripe() throws Exception {
    try (var server = new IndexingTestServer()) {
      var settings = server.settings().projection();
      Method lease =
          IndexWorkerLifetime.class.getDeclaredMethod(
              "leaseFile", settings.getClass(), String.class);
      lease.setAccessible(true);
      int collectionStripe =
          Math.floorMod(((Path) lease.invoke(null, settings, "")).hashCode(), 64);
      String document = UUID.randomUUID().toString(), generation = null;
      while (generation == null) {
        String candidate = UUID.randomUUID().toString();
        Path file = (Path) lease.invoke(null, settings, document + "\n" + candidate);
        if (Math.floorMod(file.hashCode(), 64) == collectionStripe) generation = candidate;
      }
      var parent = IndexWorkerLifetime.Parent.current();
      try (var held =
          IndexWorkerLifetime.acquireDocument(
              settings, parent, Duration.ofSeconds(5), false, document, generation)) {
        String code;
        try (var init =
            IndexWorkerLifetime.acquire(settings, parent, Duration.ofSeconds(1), false)) {
          code = "acquired";
        } catch (ProcessTextIndexer.Failure failure) {
          code = failure.code();
        }
        assertEquals(
            "acquired", code, "a document lease on the same stripe must not block initialization");
      }
    }
  }
}
