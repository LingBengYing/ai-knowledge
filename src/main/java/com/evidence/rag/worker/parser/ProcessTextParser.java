package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.OwnedTemporaryResources;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/**
 * Resource-bounded parser subprocess Module. Never inherits application environment credentials.
 */
public final class ProcessTextParser implements AutoCloseable {
  private static final Semaphore CAPACITY = new Semaphore(1);
  private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(2);
  private final long deadlineNanos;
  private final List<String> launch;
  private final PdfOcrOptions pdfs;
  private final Object lock = new Object();
  private boolean closed;
  private Job active;

  public ProcessTextParser(Duration deadline) {
    this(deadline, null, List.of());
  }

  public ProcessTextParser(Duration deadline, PdfOcrOptions pdfs) {
    this(deadline, null, List.of(), pdfs);
  }

  // Trusted test seam: still uses the same JDK, cleared environment and fixed resource controls.
  ProcessTextParser(Duration deadline, String fixtureMainClass, List<String> fixtureArgs) {
    this(deadline, fixtureMainClass, fixtureArgs, null);
  }

  private ProcessTextParser(
      Duration deadline, String fixtureMainClass, List<String> fixtureArgs, PdfOcrOptions pdfs) {
    if (deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0
        || deadline.compareTo(Duration.ofSeconds(300)) > 0) {
      throw new IllegalArgumentException("Invalid parser deadline");
    }
    deadlineNanos = deadline.toNanos();
    launch = launch(fixtureMainClass, fixtureArgs);
    this.pdfs = pdfs;
  }

  public ParsedText parse(String filename, String mime, byte[] content) {
    long started = System.nanoTime();
    Job job;
    synchronized (lock) {
      if (closed) {
        throw failure("parser_closed");
      }
      if (Thread.currentThread().isInterrupted()) {
        throw failure("parser_interrupted");
      }
      TextParser.validateEnvelope(filename, mime, content);
      if (!CAPACITY.tryAcquire()) {
        throw failure("parser_busy");
      }
      try {
        job = new Job(filename, mime, content.clone(), started);
      } catch (RuntimeException | Error reserveFailure) {
        CAPACITY.release();
        throw reserveFailure;
      }
      active = job;
      try {
        job.thread = Thread.ofVirtual().name("text-parser-process").unstarted(job.result);
        job.thread.start();
      } catch (RuntimeException | Error startFailure) {
        job.body.close();
        active = null;
        CAPACITY.release();
        throw startFailure;
      }
    }
    try {
      long remaining = deadlineNanos - (System.nanoTime() - started);
      if (remaining <= 0) {
        throw new TimeoutException();
      }
      var parsed = job.result.get(remaining, TimeUnit.NANOSECONDS);
      job.checkCancelled();
      return parsed;
    } catch (TimeoutException ignored) {
      job.cancel("parser_timeout");
      awaitCleanup(job);
      throw failure(job.cancelled.get());
    } catch (InterruptedException ignored) {
      job.cancel("parser_interrupted");
      try {
        awaitCleanup(job);
      } finally {
        Thread.currentThread().interrupt();
      }
      throw failure(job.cancelled.get());
    } catch (ExecutionException ignored) {
      Throwable cause = ignored.getCause();
      if (cause instanceof TextParser.Failure safe) {
        throw safe;
      }
      throw failure("parser_failed");
    }
  }

  @Override
  public void close() {
    Job job;
    synchronized (lock) {
      closed = true;
      job = active;
    }
    if (job != null) {
      job.cancel("parser_closed");
      awaitCleanup(job);
    }
  }

  private static void awaitCleanup(Job job) {
    boolean interrupted = Thread.interrupted();
    long until = System.nanoTime() + job.cleanupNanos();
    try {
      while (job.finished.getCount() != 0) {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) {
          throw failure("parser_cleanup_failed");
        }
        try {
          if (!job.finished.await(remaining, TimeUnit.NANOSECONDS)) {
            throw failure("parser_cleanup_failed");
          }
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
      if (!job.cleaned) {
        throw failure("parser_cleanup_failed");
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private final class Job {
    final String filename;
    final String mime;
    final byte[] content;
    final long started;
    final boolean pdfOcr;
    final ConcurrentHashMap<Child, ProcessHandle> children = new ConcurrentHashMap<>();
    final AtomicReference<String> cancelled = new AtomicReference<>();
    final CountDownLatch finished = new CountDownLatch(1);
    final LibraryOperationGate.ReservedCall<ParsedText> body =
        LibraryOperationGate.protectCurrent((Callable<ParsedText>) this::execute);
    final FutureTask<ParsedText> result = new FutureTask<>(body);
    volatile Thread thread;
    volatile Process process;
    volatile boolean cleaned;
    boolean terminalCleanup;
    Thread writerThread;
    Thread descendantsThread;
    volatile boolean observing = true;
    volatile boolean descendantsInvalid;
    final Path managedRoot = OwnedTemporaryResources.currentRoot();
    Path directory;

    Job(String filename, String mime, byte[] content, long started) {
      this.filename = filename;
      this.mime = mime;
      this.content = content;
      this.started = started;
      pdfOcr = pdfs != null && filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
    }

    long cleanupNanos() {
      return pdfOcr ? TimeUnit.SECONDS.toNanos(5) : CLEANUP_NANOS;
    }

    void cancel(String code) {
      synchronized (lock) {
        if (!cancelled.compareAndSet(null, code)) {
          return;
        }
        if (terminalCleanup) {
          return;
        }
        Process current = process;
        if (current != null) {
          if (pdfOcr) {
            observeChildren();
            current.destroy();
          } else {
            current.destroyForcibly();
          }
        }
        thread.interrupt();
      }
    }

    void checkCancelled() {
      String code = cancelled.get();
      if (code != null) {
        throw failure(code);
      }
    }

    ParsedText execute() {
      try {
        checkCancelled();
        directory = OwnedTemporaryResources.createDirectory("rag-parser-", managedRoot);
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(
            List.of(
                "-Xmx256m",
                "-XX:MaxMetaspaceSize=96m",
                "-XX:MaxDirectMemorySize=32m",
                "-XX:ActiveProcessorCount=1",
                "-XX:+UseSerialGC",
                "-XX:+ExitOnOutOfMemoryError",
                "-XX:-CreateCoredumpOnCrash",
                "-Duser.home=" + directory,
                "-Djava.io.tmpdir=" + directory,
                "-XX:ErrorFile=" + directory.resolve("jvm-error.log")));
        command.addAll(launch);
        if (pdfOcr) {
          long remaining = deadlineNanos - (System.nanoTime() - started);
          long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
          if (millis < 10) {
            throw failure("parser_timeout");
          }
          var parent = ProcessHandle.current();
          var born = parent.info().startInstant().orElseThrow(() -> failure("parser_failed"));
          if (pdfs.pipeline()) {
            command.addAll(
                List.of(
                    "--pdf-page-ocr", pdfs.socket().toString(), PdfOcrOptions.PIPELINE_PROFILE));
          } else {
            command.addAll(
                List.of(
                    "--pdf-ocr",
                    pdfs.ocr().executable().toString(),
                    pdfs.ocr().language(),
                    pdfs.ocr().revision()));
          }
          command.addAll(
              List.of(
                  Long.toString(parent.pid()),
                  Long.toString(born.getEpochSecond()),
                  Integer.toString(born.getNano()),
                  Long.toString(millis)));
        }
        var builder =
            new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        // macOS may independently add its numeric CoreFoundation locale variable on JVM startup.
        // No application, provider, JVM-option or database environment variable is inherited here.
        checkCancelled();
        OwnedTemporaryResources.launching(directory);
        process = builder.start();
        OwnedTemporaryResources.childStarted(directory, process);
        if (pdfOcr) {
          var observerBody = LibraryOperationGate.protectCurrent((Runnable) this::watchChildren);
          try {
            descendantsThread =
                Thread.ofVirtual().name("pdf-parser-descendants").start(observerBody);
          } catch (RuntimeException | Error startFailure) {
            observerBody.close();
            throw startFailure;
          }
        }
        checkCancelled();
        var inputBody =
            LibraryOperationGate.protectCurrent(
                (Callable<Void>)
                    () -> {
                      try (var output = process.getOutputStream()) {
                        ParserProtocol.writeRequest(output, filename, mime, content);
                      }
                      return null;
                    });
        var writer = new FutureTask<Void>(inputBody);
        try {
          writerThread = Thread.ofVirtual().name("text-parser-input").start(writer);
        } catch (RuntimeException | Error startFailure) {
          inputBody.close();
          throw startFailure;
        }
        var response = new ByteArrayOutputStream();
        try (var input = process.getInputStream()) {
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) != -1) {
            checkCancelled();
            if (count > ParserProtocol.MAX_OUTPUT - response.size()) {
              throw failure("parser_invalid_output");
            }
            response.write(buffer, 0, count);
          }
        }
        writer.get();
        if (process.waitFor() != 0) {
          throw failure("parser_failed");
        }
        checkCancelled();
        try {
          return ParserProtocol.decode(response.toByteArray());
        } catch (TextParser.Failure failure) {
          if (pdfOcr && pdfs.pipeline() && "unsupported_document".equals(failure.code())) {
            throw failure("parser_failed");
          }
          throw failure;
        } catch (IOException ignored) {
          throw failure("parser_invalid_output");
        }
      } catch (TextParser.Failure safe) {
        checkCancelled();
        throw safe;
      } catch (IOException | InterruptedException | ExecutionException ignored) {
        checkCancelled();
        throw failure("parser_failed");
      } finally {
        // Only this job releases capacity, and only after its child and input writer really stop.
        // A start/kill that cannot be confirmed leaves the global admission gate closed.
        synchronized (lock) {
          // Preserve cancellation without interrupting confirmed resource and ownership cleanup.
          terminalCleanup = true;
          Thread.interrupted();
        }
        cleaned = cleanup();
        if (cleaned) {
          try {
            OwnedTemporaryResources.finished(directory);
          } catch (IOException failedOwnershipCleanup) {
            cleaned = false;
          }
        }
        if (cleaned) {
          synchronized (lock) {
            if (active == this) {
              active = null;
            }
          }
          CAPACITY.release();
        }
        finished.countDown();
        if (!cleaned) {
          throw failure("parser_cleanup_failed");
        }
      }
    }

    boolean cleanup() {
      long until = System.nanoTime() + cleanupNanos();
      try {
        if (process != null) {
          if (process.isAlive()) {
            if (pdfOcr) {
              observeChildren();
              process.destroy();
              // The worker hook closes OCR admission and confirms native exit before JVM exit.
              long grace = Math.min(until, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
              while (process.isAlive() && System.nanoTime() < grace) {
                try {
                  process.waitFor(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                  // Keep the graceful native cleanup window bounded even under repeated cancel.
                }
              }
              observeChildren();
              stopChildren();
            }
            if (process.isAlive()) {
              process.destroyForcibly();
            }
          }
          while (process.isAlive()) {
            long remaining = until - System.nanoTime();
            if (remaining <= 0) {
              return false;
            }
            try {
              if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                return false;
              }
            } catch (InterruptedException ignored) {
              // Cancellation can arrive while cleanup is already in progress. Still confirm exit.
            }
          }
        }
        if (pdfOcr) {
          observing = false;
          if (descendantsThread != null) {
            descendantsThread.interrupt();
            while (descendantsThread.isAlive()) {
              long remaining = until - System.nanoTime();
              if (remaining <= 0) {
                return false;
              }
              try {
                descendantsThread.join(Duration.ofNanos(remaining));
              } catch (InterruptedException ignored) {
                // The observation thread must stop before deciding all tracked children exited.
              }
            }
          }
          stopChildren();
          while (children.entrySet().stream()
              .anyMatch(entry -> alive(entry.getKey(), entry.getValue()))) {
            if (System.nanoTime() >= until) {
              return false;
            }
            try {
              Thread.sleep(10);
            } catch (InterruptedException ignored) {
              // Confirm native process termination before releasing the shared parser permit.
            }
          }
          if (descendantsInvalid) {
            return false;
          }
        }
        if (writerThread != null) {
          writerThread.interrupt();
          while (writerThread.isAlive()) {
            long remaining = until - System.nanoTime();
            if (remaining <= 0) {
              return false;
            }
            try {
              if (!writerThread.join(Duration.ofNanos(remaining))) {
                return false;
              }
            } catch (InterruptedException ignored) {
              // Never abandon a still-running input writer because of a second interrupt.
            }
          }
        }
        if (process != null) {
          process.getInputStream().close();
          process.getOutputStream().close();
          process.getErrorStream().close();
        }
        if (directory != null) {
          Files.walkFileTree(
              directory,
              new SimpleFileVisitor<>() {
                int entries;

                private void bounded() throws IOException {
                  if (++entries > 1024 || System.nanoTime() > until) {
                    throw new IOException();
                  }
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes)
                    throws IOException {
                  bounded();
                  Files.delete(path);
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path path, IOException error)
                    throws IOException {
                  bounded();
                  if (error != null) {
                    throw error;
                  }
                  Files.delete(path);
                  return FileVisitResult.CONTINUE;
                }
              });
        }
        return true;
      } catch (IOException | RuntimeException ignored) {
        return false;
      }
    }

    void watchChildren() {
      try {
        while (observing && process.isAlive()) {
          observeChildren();
          Thread.sleep(10);
        }
      } catch (InterruptedException ended) {
        Thread.currentThread().interrupt();
      }
    }

    void observeChildren() {
      if (process == null) {
        return;
      }
      try (var descendants = process.descendants()) {
        descendants
            .limit(1025)
            .forEach(
                child -> {
                  var born = child.info().startInstant();
                  if (born.isPresent()) {
                    children.putIfAbsent(new Child(child.pid(), born.get()), child);
                    if (children.size() > 4096) {
                      descendantsInvalid = true;
                    }
                  }
                });
      }
    }

    void stopChildren() {
      children.forEach(
          (identity, child) -> {
            if (alive(identity, child)) {
              child.destroyForcibly();
            }
          });
    }
  }

  private record Child(long pid, Instant started) {}

  private static boolean alive(Child identity, ProcessHandle child) {
    return child.isAlive()
        && child.info().startInstant().filter(identity.started()::equals).isPresent();
  }

  private static List<String> launch(String fixtureMainClass, List<String> fixtureArgs) {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    String absolute =
        String.join(
            File.pathSeparator,
            Arrays.stream(classpath.split(File.pathSeparator, -1))
                .map(
                    value ->
                        Path.of(value.isEmpty() ? "." : value)
                            .toAbsolutePath()
                            .normalize()
                            .toString())
                .toList());
    if (fixtureMainClass != null) {
      if (!fixtureMainClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
        throw new IllegalArgumentException("Invalid parser fixture");
      }
      var command = new ArrayList<>(List.of("-cp", absolute, fixtureMainClass));
      command.addAll(List.copyOf(fixtureArgs));
      return List.copyOf(command);
    }
    String[] entries = absolute.split(File.pathSeparator, -1);
    if (entries.length == 1 && entries[0].endsWith(".jar")) {
      try (var jar = new JarFile(entries[0])) {
        var manifest = jar.getManifest();
        String main = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
        if (main != null && main.startsWith("org.springframework.boot.loader.")) {
          return List.of("-jar", entries[0], "--parse-worker");
        }
      } catch (IOException ignored) {
        throw failure("parser_failed");
      }
    }
    return List.of("-cp", absolute, ParserWorker.class.getName());
  }

  private static TextParser.Failure failure(String code) {
    return new TextParser.Failure(code);
  }
}
