package com.evidence.rag.worker.indexing;

import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.worker.OwnedTemporaryResources;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarFile;

/**
 * One resource-bounded media indexing child JVM at a time: launch, protocol exchange, deadline,
 * cancellation and confirmed cleanup. The media-specific indexers supply only their protocol and
 * their own safe {@code Failure} type.
 *
 * @param <Q> the protocol request
 * @param <R> the verified receipt
 * @param <F> the indexer's safe failure
 */
final class ChildProcessIndexer<Q, R, F extends RuntimeException> implements AutoCloseable {
  private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(2);

  interface RequestWriter<Q> {
    void write(OutputStream output, Q request) throws IOException;
  }

  interface ResponseDecoder<Q, R> {
    R decode(byte[] response, Q request) throws IOException;
  }

  /**
   * The media-specific half. {@code name} (such as {@code image-vector}) derives every failure code
   * ({@code image_vector_*}), thread name, temporary directory prefix and packaged worker flag.
   */
  record Spec<Q, R, F extends RuntimeException>(
      String name,
      String maxHeap,
      Class<?> workerMainClass,
      Class<F> failureType,
      Function<String, F> failure,
      Supplier<F> invalid,
      long maxOutput,
      RequestWriter<Q> writer,
      ResponseDecoder<Q, R> decoder) {
    String code(String suffix) {
      return name.replace('-', '_') + "_" + suffix;
    }
  }

  private final Spec<Q, R, F> spec;
  private final long timeoutNanos;
  private final List<String> launch;
  private final Object lock = new Object();
  private boolean closed;
  private Job active;

  // A non-null fixture main class is the trusted process fixture Adapter; same JDK, cleared
  // environment and resource constraints.
  ChildProcessIndexer(
      Spec<Q, R, F> spec, Duration timeout, String fixtureMainClass, List<String> fixtureArgs) {
    this.spec = spec;
    timeoutNanos = timeout.toNanos();
    launch = launch(fixtureMainClass, fixtureArgs);
  }

  /** {@code request} is built under the lock after the closed and interrupt checks. */
  R index(Supplier<Q> request) {
    long started = System.nanoTime();
    Job job;
    synchronized (lock) {
      if (closed) {
        throw failure("closed");
      }
      if (Thread.currentThread().isInterrupted()) {
        throw failure("interrupted");
      }
      var built = request.get();
      if (active != null) {
        throw failure("busy");
      }
      try {
        job = new Job(built);
      } catch (RuntimeException | Error reserveFailure) {
        active = null;
        throw reserveFailure;
      }
      active = job;
      try {
        job.thread = Thread.ofVirtual().name(spec.name() + "-process").unstarted(job.result);
        job.thread.start();
      } catch (RuntimeException | Error startFailure) {
        job.body.close();
        active = null;
        throw startFailure;
      }
    }
    try {
      long remaining = timeoutNanos - (System.nanoTime() - started);
      if (remaining <= 0) {
        throw new TimeoutException();
      }
      var result = job.result.get(remaining, TimeUnit.NANOSECONDS);
      job.checkCancelled();
      return result;
    } catch (TimeoutException ignored) {
      job.cancel(spec.code("timeout"));
      awaitCleanup(job);
      throw spec.failure().apply(job.cancelled.get());
    } catch (InterruptedException ignored) {
      job.cancel(spec.code("interrupted"));
      try {
        awaitCleanup(job);
      } finally {
        Thread.currentThread().interrupt();
      }
      throw spec.failure().apply(job.cancelled.get());
    } catch (ExecutionException ignored) {
      if (spec.failureType().isInstance(ignored.getCause())) {
        throw spec.failureType().cast(ignored.getCause());
      }
      throw failure("failed");
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
      job.cancel(spec.code("closed"));
      awaitCleanup(job);
    }
  }

  private F failure(String suffix) {
    return spec.failure().apply(spec.code(suffix));
  }

  private void awaitCleanup(Job job) {
    boolean interrupted = Thread.interrupted();
    long until = System.nanoTime() + CLEANUP_NANOS;
    try {
      while (job.finished.getCount() != 0) {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) {
          throw failure("cleanup_failed");
        }
        try {
          if (!job.finished.await(remaining, TimeUnit.NANOSECONDS)) {
            throw failure("cleanup_failed");
          }
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
      if (!job.cleaned) {
        throw failure("cleanup_failed");
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private final class Job {
    final Q request;
    final AtomicReference<String> cancelled = new AtomicReference<>();
    final CountDownLatch finished = new CountDownLatch(1);
    final LibraryOperationGate.ReservedCall<R> body =
        LibraryOperationGate.protectCurrent((Callable<R>) this::execute);
    final FutureTask<R> result = new FutureTask<>(body);
    volatile Thread thread;
    volatile Process process;
    volatile boolean cleaned;
    boolean terminalCleanup;
    Thread writerThread;
    final Path managedRoot = OwnedTemporaryResources.currentRoot();
    Path directory;

    Job(Q request) {
      this.request = request;
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
          current.destroyForcibly();
        }
        thread.interrupt();
      }
    }

    void checkCancelled() {
      String code = cancelled.get();
      if (code != null) {
        throw spec.failure().apply(code);
      }
    }

    R execute() {
      try {
        checkCancelled();
        directory =
            OwnedTemporaryResources.createDirectory("rag-" + spec.name() + "-", managedRoot);
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(
            List.of(
                spec.maxHeap(),
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
        // macOS may independently add its bounded numeric CoreFoundation locale at JVM startup.
        checkCancelled();
        OwnedTemporaryResources.launching(directory);
        process = builder.start();
        OwnedTemporaryResources.childStarted(directory, process);
        checkCancelled();
        var inputBody =
            LibraryOperationGate.protectCurrent(
                (Callable<Void>)
                    () -> {
                      try (var output = process.getOutputStream()) {
                        spec.writer().write(output, request);
                      }
                      return null;
                    });
        var writer = new FutureTask<Void>(inputBody);
        try {
          writerThread = Thread.ofVirtual().name(spec.name() + "-input").start(writer);
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
            if (count > spec.maxOutput() - response.size()) {
              throw spec.invalid().get();
            }
            response.write(buffer, 0, count);
          }
        }
        writer.get();
        if (process.waitFor() != 0) {
          throw failure("failed");
        }
        checkCancelled();
        try {
          return spec.decoder().decode(response.toByteArray(), request);
        } catch (IOException ignored) {
          throw spec.invalid().get();
        }
      } catch (RuntimeException unchecked) {
        if (spec.failureType().isInstance(unchecked)) {
          checkCancelled();
        }
        throw unchecked;
      } catch (IOException | InterruptedException | ExecutionException ignored) {
        checkCancelled();
        throw failure("failed");
      } finally {
        // Confirm both child and input writer have stopped before this instance can be reused.
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
        }
        finished.countDown();
        if (!cleaned) {
          throw failure("cleanup_failed");
        }
      }
    }

    boolean cleanup() {
      long until = System.nanoTime() + CLEANUP_NANOS;
      try {
        if (process != null) {
          if (process.isAlive()) {
            process.destroyForcibly();
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
              /* Repeated cancellation cannot skip exit confirmation. */
            }
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
              /* Confirm the writer has also stopped. */
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
  }

  private List<String> launch(String fixtureMainClass, List<String> fixtureArgs) {
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
        throw spec.invalid().get();
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
          return List.of("-jar", entries[0], "--" + spec.name() + "-worker");
        }
      } catch (IOException ignored) {
        throw failure("failed");
      }
    }
    return List.of("-cp", absolute, spec.workerMainClass().getName());
  }
}
