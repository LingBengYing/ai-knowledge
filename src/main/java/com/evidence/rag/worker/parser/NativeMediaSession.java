package com.evidence.rag.worker.parser;

import com.evidence.rag.tool.parser.TextParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Shared native admission, deadline, executable identity and confirmed private-job cleanup. */
final class NativeMediaSession implements AutoCloseable {
  private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(2);
  private static final Semaphore CAPACITY = new Semaphore(1);
  private final Path ffmpeg;
  private final Path ffprobe;
  private final String ffmpegHash;
  private final String ffprobeHash;
  private final long deadlineNanos;
  private final String kind;
  private final List<String> fixtureCommand;
  private final Object lock = new Object();
  private boolean closed;
  private Job<?> active;

  NativeMediaSession(
      Path ffmpeg,
      Path ffprobe,
      Duration deadline,
      String kind,
      String fixtureMain,
      List<String> fixtureArgs) {
    if (deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0
        || deadline.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalArgumentException("Invalid native media deadline");
    }
    this.ffmpeg = executable(ffmpeg);
    this.ffprobe = executable(ffprobe);
    ffmpegHash = executableHash(this.ffmpeg);
    ffprobeHash = executableHash(this.ffprobe);
    deadlineNanos = deadline.toNanos();
    this.kind = kind;
    fixtureCommand = fixtureMain == null ? List.of() : fixtureCommand(fixtureMain, fixtureArgs);
  }

  String ffmpegHash() {
    return ffmpegHash;
  }

  String ffprobeHash() {
    return ffprobeHash;
  }

  <T> T execute(String filename, byte[] source, Consumer<byte[]> admission, Work<T> work) {
    long started = System.nanoTime();
    Job<T> job;
    synchronized (lock) {
      if (closed) {
        throw failure("parser_closed");
      }
      if (Thread.currentThread().isInterrupted()) {
        throw failure("parser_interrupted");
      }
      if (source == null || source.length > 20 * 1024 * 1024) {
        throw failure("unsupported_document");
      }
      byte[] original = source.clone();
      admission.accept(original);
      if (!CAPACITY.tryAcquire()) {
        throw failure("parser_busy");
      }
      job = new Job<>(original, filename, work);
      active = job;
      job.thread = Thread.ofVirtual().name(kind + "-decoder-process").unstarted(job.result);
      job.thread.start();
    }
    try {
      long remaining = deadlineNanos - (System.nanoTime() - started);
      if (remaining <= 0) {
        throw new TimeoutException();
      }
      T decoded = job.result.get(remaining, TimeUnit.NANOSECONDS);
      job.checkCancelled();
      return decoded;
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
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof TextParser.Failure safe) {
        throw safe;
      }
      throw failure("parser_failed");
    }
  }

  @Override
  public void close() {
    Job<?> job;
    synchronized (lock) {
      closed = true;
      job = active;
    }
    if (job != null) {
      job.cancel("parser_closed");
      awaitCleanup(job);
    }
  }

  private static void awaitCleanup(NativeMediaSession.Job<?> job) {
    boolean interrupted = Thread.interrupted();
    long until = System.nanoTime() + CLEANUP_NANOS;
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

  @FunctionalInterface
  interface Work<T> {
    T run(NativeMediaSession.Job<T> job) throws IOException, InterruptedException;
  }

  record Output(byte[] stdout, byte[] stderr) {}

  final class Job<T> {
    final byte[] source;
    final String suffix;
    final Work<T> work;
    final AtomicReference<String> cancelled = new AtomicReference<>();
    final CountDownLatch finished = new CountDownLatch(1);
    final FutureTask<T> result = new FutureTask<>(this::execute);
    volatile Thread thread;
    volatile Process process;
    volatile Thread errorReader;
    volatile boolean cleaned;
    Path directory;
    Path input;

    Job(byte[] source, String filename, Work<T> work) {
      this.source = source;
      suffix = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
      this.work = work;
    }

    void cancel(String code) {
      if (cancelled.compareAndSet(null, code)) {
        Process child = process;
        if (child != null) {
          child.destroyForcibly();
        }
        thread.interrupt();
      }
    }

    void checkCancelled() {
      if (cancelled.get() != null) {
        throw failure(cancelled.get());
      }
    }

    T execute() {
      try {
        checkCancelled();
        directory = Files.createTempDirectory("rag-" + kind + "-decoder-");
        input = directory.resolve("original" + suffix);
        Files.write(input, source);
        checkCancelled();
        return work.run(this);
      } catch (TextParser.Failure safe) {
        checkCancelled();
        throw safe;
      } catch (IOException | InterruptedException failed) {
        checkCancelled();
        throw failure("parser_failed");
      } finally {
        Thread.interrupted();
        cleaned = cleanup();
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

    byte[] probe(String phase, List<String> args, int maximum)
        throws IOException, InterruptedException {
      return run(ffprobe, ffprobeHash, phase, args, maximum, 0).stdout();
    }

    Output decode(String phase, List<String> args, int maximum, int maximumError)
        throws IOException, InterruptedException {
      return run(ffmpeg, ffmpegHash, phase, args, maximum, maximumError);
    }

    private Output run(
        Path executable,
        String expectedHash,
        String phase,
        List<String> args,
        int maximum,
        int maximumError)
        throws IOException, InterruptedException {
      checkCancelled();
      if (!expectedHash.equals(executableHash(executable))) {
        throw failure("parser_failed");
      }
      var command = new ArrayList<String>();
      if (fixtureCommand.isEmpty()) {
        command.add(executable.toString());
      } else {
        command.addAll(fixtureCommand);
        command.add(phase);
      }
      command.addAll(args);
      var builder = new ProcessBuilder(command).directory(directory.toFile());
      if (maximumError == 0) {
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      }
      builder.environment().clear();
      checkCancelled();
      process = builder.start();
      Process child = process;
      checkCancelled();
      child.getOutputStream().close();
      FutureTask<byte[]> error = null;
      if (maximumError > 0) {
        error =
            new FutureTask<>(
                () -> {
                  try {
                    return read(child.getErrorStream(), maximumError);
                  } catch (IOException | RuntimeException failed) {
                    child.destroyForcibly();
                    throw failed;
                  }
                });
        errorReader = Thread.ofVirtual().name(kind + "-decoder-stderr").start(error);
      }
      byte[] stdout = read(child.getInputStream(), maximum);
      byte[] stderr = new byte[0];
      if (error != null) {
        try {
          stderr = error.get();
        } catch (ExecutionException failed) {
          if (failed.getCause() instanceof TextParser.Failure safe) {
            throw safe;
          }
          throw failure("parser_failed");
        }
        errorReader.join();
        errorReader = null;
      }
      if (child.waitFor() != 0) {
        throw failure("parser_failed");
      }
      checkCancelled();
      child.getErrorStream().close();
      process = null;
      return new Output(stdout, stderr);
    }

    private byte[] read(InputStream stream, int maximum) throws IOException {
      var response = new ByteArrayOutputStream();
      try (stream) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
          checkCancelled();
          if (count > maximum - response.size()) {
            throw failure("parser_invalid_output");
          }
          response.write(buffer, 0, count);
        }
      }
      return response.toByteArray();
    }

    private boolean cleanup() {
      long until = System.nanoTime() + CLEANUP_NANOS;
      try {
        if (process != null) {
          process.destroyForcibly();
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
              // Further cancellation must not abandon an unconfirmed native process.
            }
          }
          process.getInputStream().close();
          process.getOutputStream().close();
          process.getErrorStream().close();
        }
        while (errorReader != null && errorReader.isAlive()) {
          long remaining = until - System.nanoTime();
          if (remaining <= 0) {
            return false;
          }
          try {
            if (!errorReader.join(Duration.ofNanos(remaining))) {
              return false;
            }
          } catch (InterruptedException ignored) {
            // The owned reader must exit before admission is released.
          }
        }
        if (input != null) {
          Files.deleteIfExists(input);
        }
        if (directory != null) {
          Files.delete(directory);
        }
        return true;
      } catch (IOException | RuntimeException failed) {
        return false;
      }
    }
  }

  private static Path executable(Path path) {
    try {
      if (path == null
          || !path.isAbsolute()
          || !path.normalize().equals(path)
          || path.toString().codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Invalid native media executable");
      }
      Path actual = path.toRealPath();
      if (!Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(actual)) {
        throw new IllegalArgumentException("Invalid native media executable");
      }
      return actual;
    } catch (IOException failure) {
      throw new IllegalArgumentException("Invalid native media executable");
    }
  }

  private static String executableHash(Path executable) {
    try (var input = Files.newInputStream(executable)) {
      var hash = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        hash.update(buffer, 0, count);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (IOException | NoSuchAlgorithmException failure) {
      throw new IllegalArgumentException("Cannot identify native media executable");
    }
  }

  private static List<String> fixtureCommand(String main, List<String> args) {
    if (!main.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
      throw new IllegalArgumentException("Invalid native media fixture");
    }
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
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-XX:+UseSerialGC",
                "-XX:ActiveProcessorCount=1",
                "-cp",
                absolute,
                main));
    command.addAll(List.copyOf(args));
    return List.copyOf(command);
  }

  private static TextParser.Failure failure(String code) {
    return new TextParser.Failure(code);
  }
}
