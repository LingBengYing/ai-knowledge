package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Only the isolated PDF worker owns termination and its one sequential native OCR child. */
final class PdfWorkerLifetime implements AutoCloseable {
  private final long parentPid;
  private final Instant parentStarted;
  private final long deadline;
  private final ProcessImageParser ocr;
  private final Thread caller = Thread.currentThread();
  private final Object lock = new Object();
  private volatile boolean stopped;
  private Thread watchdog;
  private Thread hook;

  PdfWorkerLifetime(String[] args) {
    if (args.length != 8 || !args[0].equals("--pdf-ocr")) {
      throw invalid();
    }
    var options = new PdfOcrOptions(new ImageOcrOptions(Path.of(args[1]), args[2], args[3]));
    parentPid = Long.parseLong(args[4]);
    parentStarted = Instant.ofEpochSecond(Long.parseLong(args[5]), Integer.parseInt(args[6]));
    long timeout = Long.parseLong(args[7]);
    if (parentPid <= 0
        || timeout < 10
        || timeout > 60000
        || ProcessHandle.current()
            .parent()
            .map(ProcessHandle::pid)
            .filter(pid -> pid == parentPid)
            .isEmpty()
        || !parentAlive()) {
      throw invalid();
    }
    deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
    ocr = new ProcessImageParser(options.ocr(), Duration.ofMillis(timeout));
    hook = Thread.ofPlatform().name("pdf-ocr-shutdown").unstarted(this::stop);
    Runtime.getRuntime().addShutdownHook(hook);
    watchdog = Thread.ofPlatform().name("pdf-ocr-parent-lifetime").daemon(true).start(this::watch);
  }

  ImageOcr ocr() {
    return ocr;
  }

  private boolean parentAlive() {
    return ProcessHandle.of(parentPid)
        .filter(ProcessHandle::isAlive)
        .flatMap(process -> process.info().startInstant())
        .filter(parentStarted::equals)
        .isPresent();
  }

  private void watch() {
    try {
      while (!stopped) {
        if (!parentAlive() || System.nanoTime() >= deadline) {
          try {
            stop();
          } finally {
            Runtime.getRuntime().halt(70);
          }
        }
        Thread.sleep(25);
      }
    } catch (InterruptedException ended) {
      Thread.currentThread().interrupt();
    }
  }

  private void stop() {
    synchronized (lock) {
      if (stopped) {
        return;
      }
      stopped = true;
      caller.interrupt();
      try {
        ocr.close();
      } finally {
        // Never halt an isolated JVM before its native descendants have been stopped.
        var children = ProcessHandle.current().descendants().limit(1024).toList();
        children.forEach(ProcessHandle::destroyForcibly);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (children.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < until) {
          try {
            Thread.sleep(10);
          } catch (InterruptedException ignored) {
            // Confirm exit.
          }
        }
        if (children.stream().anyMatch(ProcessHandle::isAlive)) {
          throw invalid();
        }
      }
    }
  }

  @Override
  public void close() {
    boolean interrupted = Thread.interrupted();
    try {
      stop();
      if (watchdog != null) {
        watchdog.interrupt();
      }
      if (hook != null) {
        try {
          Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) {
          // The hook still owns native cleanup.
        }
      }
    } finally {
      // A normal complete worker must not create a spurious cancelled parser response.
      Thread.interrupted();
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static TextParser.Failure invalid() {
    return new TextParser.Failure("parser_invalid_output");
  }
}
