package com.evidence.rag.service;

import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundInputSpan;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.tool.parser.AudioInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Complete original-sound PCM preparation: one actual decode and no ASR or model dependency. */
public final class SoundCompilationService implements AutoCloseable {
  private final AudioDecoder decoder;
  private final String decoderRevision;
  private final int chunkSeconds;
  private final long budgetNanos;
  private final String revision;
  private volatile boolean closed;

  public SoundCompilationService(AudioDecoder decoder, int chunkSeconds, Duration budget) {
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
        "java-sound-compilation-v1:"
            + ModelValues.sha256(
                (decoderRevision + "\0" + chunkSeconds + "\0complete-actual-pcm16k-mono-s16le-v1")
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

  public List<SoundInputSpan> decode(DocumentOriginal original, BooleanSupplier current) {
    if (original == null || !"audio".equals(original.documentType())) {
      throw ModelValues.invalid();
    }
    var waveforms =
        decodeQuery(original.filename(), original.mediaType(), original.content(), current);
    var spans = new ArrayList<SoundInputSpan>();
    for (int i = 0; i < waveforms.size(); i++) {
      spans.add(
          new SoundInputSpan(SoundProfile.spanId(original.revisionId(), i), i, waveforms.get(i)));
    }
    return List.copyOf(spans);
  }

  public List<AudioWaveform> decodeQuery(
      String filename, String mime, byte[] source, BooleanSupplier current) {
    long started = System.nanoTime();
    if (current == null) {
      throw ModelValues.invalid();
    }
    check(current, started);
    AudioInput.validateEnvelope(filename, mime, source);
    byte[] privateSource = source.clone();
    String sourceSha = ModelValues.sha256(privateSource);
    var reserved =
        LibraryOperationGate.protectCurrent(
            () -> {
              return decoder.decode(filename, mime, privateSource);
            });
    var task = new FutureTask<>(reserved);
    var thread = Thread.ofVirtual().name("sound-source-decode").unstarted(task);
    try {
      thread.start();
    } catch (RuntimeException | Error failedStart) {
      reserved.close();
      throw failedStart;
    }
    DecodedAudio decoded;
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
    byte[] pcm = decoded.pcm();
    int chunkBytes = chunkSeconds * DecodedAudio.BYTES_PER_SECOND;
    var result = new ArrayList<AudioWaveform>();
    for (int offset = 0; offset < pcm.length; offset += chunkBytes) {
      check(current, started);
      int end = Math.min(pcm.length, offset + chunkBytes);
      result.add(
          new AudioWaveform(
              sourceSha,
              decoderRevision,
              offset / 2L,
              end / 2L,
              Arrays.copyOfRange(pcm, offset, end)));
    }
    check(current, started);
    return List.copyOf(result);
  }

  private void check(BooleanSupplier current, long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextParser.Failure("parser_interrupted");
    }
    if (System.nanoTime() - started >= budgetNanos) {
      throw new TextParser.Failure("parser_timeout");
    }
    if (!configurationCurrent()) {
      throw new TextParser.Failure("sound_profile_changed");
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
