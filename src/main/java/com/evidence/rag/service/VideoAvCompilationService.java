package com.evidence.rag.service;

import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvWindow;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.tool.parser.VideoInput;
import com.evidence.rag.worker.parser.VideoAvDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Complete original-video preparation: one actual native pass, no ASR or model dependency. */
public final class VideoAvCompilationService implements AutoCloseable {
  private final VideoAvDecoder decoder;
  private final String decoderRevision;
  private final int chunkSeconds;
  private final long budgetNanos;
  private final String revision;
  private volatile boolean closed;

  public VideoAvCompilationService(VideoAvDecoder decoder, int chunkSeconds, Duration budget) {
    if (decoder == null
        || chunkSeconds < 1
        || chunkSeconds > 30
        || budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMinutes(2)) > 0) {
      throw ModelValues.invalid();
    }
    this.decoder = decoder;
    this.decoderRevision = ModelValues.identifier(decoder.revision(), 200);
    this.chunkSeconds = chunkSeconds;
    this.budgetNanos = budget.toNanos();
    revision =
        "java-video-av-compilation-v1:"
            + ModelValues.sha256(
                (decoderRevision
                        + "\0"
                        + chunkSeconds
                        + "\0complete-actual-mp4-pcm-rational-epoch-v1")
                    .getBytes(StandardCharsets.UTF_8));
  }

  public String revision() {
    return revision;
  }

  public String decoderRevision() {
    return decoderRevision;
  }

  public int chunkSeconds() {
    return chunkSeconds;
  }

  public boolean configurationCurrent() {
    return !closed && decoderRevision.equals(decoder.revision());
  }

  public VideoAvCompilation compile(DocumentOriginal original, BooleanSupplier current) {
    if (original == null || !"video".equals(original.documentType()) || current == null) {
      throw ModelValues.invalid();
    }
    return compile(
        original.filename(),
        original.mediaType(),
        original.content(),
        original.sourceSha256(),
        original.revisionId(),
        current);
  }

  /** Admits one complete reference at the HTTP boundary without starting a decoder. */
  public static void validateQueryInput(QueryAttachment attachment) {
    if (attachment == null || attachment.kind() != QueryAttachment.Kind.VIDEO) {
      throw ModelValues.invalid();
    }
    VideoInput.validateEnvelope(
        attachment.filename(), attachment.mediaType(), attachment.content());
  }

  public VideoAvCompilation compileQuery(QueryAttachment attachment, BooleanSupplier current) {
    if (attachment == null || attachment.kind() != QueryAttachment.Kind.VIDEO || current == null) {
      throw ModelValues.invalid();
    }
    String sourceSha = attachment.sha256();
    String queryRevision =
        "query-"
            + ModelValues.sha256((sourceSha + "\0" + revision).getBytes(StandardCharsets.UTF_8));
    return compile(
        attachment.filename(),
        attachment.mediaType(),
        attachment.content(),
        sourceSha,
        queryRevision,
        current);
  }

  private VideoAvCompilation compile(
      String filename,
      String mime,
      byte[] privateSource,
      String sourceSha,
      String sourceRevision,
      BooleanSupplier current) {
    long started = System.nanoTime();
    check(current, started);
    VideoInput.validateEnvelope(filename, mime, privateSource);
    var reserved =
        LibraryOperationGate.protectCurrent(
            () -> {
              return decoder.decode(filename, mime, privateSource);
            });
    var task = new FutureTask<>(reserved);
    var thread = Thread.ofVirtual().name("video-av-source-decode").unstarted(task);
    try {
      thread.start();
    } catch (RuntimeException | Error failedStart) {
      reserved.close();
      throw failedStart;
    }
    VideoAvCompilation decoded;
    try {
      while (true) {
        check(current, started);
        try {
          decoded =
              task.get(
                  Math.min(
                      budgetNanos - (System.nanoTime() - started),
                      TimeUnit.MILLISECONDS.toNanos(25)),
                  TimeUnit.NANOSECONDS);
          break;
        } catch (TimeoutException tick) {
          check(current, started);
        }
      }
    } catch (InterruptedException interrupted) {
      stop(thread);
      Thread.currentThread().interrupt();
      throw new TextParser.Failure("parser_interrupted");
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof TextParser.Failure safe) {
        throw safe;
      }
      throw new TextParser.Failure("parser_invalid_output");
    } catch (RuntimeException failure) {
      stop(thread);
      throw failure;
    } finally {
      reserved.close();
    }
    check(current, started);
    if (decoded == null
        || !sourceSha.equals(decoded.sourceSha256())
        || !decoderRevision.equals(decoded.decoderRevision())) {
      throw new TextParser.Failure("parser_invalid_output");
    }
    var windows = new ArrayList<VideoAvWindow>();
    for (var w : decoded.windows()) {
      check(current, started);
      if (w.endTick() - w.startTick() > decoded.epoch().durationLimit(chunkSeconds)) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      windows.add(
          new VideoAvWindow(
              VideoAvProfile.windowId(sourceRevision, w.ordinal()),
              w.ordinal(),
              w.startTick(),
              w.endTick(),
              w.video(),
              w.audio()));
    }
    check(current, started);
    return new VideoAvCompilation(
        sourceSha,
        decoderRevision,
        decoded.epoch(),
        decoded.durationTick(),
        decoded.hasAudio(),
        windows);
  }

  private void check(BooleanSupplier current, long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextParser.Failure("parser_interrupted");
    }
    if (System.nanoTime() - started >= budgetNanos) {
      throw new TextParser.Failure("parser_timeout");
    }
    if (!configurationCurrent()) {
      throw new TextParser.Failure("video_av_profile_changed");
    }
    if (!current.getAsBoolean()) {
      throw new TextParser.Failure("parser_cancelled");
    }
  }

  private static void stop(Thread thread) {
    boolean interrupted = Thread.interrupted();
    thread.interrupt();
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    try {
      while (thread.isAlive()) {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) {
          throw new TextParser.Failure("parser_cleanup_failed");
        }
        try {
          if (!thread.join(Duration.ofNanos(remaining))) {
            throw new TextParser.Failure("parser_cleanup_failed");
          }
        } catch (InterruptedException repeated) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void close() {
    closed = true;
    decoder.close();
  }
}
