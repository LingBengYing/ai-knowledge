package com.evidence.rag.ingestion;

import com.evidence.rag.corpus.ProcessTextParser;
import com.evidence.rag.corpus.TextParser;
import com.evidence.rag.management.ManagementModule;
import com.evidence.rag.shared.Problem;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** One parser at a time. Authority transactions never encompass child process execution. */
public final class IngestionRuntime implements AutoCloseable {
  private final ManagementModule authority;
  private final String workspace;
  private final Duration deadline;
  private final Function<Duration, ProcessTextParser> parsers;
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
  private final Object lock = new Object();
  private ManagementModule.IngestionClaim current;
  private Thread executing;
  private boolean closed;

  public IngestionRuntime(ManagementModule authority, String workspace, Duration deadline) {
    this(authority, workspace, deadline, ProcessTextParser::new);
  }

  // Real child parser and controlled child executable in integration tests share this factory seam.
  IngestionRuntime(
      ManagementModule authority,
      String workspace,
      Duration deadline,
      Function<Duration, ProcessTextParser> parsers) {
    this.authority = authority;
    this.workspace = workspace;
    this.deadline = deadline;
    this.parsers = parsers;
    scheduler.scheduleWithFixedDelay(this::processNext, 100, 250, TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(this::cancelStale, 100, 100, TimeUnit.MILLISECONDS);
  }

  private void processNext() {
    ManagementModule.IngestionClaim claim = null;
    try {
      synchronized (lock) {
        if (closed) return;
        var next = authority.claimIngestion(workspace);
        if (next.isEmpty()) return;
        claim = next.orElseThrow();
        current = claim;
        executing = Thread.currentThread();
      }
      try (var parser = parsers.apply(deadline)) {
        var parsed = parser.parse(claim.filename(), claim.mimeType(), claim.content());
        authority.completeIngestion(claim, parsed);
      }
    } catch (TextParser.Failure failure) {
      fail(
          claim,
          switch (failure.code()) {
            case "unsupported_document" -> "unsupported_document";
            case "parser_timeout" -> "parser_timeout";
            case "parser_interrupted", "parser_cancelled", "parser_closed" -> "worker_interrupted";
            case "parser_invalid_output" -> "parser_output_invalid";
            default -> "parser_failed";
          });
    } catch (RuntimeException failure) {
      // Never persist/log exception messages: JDBC and parser errors can include document text.
      fail(claim, "parser_failed");
    } finally {
      synchronized (lock) {
        current = null;
        executing = null;
        Thread.interrupted(); // Only this scheduler-owned task thread; no user thread is cleared.
      }
    }
  }

  private void fail(ManagementModule.IngestionClaim claim, String code) {
    if (claim == null) return;
    try {
      authority.failIngestion(claim, code);
    } catch (Problem unavailable) {
      /* Persisted processing state recovers to failed on authority reopen. */
    }
  }

  private void cancelStale() {
    synchronized (lock) {
      if (current == null || executing == null) return;
      try {
        if (!authority.isIngestionClaimCurrent(current)) executing.interrupt();
      } catch (Problem unavailable) {
        executing.interrupt();
      }
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      closed = true;
      if (executing != null) executing.interrupt();
    }
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
