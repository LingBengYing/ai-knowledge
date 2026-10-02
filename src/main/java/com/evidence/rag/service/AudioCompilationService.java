package com.evidence.rag.service;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Decode and transcribe a complete source; callers retain publication and current authority. */
public final class AudioCompilationService {
  private final AudioDecoder decoder;
  private final AudioModels models;
  private final AudioTranscriptionService transcription;
  private final long budgetNanos;
  private final String decoderRevision;
  private final String modelRevision;
  private final String compilerRevision;

  public AudioCompilationService(
      AudioDecoder decoder, AudioModels models, int chunkSeconds, Duration budget) {
    if (decoder == null
        || models == null
        || chunkSeconds < 1
        || chunkSeconds > 30
        || budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMinutes(10)) > 0) {
      throw ModelValues.invalid();
    }
    this.decoder = decoder;
    this.models = models;
    decoderRevision = ModelValues.identifier(decoder.revision(), 200);
    modelRevision = ModelValues.identifier(models.revision(), 200);
    budgetNanos = budget.toNanos();
    compilerRevision =
        "java-audio-compiler-v1:"
            + ModelValues.sha256(
                (decoderRevision
                        + "\0"
                        + modelRevision
                        + "\0"
                        + chunkSeconds
                        + "\0pcm16k-mono-s16le")
                    .getBytes(StandardCharsets.UTF_8));
    transcription = new AudioTranscriptionService(models, chunkSeconds, budget);
  }

  public String revision() {
    return compilerRevision;
  }

  public AudioCompilation compile(
      String filename, String mime, byte[] source, BooleanSupplier current) {
    long started = System.nanoTime();
    if (current == null) {
      throw ModelValues.invalid();
    }
    check(current, started);
    if (source == null || source.length == 0 || source.length > 20 * 1024 * 1024) {
      throw new TextParser.Failure("unsupported_document");
    }
    byte[] original = source.clone();
    String sourceSha = ModelValues.sha256(original);
    var decoded = decoder.decode(filename, mime, original);
    check(current, started);
    if (decoded == null
        || !sourceSha.equals(decoded.sourceSha256())
        || !decoderRevision.equals(decoded.decoderRevision())) {
      throw new TextParser.Failure("parser_invalid_output");
    }
    var transcript =
        transcription.transcribe(
            decoded,
            () -> {
              check(current, started);
              return true;
            });
    if (transcript.spans().stream().allMatch(span -> span.text().isBlank())) {
      throw new TextParser.Failure("audio_no_speech");
    }
    return new AudioCompilation(
        sourceSha,
        decoderRevision,
        modelRevision,
        compilerRevision,
        decoded.durationMs(),
        transcript.spans());
  }

  public boolean configurationCurrent() {
    return decoderRevision.equals(decoder.revision()) && modelRevision.equals(models.revision());
  }

  private void check(BooleanSupplier current, long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextParser.Failure("parser_interrupted");
    }
    if (System.nanoTime() - started >= budgetNanos) {
      throw new TextParser.Failure("parser_timeout");
    }
    boolean allowed;
    try {
      allowed = current.getAsBoolean();
    } catch (RuntimeException unavailable) {
      throw new TextParser.Failure("parser_cancelled");
    }
    if (!allowed) {
      throw new TextParser.Failure("parser_cancelled");
    }
    if (!configurationCurrent()) {
      throw new TextParser.Failure("audio_profile_changed");
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new TextParser.Failure("parser_interrupted");
    }
    if (System.nanoTime() - started >= budgetNanos) {
      throw new TextParser.Failure("parser_timeout");
    }
  }
}
