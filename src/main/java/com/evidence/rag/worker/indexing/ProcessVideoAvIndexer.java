package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.GeminiVideoAvEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.VideoAvBuildClaim;
import com.evidence.rag.model.domain.VideoAvReceipt;
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
import java.util.jar.JarFile;

/** Resource-bounded indexing subprocess Module. */
public final class ProcessVideoAvIndexer implements AutoCloseable {
  private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(2);
  private final GeminiVideoAvEmbeddingModels.Configuration models;
  private final MilvusRestProjection.Settings visualProjection;
  private final MilvusRestProjection.Settings audioProjection;
  private final Duration timeout;
  private final long timeoutNanos;
  private final List<String> launch;
  private final Object lock = new Object();
  private boolean closed;
  private Job active;

  public ProcessVideoAvIndexer(
      GeminiVideoAvEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings visualProjection,
      MilvusRestProjection.Settings audioProjection,
      Duration timeout) {
    this(models, visualProjection, audioProjection, timeout, null, List.of());
  }

  // Trusted process fixture Adapter; same JDK, cleared environment and resource constraints.
  ProcessVideoAvIndexer(
      GeminiVideoAvEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings visualProjection,
      MilvusRestProjection.Settings audioProjection,
      Duration timeout,
      String fixtureMainClass,
      List<String> fixtureArgs) {
    VideoAvIndexProtocol.validateSettings(models, visualProjection, audioProjection, timeout);
    this.models = models;
    this.visualProjection = visualProjection;
    this.audioProjection = audioProjection;
    this.timeout = timeout;
    timeoutNanos = timeout.toNanos();
    launch = launch(fixtureMainClass, fixtureArgs);
  }

  /** Independently verifies every actual material/vector binding before authority publication. */
  public static void verify(VideoAvBuildClaim claim, VideoAvReceipt receipt) {
    VideoAvIndexProtocol.verify(claim, receipt);
  }

  public VideoAvReceipt index(VideoAvBuildClaim claim) {
    return executeIndex(claim);
  }

  private VideoAvReceipt executeIndex(VideoAvBuildClaim claim) {
    long started = System.nanoTime();
    Job job;
    synchronized (lock) {
      if (closed) {
        throw new Failure("video_av_index_closed");
      }
      if (Thread.currentThread().isInterrupted()) {
        throw new Failure("video_av_index_interrupted");
      }
      var request =
          VideoAvIndexProtocol.request(models, visualProjection, audioProjection, timeout, claim);
      if (active != null) {
        throw new Failure("video_av_index_busy");
      }
      try {
        job = new Job(request);
      } catch (RuntimeException | Error reserveFailure) {
        active = null;
        throw reserveFailure;
      }
      active = job;
      try {
        job.thread = Thread.ofVirtual().name("video-av-index-process").unstarted(job.result);
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
      job.cancel("video_av_index_timeout");
      awaitCleanup(job);
      throw new Failure(job.cancelled.get());
    } catch (InterruptedException ignored) {
      job.cancel("video_av_index_interrupted");
      try {
        awaitCleanup(job);
      } finally {
        Thread.currentThread().interrupt();
      }
      throw new Failure(job.cancelled.get());
    } catch (ExecutionException ignored) {
      if (ignored.getCause() instanceof Failure safe) {
        throw safe;
      }
      throw new Failure("video_av_index_failed");
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
      job.cancel("video_av_index_closed");
      awaitCleanup(job);
    }
  }

  private static void awaitCleanup(Job job) {
    boolean interrupted = Thread.interrupted();
    long until = System.nanoTime() + CLEANUP_NANOS;
    try {
      while (job.finished.getCount() != 0) {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) {
          throw new Failure("video_av_index_cleanup_failed");
        }
        try {
          if (!job.finished.await(remaining, TimeUnit.NANOSECONDS)) {
            throw new Failure("video_av_index_cleanup_failed");
          }
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
      if (!job.cleaned) {
        throw new Failure("video_av_index_cleanup_failed");
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private final class Job {
    final VideoAvIndexProtocol.Request request;
    final AtomicReference<String> cancelled = new AtomicReference<>();
    final CountDownLatch finished = new CountDownLatch(1);
    final LibraryOperationGate.ReservedCall<VideoAvReceipt> body =
        LibraryOperationGate.protectCurrent((Callable<VideoAvReceipt>) this::execute);
    final FutureTask<VideoAvReceipt> result = new FutureTask<>(body);
    volatile Thread thread;
    volatile Process process;
    volatile boolean cleaned;
    boolean terminalCleanup;
    Thread writerThread;
    final Path managedRoot = OwnedTemporaryResources.currentRoot();
    Path directory;

    Job(VideoAvIndexProtocol.Request request) {
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
        throw new Failure(code);
      }
    }

    VideoAvReceipt execute() {
      try {
        checkCancelled();
        directory = OwnedTemporaryResources.createDirectory("rag-video-av-index-", managedRoot);
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(
            List.of(
                "-Xmx512m",
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
                        VideoAvIndexProtocol.writeRequest(output, request);
                      }
                      return null;
                    });
        var writer = new FutureTask<Void>(inputBody);
        try {
          writerThread = Thread.ofVirtual().name("video-av-index-input").start(writer);
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
            if (count > VideoAvIndexProtocol.MAX_OUTPUT - response.size()) {
              throw VideoAvIndexProtocol.invalid();
            }
            response.write(buffer, 0, count);
          }
        }
        writer.get();
        if (process.waitFor() != 0) {
          throw new Failure("video_av_index_failed");
        }
        checkCancelled();
        try {
          return VideoAvIndexProtocol.decode(response.toByteArray(), request);
        } catch (IOException ignored) {
          throw VideoAvIndexProtocol.invalid();
        }
      } catch (Failure safe) {
        checkCancelled();
        throw safe;
      } catch (IOException | InterruptedException | ExecutionException ignored) {
        checkCancelled();
        throw new Failure("video_av_index_failed");
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
          throw new Failure("video_av_index_cleanup_failed");
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
        throw VideoAvIndexProtocol.invalid();
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
          return List.of("-jar", entries[0], "--video-av-index-worker");
        }
      } catch (IOException ignored) {
        throw new Failure("video_av_index_failed");
      }
    }
    return List.of("-cp", absolute, VideoAvIndexWorker.class.getName());
  }

  public static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public Failure(String code) {
      super("索引进程未完成安全校验。", null, false, true);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
