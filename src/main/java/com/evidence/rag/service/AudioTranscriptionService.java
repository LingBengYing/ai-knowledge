package com.evidence.rag.service;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/** PCM transcription Module shared by original-audio and video compilation. */
public final class AudioTranscriptionService {
  private final AudioModels models;
  private final int chunkBytes;
  private final long budgetNanos;
  private final String modelRevision;
  private final String transcriptionRevision;

  public AudioTranscriptionService(AudioModels models, int chunkSeconds, Duration budget) {
    if (models == null
        || chunkSeconds < 1
        || chunkSeconds > 30
        || budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMinutes(10)) > 0) {
      throw ModelValues.invalid();
    }
    this.models = models;
    modelRevision = ModelValues.identifier(models.revision(), 200);
    chunkBytes = chunkSeconds * DecodedAudio.BYTES_PER_SECOND;
    budgetNanos = budget.toNanos();
    transcriptionRevision =
        "java-audio-transcription-v1:"
            + ModelValues.sha256(
                (modelRevision + "\0" + chunkSeconds + "\0pcm16k-mono-s16le")
                    .getBytes(StandardCharsets.UTF_8));
  }

  public String revision() {
    if (!modelRevision.equals(models.revision())) {
      throw new TextParser.Failure("audio_profile_changed");
    }
    return transcriptionRevision;
  }

  public AudioTranscription transcribe(DecodedAudio decoded, BooleanSupplier current) {
    long started = System.nanoTime();
    if (decoded == null || current == null) {
      throw ModelValues.invalid();
    }
    byte[] pcm = decoded.pcm();
    var spans = new ArrayList<AudioTranscriptSpan>();
    long textCodePoints = 0;
    for (int offset = 0; offset < pcm.length; offset += chunkBytes) {
      check(current, started);
      int end = Math.min(pcm.length, offset + chunkBytes);
      byte[] wav = AudioPcm.wav(pcm, offset, end);
      check(current, started);
      var transcript = models.transcribe(wav);
      check(current, started);
      if (transcript == null || transcript.text() == null) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      var span =
          new AudioTranscriptSpan(spans.size(), offset / 32L, (end + 31L) / 32, transcript.text());
      textCodePoints += span.text().codePointCount(0, span.text().length());
      if (textCodePoints > 1_000_000) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      spans.add(span);
    }
    check(current, started);
    return new AudioTranscription(
        decoded.sourceSha256(),
        decoded.decoderRevision(),
        modelRevision,
        transcriptionRevision,
        pcm.length / 2L,
        spans);
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
    } catch (TextParser.Failure safe) {
      // The caller may enforce a larger compilation's original deadline and decoder profile.
      throw safe;
    } catch (RuntimeException unavailable) {
      throw new TextParser.Failure("parser_cancelled");
    }
    if (!allowed) {
      throw new TextParser.Failure("parser_cancelled");
    }
    if (!modelRevision.equals(models.revision())) {
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
