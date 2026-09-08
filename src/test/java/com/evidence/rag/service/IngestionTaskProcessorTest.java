package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.worker.parser.ProcessTextParser;
import com.evidence.rag.worker.parser.SlowParserFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// These tests intentionally hold an otherwise unused runtime and explicitly close it mid-test.
@SuppressWarnings("try")
class IngestionTaskProcessorTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");

  @Test
  void cancellingProcessingKillsActualChildAndRetryCannotReceiveItsLateResult() throws Exception {
    Path pidFile = directory.resolve("cancel-child.pid");
    var calls = new AtomicInteger();
    try (var authority = new AuthorityTestContext(directory.resolve("db"));
        var runtime =
            new IngestionJob(
                new IngestionTaskProcessor(
                    authority.ingestion(),
                    "org",
                    Duration.ofSeconds(8),
                    limit ->
                        calls.getAndIncrement() == 0
                            ? SlowParserFixture.parser(limit, pidFile)
                            : new ProcessTextParser(limit)))) {
      var job =
          authority.uploadDocument(
              owner, "synthetic.txt", "text/plain", "合成取消重试证据".getBytes(StandardCharsets.UTF_8));
      String taskId = (String) job.get("task_id");
      long pid = awaitPid(pidFile);
      assertEquals("processing", authority.ingestionStatus(owner, taskId).get("state"));
      assertEquals("cancelled", authority.cancelIngestion(owner, taskId).get("state"));
      await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
      assertEquals("queued", authority.retryIngestion(owner, taskId).get("state"));
      await(() -> "parsed".equals(authority.ingestionStatus(owner, taskId).get("state")));
      assertEquals(2, authority.ingestionStatus(owner, taskId).get("attempt"));
      assertEquals(
          "合成取消重试证据",
          authority
              .parsedEvidence(owner, (String) job.get("document_id"))
              .pages()
              .getFirst()
              .text());
    }
  }

  @Test
  void gracefulShutdownTerminatesProcessingChildAndPersistsRetryableFailure() throws Exception {
    Path pidFile = directory.resolve("shutdown-child.pid");
    try (var authority = new AuthorityTestContext(directory.resolve("db"))) {
      try (var runtime =
          new IngestionJob(
              new IngestionTaskProcessor(
                  authority.ingestion(),
                  "org",
                  Duration.ofSeconds(8),
                  limit -> SlowParserFixture.parser(limit, pidFile)))) {
        String taskId =
            (String)
                authority
                    .uploadDocument(owner, "synthetic.txt", "text/plain", new byte[] {65})
                    .get("task_id");
        long pid = awaitPid(pidFile);
        runtime.close();
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        var failed = authority.ingestionStatus(owner, taskId);
        assertEquals("failed", failed.get("state"));
        assertEquals("worker_interrupted", failed.get("error_code"));
        assertEquals(true, failed.get("can_retry"));
      }
    }
  }

  private static long awaitPid(Path path) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < until) {
      if (Files.exists(path)) {
        String value = Files.readString(path);
        if (value.matches("[0-9]+")) {
          return Long.parseLong(value);
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Controlled parser child did not start");
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < until) {
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean(), "Expected bounded scheduler state transition");
  }
}
