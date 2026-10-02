package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.ImageTsvParser;
import com.evidence.rag.tool.parser.TextParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** One explicit, resource-bounded OCR operation without inherited application credentials. */
public final class ProcessImageParser implements ImageOcr, AutoCloseable {
  private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(2);
  private static final Semaphore CAPACITY = new Semaphore(1);
  private final long deadlineNanos;
  private final String revision;
  private final List<String> command;
  private final Object lock = new Object();
  private boolean closed;
  private Job active;

  public ProcessImageParser(ImageOcrOptions options, Duration deadline) {
    this(options, deadline, null, List.of());
  }

  @Override
  public String revision() {
    return revision;
  }

  @Override
  public Optional<ParsedImage> read(VisualImage image) {
    if (image == null) {
      throw failure("unsupported_document");
    }
    return run(
        "image/png".equals(image.mediaType()) ? "frame.png" : "frame.jpg",
        image.mediaType(),
        image.content());
  }

  // This trusted test seam launches a real child with the same byte/lifecycle controls.
  ProcessImageParser(
      ImageOcrOptions options, Duration deadline, String fixtureMain, List<String> fixtureArgs) {
    Objects.requireNonNull(options);
    if (deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0
        || deadline.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalArgumentException("Invalid parser deadline");
    }
    deadlineNanos = deadline.toNanos();
    revision = options.parserRevision();
    command =
        fixtureMain == null
            ? List.of(
                options.executable().toString(),
                "stdin",
                "stdout",
                "-l",
                options.language(),
                "--psm",
                "6",
                "tsv")
            : fixtureCommand(fixtureMain, fixtureArgs);
  }

  public ParsedImage parse(String filename, String mime, byte[] content) {
    return run(filename, mime, content).orElseThrow(() -> failure("parser_invalid_output"));
  }

  private Optional<ParsedImage> run(String filename, String mime, byte[] content) {
    long started = System.nanoTime();
    Job job;
    synchronized (lock) {
      if (closed) {
        throw failure("parser_closed");
      }
      if (Thread.currentThread().isInterrupted()) {
        throw failure("parser_interrupted");
      }
      if (content == null || content.length > ImageInput.MAX_BYTES) {
        throw failure("unsupported_document");
      }
      byte[] original = content.clone();
      ImageInput.validateEnvelope(filename, mime, original);
      ImageDimensions dimensions = ImageInput.inspect(original);
      if (!CAPACITY.tryAcquire()) {
        throw failure("parser_busy");
      }
      job = new Job(original, dimensions);
      active = job;
      job.thread = Thread.ofVirtual().name("image-ocr-process").unstarted(job.result);
      job.thread.start();
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
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof TextParser.Failure safe) {
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

  private final class Job {
    final byte[] content;
    final ImageDimensions dimensions;
    final AtomicReference<String> cancelled = new AtomicReference<>();
    final CountDownLatch finished = new CountDownLatch(1);
    final FutureTask<Optional<ParsedImage>> result = new FutureTask<>(this::execute);
    volatile Thread thread;
    volatile Process process;
    volatile boolean cleaned;
    Thread writerThread;
    Path directory;

    Job(byte[] content, ImageDimensions dimensions) {
      this.content = content;
      this.dimensions = dimensions;
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

    Optional<ParsedImage> execute() {
      try {
        checkCancelled();
        directory = Files.createTempDirectory("rag-image-parser-");
        var builder =
            new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        checkCancelled();
        process = builder.start();
        checkCancelled();
        var writer =
            new FutureTask<Void>(
                () -> {
                  try (var output = process.getOutputStream()) {
                    output.write(content);
                  }
                  return null;
                });
        writerThread = Thread.ofVirtual().name("image-ocr-input").start(writer);
        var response = new ByteArrayOutputStream();
        try (var input = process.getInputStream()) {
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) != -1) {
            checkCancelled();
            if (count > ImageTsvParser.MAX_BYTES - response.size()) {
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
          return ImageTsvParser.parseOptional(response.toByteArray(), dimensions);
        } catch (TextParser.Failure invalid) {
          throw failure("parser_invalid_output");
        }
      } catch (TextParser.Failure safe) {
        checkCancelled();
        throw safe;
      } catch (IOException | InterruptedException | ExecutionException failed) {
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

    boolean cleanup() {
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
              // A second cancellation must not abandon an unconfirmed child.
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
              // Confirm the input writer has really stopped before releasing admission.
            }
          }
        }
        if (process != null) {
          process.getInputStream().close();
          process.getOutputStream().close();
          process.getErrorStream().close();
        }
        if (directory != null) {
          // Fixed stdin/stdout execution creates no output files. Never recursively erase extras.
          Files.delete(directory);
        }
        return true;
      } catch (IOException | RuntimeException failed) {
        return false;
      }
    }
  }

  private static List<String> fixtureCommand(String main, List<String> args) {
    if (!main.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
      throw new IllegalArgumentException("Invalid parser fixture");
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
