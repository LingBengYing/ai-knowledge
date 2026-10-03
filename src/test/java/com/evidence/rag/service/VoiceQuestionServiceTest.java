package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.dto.VoiceQuestionCommand;
import com.evidence.rag.model.dto.VoiceQuestionResult;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/** Complete temporary user input only: no authority, task, retrieval or projection fixture. */
class VoiceQuestionServiceTest {
  private static final Actor ACTOR = new Actor("org-main", "reader");
  private static final byte[] SOURCE = AudioPcm.wav(new byte[] {1, 2}, 0, 2);
  private static final VoiceQuestionCommand COMMAND =
      new VoiceQuestionCommand(new QueryAttachment("question.wav", "audio/wav", SOURCE));

  @Test
  void allOrdinalTextIncludingEmptyMiddleAndTailIsJoinedExactlyWithLf() {
    var fixture = fixture("  Project Car code is 7,3,9,21.\r\n", "", "尾部😀  ", "");
    var result = fixture.service.transcribe(ACTOR, COMMAND);
    String text = "  Project Car code is 7,3,9,21.\r\n\n\n尾部😀  \n";
    assertEquals(text, result.transcript());
    assertEquals(
        ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8)), result.transcriptSha256());
    assertEquals(ModelValues.sha256(SOURCE), result.sourceSha256());
    assertEquals("decoder-v1", result.decoderRevision());
    assertEquals("asr-v1", result.modelRevision());
    assertEquals(fixture.compilation.revision(), result.compilerRevision());
    assertEquals(4000, result.durationMs());
    assertEquals(VoiceQuestionResult.POLICY_REVISION, result.policyRevision());
    assertEquals(4, fixture.models.calls);
    assertEquals(1, fixture.decoder.calls);
  }

  @Test
  void previewOverTheOldQuestionLimitRemainsCompleteForExplicitUserEditing() {
    var fixture = fixture("a".repeat(4096), "尾部");
    var result = fixture.service.transcribe(ACTOR, COMMAND);
    assertEquals("a".repeat(4096) + "\n尾部", result.transcript());
    assertTrue(result.transcript().getBytes(StandardCharsets.UTF_8).length > 4096);
  }

  @Test
  void exact65536Utf8BytesAreAcceptedAndTheNextByteFailsWithoutPartialText() {
    var text = new ArrayList<String>();
    for (int i = 0; i < 5; i++) {
      text.add("中".repeat(4096));
    }
    text.add("中".repeat(1363) + "ab");
    var valid = fixture(text.toArray(String[]::new));
    var result = valid.service.transcribe(ACTOR, COMMAND);
    assertEquals(65536, result.transcript().getBytes(StandardCharsets.UTF_8).length);
    assertEquals(String.join("\n", text), result.transcript());
    text.set(5, text.getLast() + "c");
    var excessive = fixture(text.toArray(String[]::new));
    assertProblem(
        "voice_transcript_too_long",
        FailureKind.INVALID_INPUT,
        () -> excessive.service.transcribe(ACTOR, COMMAND));
    assertEquals(6, excessive.models.calls);
  }

  @Test
  void emptyRecognizedTrackFailsSafelyWithoutClaimingVadOrReturningAQuestion() {
    var fixture = fixture("", "\n");
    assertProblem(
        "voice_no_speech",
        FailureKind.INVALID_INPUT,
        () -> fixture.service.transcribe(ACTOR, COMMAND));
    assertEquals(2, fixture.models.calls);
  }

  @Test
  void invalidAudioEnvelopeAndMissingActorOrCommandDoNoDecoderOrModelWork() {
    var fixture = fixture("question");
    var invalid =
        new VoiceQuestionCommand(
            new QueryAttachment("question.wav", "audio/wav", new byte[] {1, 2, 3}));
    assertProblem(
        "voice_audio_invalid",
        FailureKind.INVALID_INPUT,
        () -> fixture.service.transcribe(ACTOR, invalid));
    assertProblem(
        "voice_audio_invalid",
        FailureKind.INVALID_INPUT,
        () -> fixture.service.transcribe(null, COMMAND));
    assertProblem(
        "voice_audio_invalid",
        FailureKind.INVALID_INPUT,
        () -> fixture.service.transcribe(ACTOR, null));
    assertEquals(0, fixture.decoder.calls);
    assertEquals(0, fixture.models.calls);
  }

  @Test
  void decoderFailuresUseOnlyTheSpecifiedSafeCodesAndKinds() {
    for (var failure :
        List.of(
            new Expected("unsupported_document", "voice_audio_invalid", FailureKind.INVALID_INPUT),
            new Expected("parser_timeout", "voice_question_timeout", FailureKind.TIMEOUT),
            new Expected(
                "parser_interrupted", "voice_question_interrupted", FailureKind.INVALID_REQUEST),
            new Expected(
                "parser_invalid_output", "voice_question_unavailable", FailureKind.UNAVAILABLE),
            new Expected(
                "parser_cancelled", "voice_question_unavailable", FailureKind.UNAVAILABLE))) {
      var fixture = fixture("question");
      fixture.decoder.failure = new TextParser.Failure(failure.original);
      assertProblem(failure.code, failure.kind, () -> fixture.service.transcribe(ACTOR, COMMAND));
      assertEquals(0, fixture.models.calls);
    }
  }

  @Test
  void failedAsrTailCannotReturnEarlierTextAndIsNeverRetried() {
    var fixture = fixture("first", "tail");
    fixture.models.failAt = 1;
    fixture.models.failure = new TextModels.Failure("model_http_failed");
    assertProblem(
        "voice_question_unavailable",
        FailureKind.UNAVAILABLE,
        () -> fixture.service.transcribe(ACTOR, COMMAND));
    assertEquals(2, fixture.models.calls);
  }

  @Test
  void upstreamTimeoutAndInterruptionRetainTheirSafeTransportCategories() {
    for (var failure :
        List.of(
            new Expected("model_timeout", "voice_question_timeout", FailureKind.TIMEOUT),
            new Expected(
                "model_interrupted", "voice_question_interrupted", FailureKind.INVALID_REQUEST))) {
      var fixture = fixture("question");
      fixture.models.failAt = 0;
      fixture.models.failure = new TextModels.Failure(failure.original);
      assertProblem(failure.code, failure.kind, () -> fixture.service.transcribe(ACTOR, COMMAND));
      assertEquals(1, fixture.models.calls);
    }
  }

  @Test
  void changedProfileBeforeDecodeDoesNoWorkAndMidAsrChangeStopsTheNextChunk() {
    var before = fixture("first");
    before.decoder.revision = "decoder-v2";
    assertProblem(
        "voice_profile_changed",
        FailureKind.CONFLICT,
        () -> before.service.transcribe(ACTOR, COMMAND));
    assertEquals(0, before.decoder.calls);
    assertEquals(0, before.models.calls);
    var during = fixture("first", "tail");
    during.models.after = () -> during.models.revision = "asr-v2";
    assertProblem(
        "voice_profile_changed",
        FailureKind.CONFLICT,
        () -> during.service.transcribe(ACTOR, COMMAND));
    assertEquals(1, during.models.calls);
  }

  @Test
  void requestBudgetExpiryAfterDecodeStopsBeforeTheFirstAsrCall() {
    var fixture = fixture(Duration.ofMillis(20), "question");
    fixture.decoder.after =
        () -> {
          long deadline = System.nanoTime() + Duration.ofMillis(60).toNanos();
          while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
          }
        };
    assertProblem(
        "voice_question_timeout",
        FailureKind.TIMEOUT,
        () -> fixture.service.transcribe(ACTOR, COMMAND));
    assertEquals(1, fixture.decoder.calls);
    assertEquals(0, fixture.models.calls);
  }

  @Test
  void threadInterruptionBeforeDecodeOrAfterAsrPreservesTheFlagAndNeverReturnsPartialText() {
    var before = fixture("question");
    try {
      Thread.currentThread().interrupt();
      assertProblem(
          "voice_question_interrupted",
          FailureKind.INVALID_REQUEST,
          () -> before.service.transcribe(ACTOR, COMMAND));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(0, before.decoder.calls);
    } finally {
      Thread.interrupted();
    }
    var during = fixture("first", "tail");
    during.models.after = () -> Thread.currentThread().interrupt();
    try {
      assertProblem(
          "voice_question_interrupted",
          FailureKind.INVALID_REQUEST,
          () -> during.service.transcribe(ACTOR, COMMAND));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, during.models.calls);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void untrustedUnexpectedExceptionsCannotExposePrivateProviderMessagesOrCauses() {
    var fixture = fixture("question");
    fixture.decoder.failure = new IllegalStateException("private-provider-message");
    var problem =
        assertProblem(
            "voice_question_unavailable",
            FailureKind.UNAVAILABLE,
            () -> fixture.service.transcribe(ACTOR, COMMAND));
    assertFalse(problem.toString().contains("private-provider-message"));
    assertEquals(null, problem.getCause());
    assertEquals(0, fixture.models.calls);
  }

  @Test
  void processingBudgetCannotExceedTheFrozenTransportContract() {
    var fixture = fixture("question");
    for (Duration duration :
        List.of(Duration.ZERO, Duration.ofMillis(9), Duration.ofMillis(120001))) {
      assertThrows(
          ApplicationException.class,
          () -> new VoiceQuestionService(fixture.compilation, duration));
    }
    assertThrows(
        ApplicationException.class, () -> new VoiceQuestionService(null, Duration.ofSeconds(1)));
    assertThrows(
        ApplicationException.class, () -> new VoiceQuestionService(fixture.compilation, null));
  }

  private static Fixture fixture(String... text) {
    return fixture(Duration.ofSeconds(5), text);
  }

  private static Fixture fixture(Duration budget, String... text) {
    var decoder = new Decoder(text.length * DecodedAudio.BYTES_PER_SECOND);
    var models = new Models(text);
    var compilation = new AudioCompilationService(decoder, models, 1, Duration.ofSeconds(5));
    return new Fixture(decoder, models, compilation, new VoiceQuestionService(compilation, budget));
  }

  private static ApplicationException assertProblem(String code, FailureKind kind, Runnable work) {
    var problem = assertThrows(ApplicationException.class, work::run);
    assertEquals(code, problem.code());
    assertEquals(kind, problem.kind());
    return problem;
  }

  private record Expected(String original, String code, FailureKind kind) {}

  private record Fixture(
      Decoder decoder,
      Models models,
      AudioCompilationService compilation,
      VoiceQuestionService service) {}

  private static final class Decoder implements AudioDecoder {
    private final byte[] pcm;
    private int calls;
    private String revision = "decoder-v1";
    private RuntimeException failure;
    private Runnable after = () -> {};

    Decoder(int size) {
      pcm = new byte[size];
    }

    @Override
    public String revision() {
      return revision;
    }

    @Override
    public DecodedAudio decode(String filename, String mime, byte[] source) {
      calls++;
      if (failure != null) {
        throw failure;
      }
      after.run();
      return new DecodedAudio(ModelValues.sha256(source), revision, pcm);
    }

    @Override
    public void close() {}
  }

  private static final class Models implements AudioModels {
    private final List<String> text;
    private int calls;
    private int failAt = -1;
    private RuntimeException failure;
    private String revision = "asr-v1";
    private Runnable after = () -> {};

    Models(String... text) {
      this.text = List.of(text);
    }

    @Override
    public String revision() {
      return revision;
    }

    @Override
    public Transcript transcribe(byte[] wav) {
      int ordinal = calls++;
      if (ordinal == failAt) {
        throw failure;
      }
      after.run();
      return new Transcript(text.get(ordinal));
    }

    @Override
    public void close() {}
  }
}
