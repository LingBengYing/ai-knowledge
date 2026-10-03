package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.VoiceQuestionCommand;
import com.evidence.rag.model.dto.VoiceQuestionResult;
import com.evidence.rag.tool.parser.AudioInput;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;

/** Complete temporary speech input; no knowledge-library authority or answer dependency. */
public final class VoiceQuestionService {
  private final AudioCompilationService audio;
  private final long budgetNanos;
  private final String compilerRevision;

  public VoiceQuestionService(AudioCompilationService audio, Duration processingBudget) {
    if (audio == null
        || processingBudget == null
        || processingBudget.compareTo(Duration.ofMillis(10)) < 0
        || processingBudget.compareTo(Duration.ofSeconds(120)) > 0) {
      throw ModelValues.invalid();
    }
    this.audio = audio;
    budgetNanos = processingBudget.toNanos();
    compilerRevision = ModelValues.identifier(audio.revision(), 200);
  }

  public VoiceQuestionResult transcribe(Actor actor, VoiceQuestionCommand command) {
    if (actor == null || command == null) {
      throw problem(FailureKind.INVALID_INPUT, "voice_audio_invalid");
    }
    long started = System.nanoTime();
    check(started);
    var input = command.audio();
    AudioCompilation compiled;
    try {
      byte[] original = input.content();
      AudioInput.validateEnvelope(input.filename(), input.mediaType(), original);
      compiled =
          audio.compile(input.filename(), input.mediaType(), original, () -> active(started));
    } catch (TextParser.Failure failure) {
      // The existing compiler sanitizes callback failures: restore this request's current reason.
      check(started);
      throw mapped(failure.code());
    } catch (TextModels.Failure failure) {
      check(started);
      throw mapped(failure.code());
    } catch (RuntimeException unavailable) {
      check(started);
      throw problem(FailureKind.UNAVAILABLE, "voice_question_unavailable");
    }
    check(started);
    if (compiled == null
        || !input.sha256().equals(compiled.sourceSha256())
        || !compilerRevision.equals(compiled.compilerRevision())) {
      throw problem(FailureKind.UNAVAILABLE, "voice_question_unavailable");
    }
    String transcript =
        compiled.spans().stream().map(AudioTranscriptSpan::text).collect(Collectors.joining("\n"));
    byte[] text = transcript.getBytes(StandardCharsets.UTF_8);
    if (text.length > VoiceQuestionResult.MAX_TRANSCRIPT_BYTES) {
      throw problem(FailureKind.INVALID_INPUT, "voice_transcript_too_long");
    }
    check(started);
    VoiceQuestionResult result;
    try {
      result =
          new VoiceQuestionResult(
              transcript,
              ModelValues.sha256(text),
              compiled.sourceSha256(),
              compiled.decoderRevision(),
              compiled.modelRevision(),
              compiled.compilerRevision(),
              compiled.durationMs(),
              VoiceQuestionResult.POLICY_REVISION);
    } catch (RuntimeException unavailable) {
      throw problem(FailureKind.UNAVAILABLE, "voice_question_unavailable");
    }
    check(started);
    return result;
  }

  private boolean active(long started) {
    return !Thread.currentThread().isInterrupted()
        && System.nanoTime() - started < budgetNanos
        && profileCurrent();
  }

  private void check(long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw problem(FailureKind.INVALID_REQUEST, "voice_question_interrupted");
    }
    if (System.nanoTime() - started >= budgetNanos) {
      throw problem(FailureKind.TIMEOUT, "voice_question_timeout");
    }
    if (!profileCurrent()) {
      throw problem(FailureKind.CONFLICT, "voice_profile_changed");
    }
  }

  private boolean profileCurrent() {
    try {
      return compilerRevision.equals(audio.revision()) && audio.configurationCurrent();
    } catch (RuntimeException changed) {
      return false;
    }
  }

  private static ApplicationException mapped(String code) {
    return switch (code) {
      case "audio_no_speech" -> problem(FailureKind.INVALID_INPUT, "voice_no_speech");
      case "unsupported_document" -> problem(FailureKind.INVALID_INPUT, "voice_audio_invalid");
      case "audio_profile_changed" -> problem(FailureKind.CONFLICT, "voice_profile_changed");
      case "parser_timeout", "model_timeout" ->
          problem(FailureKind.TIMEOUT, "voice_question_timeout");
      case "parser_interrupted", "model_interrupted" ->
          problem(FailureKind.INVALID_REQUEST, "voice_question_interrupted");
      default -> problem(FailureKind.UNAVAILABLE, "voice_question_unavailable");
    };
  }

  private static ApplicationException problem(FailureKind kind, String code) {
    return new ApplicationException(kind, code, "语音问题处理未完成，请检查输入后重试。");
  }
}
