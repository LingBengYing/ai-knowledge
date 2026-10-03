package com.evidence.rag.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VoiceQuestionDtoTest {
  private static final String SOURCE_SHA = "a".repeat(64);

  @Test
  void inputAcceptsOneAudioOriginalWithDefensiveBytesAndRedactedDiagnostics() {
    byte[] bytes = {1, 2};
    var audio = new QueryAttachment("private-question.wav", "audio/wav", bytes);
    var command = new VoiceQuestionCommand(audio);
    bytes[0] = 9;
    command.audio().content()[0] = 8;
    assertEquals(1, command.audio().content()[0]);
    assertEquals(audio, command.audio());
    assertEquals("VoiceQuestionCommand[redacted]", command.toString());
    for (var item :
        List.of(
            new QueryAttachment("q.mp3", "audio/mpeg", new byte[] {1}),
            new QueryAttachment("q.flac", "audio/flac", new byte[] {1}),
            new QueryAttachment("q.ogg", "audio/ogg", new byte[] {1}),
            new QueryAttachment("q.m4a", "audio/mp4", new byte[] {1}),
            new QueryAttachment("q.webm", "audio/webm", new byte[] {1}))) {
      assertEquals(item, new VoiceQuestionCommand(item).audio());
    }
    assertThrows(ApplicationException.class, () -> new VoiceQuestionCommand(null));
    assertThrows(
        ApplicationException.class,
        () -> new VoiceQuestionCommand(new QueryAttachment("q.png", "image/png", new byte[] {1})));
    assertThrows(
        ApplicationException.class,
        () -> new VoiceQuestionCommand(new QueryAttachment("q.mp4", "video/mp4", new byte[] {1})));
    assertThrows(
        ApplicationException.class,
        () ->
            new VoiceQuestionCommand(
                new QueryAttachment("q.wav", "audio/wav", new byte[20 * 1024 * 1024 + 1])));
  }

  @Test
  void fullPreviewPreservesWhitespaceNumericSequenceAndTheSeparateLargeUtf8Budget() {
    String text = "  launch code is 7,3,9,21.\r\n\n尾部😀  ";
    var result = result(text);
    assertEquals(text, result.transcript());
    assertEquals(
        ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8)), result.transcriptSha256());
    assertEquals(SOURCE_SHA, result.sourceSha256());
    assertEquals("decoder-v1", result.decoderRevision());
    assertEquals("asr-v1", result.modelRevision());
    assertEquals("compiler-v1", result.compilerRevision());
    assertEquals(600_000, result.durationMs());
    assertEquals("java-voice-question-v1", result.policyRevision());
    assertEquals(65536, VoiceQuestionResult.MAX_TRANSCRIPT_BYTES);
    String maximum = "😀".repeat(16384);
    assertEquals(maximum, result(maximum).transcript());
    assertThrows(
        ApplicationException.class,
        () -> new AnswerCommand(maximum, DocumentSelection.allDocuments()));
    assertEquals("VoiceQuestionResult[redacted]", result.toString());
  }

  @Test
  void resultCannotInventHashesVersionsDurationOrExposeMalformedPartialText() {
    for (String text :
        Arrays.asList(null, "", "\n\t ", "x\u0000y", "\ud800", "😀".repeat(16384) + "x")) {
      assertThrows(ApplicationException.class, () -> result(text));
    }
    String text = "question";
    String hash = ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
    assertThrows(
        ApplicationException.class,
        () ->
            new VoiceQuestionResult(
                text,
                SOURCE_SHA,
                SOURCE_SHA,
                "decoder-v1",
                "asr-v1",
                "compiler-v1",
                1,
                VoiceQuestionResult.POLICY_REVISION));
    assertThrows(
        ApplicationException.class,
        () ->
            new VoiceQuestionResult(
                text,
                hash,
                "A".repeat(64),
                "decoder-v1",
                "asr-v1",
                "compiler-v1",
                1,
                VoiceQuestionResult.POLICY_REVISION));
    assertThrows(
        ApplicationException.class,
        () ->
            new VoiceQuestionResult(
                text,
                hash,
                SOURCE_SHA,
                "",
                "asr-v1",
                "compiler-v1",
                1,
                VoiceQuestionResult.POLICY_REVISION));
    assertThrows(
        ApplicationException.class,
        () ->
            new VoiceQuestionResult(
                text,
                hash,
                SOURCE_SHA,
                "decoder-v1",
                "x".repeat(201),
                "compiler-v1",
                1,
                VoiceQuestionResult.POLICY_REVISION));
    assertThrows(
        ApplicationException.class,
        () ->
            new VoiceQuestionResult(
                text,
                hash,
                SOURCE_SHA,
                "decoder-v1",
                "asr-v1",
                "",
                1,
                VoiceQuestionResult.POLICY_REVISION));
    assertThrows(
        ApplicationException.class,
        () ->
            new VoiceQuestionResult(
                text,
                hash,
                SOURCE_SHA,
                "decoder-v1",
                "asr-v1",
                "compiler-v1",
                0,
                VoiceQuestionResult.POLICY_REVISION));
    assertThrows(
        ApplicationException.class,
        () ->
            new VoiceQuestionResult(
                text,
                hash,
                SOURCE_SHA,
                "decoder-v1",
                "asr-v1",
                "compiler-v1",
                600_001,
                VoiceQuestionResult.POLICY_REVISION));
    assertThrows(
        ApplicationException.class,
        () ->
            new VoiceQuestionResult(
                text, hash, SOURCE_SHA, "decoder-v1", "asr-v1", "compiler-v1", 1, "other-policy"));
  }

  private static VoiceQuestionResult result(String text) {
    return new VoiceQuestionResult(
        text,
        text == null ? SOURCE_SHA : ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8)),
        SOURCE_SHA,
        "decoder-v1",
        "asr-v1",
        "compiler-v1",
        600_000,
        VoiceQuestionResult.POLICY_REVISION);
  }
}
