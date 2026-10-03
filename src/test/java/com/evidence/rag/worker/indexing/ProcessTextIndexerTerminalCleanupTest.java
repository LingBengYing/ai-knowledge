package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.IndexingResult;
import com.evidence.rag.model.domain.LibraryOperationGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessTextIndexerTerminalCleanupTest {
  @TempDir Path temporary;

  @Test
  void cancellationAfterRealProcessAndWriterExitCannotInterruptDurableOwnershipCleanup()
      throws Exception {
    var observer = new PausedOwnershipCleanup();
    var gate = new LibraryOperationGate(temporary.toRealPath().resolve("owned-work"));
    var callerFailure = new AtomicReference<Throwable>();
    var closeFailure = new AtomicReference<Throwable>();
    var firstResult = new AtomicReference<IndexingResult>();
    try (var server = new IndexingTestServer();
        var indexer =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(10),
                ProcessTextIndexerTest.ProcessFixture.class.getName(),
                List.of("success", "unused"),
                observer)) {
      Thread caller =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try (var operation = gate.enter()) {
                      firstResult.set(indexer.index(server.claim(1)));
                    } catch (Throwable failure) {
                      callerFailure.set(failure);
                    }
                  });
      Thread closer = null;
      try {
        assertTrue(observer.entered.await(5, TimeUnit.SECONDS));
        assertNotNull(observer.directory);
        assertFalse(Files.exists(observer.directory));
        assertTrue(Files.isRegularFile(observer.marker()));
        closer =
            Thread.ofPlatform()
                .start(
                    () -> {
                      try {
                        indexer.close();
                      } catch (Throwable failure) {
                        closeFailure.set(failure);
                      }
                    });
        assertTrue(observer.cancelled.await(2, TimeUnit.SECONDS));
      } finally {
        observer.release.countDown();
        caller.join(3_000);
        if (closer != null) {
          closer.join(3_000);
        }
      }
      assertFalse(caller.isAlive());
      assertNotNull(closer);
      assertFalse(closer.isAlive());

      // Check a real subsequent child before asserting, so RED records any poisoned admission.
      IndexingResult nextResult = null;
      Throwable nextFailure = null;
      try (var operation = gate.enter();
          var next =
              new ProcessTextIndexer(
                  server.settings().models(),
                  server.settings().projection(),
                  Duration.ofSeconds(10),
                  ProcessTextIndexerTest.ProcessFixture.class.getName(),
                  List.of("success", "unused"))) {
        nextResult = next.index(server.claim(1));
      } catch (Throwable failure) {
        nextFailure = failure;
      }
      String diagnostic =
          "ownership="
              + kind(observer.ownershipFailure.get())
              + "; interruptSent="
              + observer.interruptSent
              + "; caller="
              + kind(callerFailure.get())
              + "; close="
              + kind(closeFailure.get())
              + "; next="
              + kind(nextFailure);
      assertNull(observer.ownershipFailure.get(), diagnostic);
      assertFalse(observer.interruptSent, diagnostic);
      assertNull(closeFailure.get(), diagnostic);
      assertTrue(callerFailure.get() instanceof ProcessTextIndexer.Failure, diagnostic);
      assertEquals(
          "indexing_closed", ((ProcessTextIndexer.Failure) callerFailure.get()).code(), diagnostic);
      assertNull(firstResult.get(), diagnostic);
      assertNull(nextFailure, diagnostic);
      assertNotNull(nextResult, diagnostic);
      assertEquals(1, nextResult.entryDigests().size());
      assertFalse(Files.exists(observer.marker()));
      assertTrue(gate.isIdle());
    }
  }

  private static String kind(Throwable failure) {
    if (failure == null) {
      return "none";
    }
    if (failure instanceof ProcessTextIndexer.Failure safe) {
      return failure.getClass().getSimpleName() + ":" + safe.code();
    }
    return failure.getClass().getName();
  }

  private static final class PausedOwnershipCleanup implements ProcessTextIndexer.CleanupObserver {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch cancelled = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<IOException> ownershipFailure = new AtomicReference<>();
    volatile Path directory;
    volatile boolean interruptSent;

    @Override
    public void beforeOwnershipCleanup(Path directory) {
      this.directory = directory;
      entered.countDown();
      boolean interrupted = false;
      boolean released = false;
      while (!released) {
        try {
          released = release.await(5, TimeUnit.SECONDS);
          if (!released) {
            throw new AssertionError("Synthetic ownership cleanup was not released");
          }
        } catch (InterruptedException cancelled) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void cancelled(boolean interrupted) {
      interruptSent = interrupted;
      cancelled.countDown();
    }

    @Override
    public void ownershipFailure(IOException failure) {
      ownershipFailure.set(failure);
    }

    Path marker() {
      return directory.getParent().resolve(".owners").resolve(directory.getFileName() + ".json");
    }
  }
}
