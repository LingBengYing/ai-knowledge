package com.evidence.rag.corpus;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
  private final Object lock = new Object();
  private boolean closed;
  private Job active;

  public ProcessTextParser(Duration deadline) {
    this(deadline, null, List.of());
  }

  // Trusted test seam: still uses the same JDK, cleared environment and fixed resource controls.
  ProcessTextParser(Duration deadline, String fixtureMainClass, List<String> fixtureArgs) {
    if (deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0
        || deadline.compareTo(Duration.ofSeconds(60)) > 0)
      throw new IllegalArgumentException("Invalid parser deadline");
    deadlineNanos = deadline.toNanos();
    launch = launch(fixtureMainClass, fixtureArgs);
  }

  public TextParser.Parsed parse(String filename, String mime, byte[] content) {
    long started = System.nanoTime();
    Job job;
    synchronized (lock) {
      if (closed) throw failure("parser_closed");
      if (Thread.currentThread().isInterrupted()) throw failure("parser_interrupted");
      TextParser.validateEnvelope(filename, mime, content);
      if (!CAPACITY.tryAcquire()) throw failure("parser_busy");
      job = new Job(filename, mime, content.clone());
      active = job;
      job.thread = Thread.ofVirtual().name("text-parser-process").unstarted(job.result);
      job.thread.start();
    }
    try {
      long remaining = deadlineNanos - (System.nanoTime() - started);
      if (remaining <= 0) throw new TimeoutException();
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
      if (cause instanceof TextParser.Failure safe) throw safe;
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
    long until = System.nanoTime() + CLEANUP_NANOS;
    try {
      while (job.finished.getCount() != 0) {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) throw failure("parser_cleanup_failed");
        try {
          if (!job.finished.await(remaining, TimeUnit.NANOSECONDS))
            throw failure("parser_cleanup_failed");
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
      if (!job.cleaned) throw failure("parser_cleanup_failed");
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private final class Job {
    final String filename;
    final String mime;
    final byte[] content;
    final AtomicReference<String> cancelled = new AtomicReference<>();
    final CountDownLatch finished = new CountDownLatch(1);
    final FutureTask<TextParser.Parsed> result = new FutureTask<>(this::execute);
    volatile Thread thread;
    volatile Process process;
    volatile boolean cleaned;
    Thread writerThread;
    Path directory;

    Job(String filename, String mime, byte[] content) {
      this.filename = filename;
      this.mime = mime;
      this.content = content;
    }

    void cancel(String code) {
      if (!cancelled.compareAndSet(null, code)) return;
      Process current = process;
      if (current != null) current.destroyForcibly();
      thread.interrupt();
    }

    void checkCancelled() {
      String code = cancelled.get();
      if (code != null) throw failure(code);
    }

    TextParser.Parsed execute() {
      try {
        checkCancelled();
        directory = Files.createTempDirectory("rag-parser-");
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
        var builder =
            new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        // macOS may independently add its numeric CoreFoundation locale variable on JVM startup.
        // No application, provider, JVM-option or database environment variable is inherited here.
        checkCancelled();
        process = builder.start();
        checkCancelled();
        var writer =
            new FutureTask<Void>(
                () -> {
                  try (var output = process.getOutputStream()) {
                    ParserWorker.writeRequest(output, filename, mime, content);
                  }
                  return null;
                });
        writerThread = Thread.ofVirtual().name("text-parser-input").start(writer);
        var response = new ByteArrayOutputStream();
        try (var input = process.getInputStream()) {
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) != -1) {
            checkCancelled();
            if (count > ParserWorker.MAX_OUTPUT - response.size())
              throw failure("parser_invalid_output");
            response.write(buffer, 0, count);
          }
        }
        writer.get();
        if (process.waitFor() != 0) throw failure("parser_failed");
        checkCancelled();
        try {
          return ParserWorker.decode(response.toByteArray());
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
        Thread.interrupted();
        cleaned = cleanup();
        if (cleaned) {
          synchronized (lock) {
            if (active == this) active = null;
          }
          CAPACITY.release();
        }
        finished.countDown();
        if (!cleaned) throw failure("parser_cleanup_failed");
      }
    }

    boolean cleanup() {
      long until = System.nanoTime() + CLEANUP_NANOS;
      try {
        if (process != null) {
          if (process.isAlive()) process.destroyForcibly();
          while (process.isAlive()) {
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return false;
            try {
              if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) return false;
            } catch (InterruptedException ignored) {
              // Cancellation can arrive while cleanup is already in progress. Still confirm exit.
            }
          }
        }
        if (writerThread != null) {
          writerThread.interrupt();
          while (writerThread.isAlive()) {
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return false;
            try {
              if (!writerThread.join(Duration.ofNanos(remaining))) return false;
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
                  if (++entries > 1024 || System.nanoTime() > until) throw new IOException();
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
                  if (error != null) throw error;
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
      if (!fixtureMainClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*"))
        throw new IllegalArgumentException("Invalid parser fixture");
      var command = new ArrayList<>(List.of("-cp", absolute, fixtureMainClass));
      command.addAll(List.copyOf(fixtureArgs));
      return List.copyOf(command);
    }
    String[] entries = absolute.split(File.pathSeparator, -1);
    if (entries.length == 1 && entries[0].endsWith(".jar")) {
      try (var jar = new JarFile(entries[0])) {
        var manifest = jar.getManifest();
        String main = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
        if (main != null && main.startsWith("org.springframework.boot.loader."))
          return List.of("-jar", entries[0], "--parse-worker");
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
