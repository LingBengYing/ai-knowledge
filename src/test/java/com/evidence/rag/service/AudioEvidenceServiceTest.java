package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.AudioTraceEvidence;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioEvidenceServiceTest {
  private static final List<String> TEXTS = List.of("😀会议背景。", "\n", "星港项目的预算为42元。");
  @TempDir Path directory;

  @Test
  void audioHydrationAndSavedSourcePreserveWholeTranscriptAndOriginalSpanTime() {
    try (var context = context()) {
      String text = context.publish("plan.txt", "这是文字资料。");
      var audio = AudioTestFixture.publish(context, TEXTS);
      var scope = scope(context, List.of(text, audio.documentId()));
      assertEquals(2, scope.publications().size());
      assertEquals(
          List.of(audio.documentId()),
          context.evidence.audioPublications(scope).stream().map(p -> p.documentId()).toList());
      var material = context.evidence.hydrateAudio(scope, audio.physicalIds());
      assertEquals(2, material.size());
      assertEquals(String.join("\n", TEXTS), material.getLast().transcript().contextText());
      assertEquals(2, material.getLast().span().ordinal());
      var receipt =
          context.evidence.finish(
              scope,
              trace(
                  material.getLast().physicalSegmentId(),
                  material.getLast().transcript().startCodePoint(),
                  material.getLast().transcript().endCodePoint()),
              () -> AnswerEligibility.ELIGIBLE);
      var source = context.evidence.audioSource(context.owner, receipt.traceId(), 1);
      assertEquals(TEXTS.getLast(), source.quote());
      assertEquals(2000, source.startMs());
      assertEquals(3000, source.endMs());
      assertArrayEquals(audio.original(), source.audio().content());
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM audio_trace_evidence"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
      var foreign = new Actor(context.owner.workspaceId(), "reader");
      assertEquals(
          "not_found",
          assertThrows(
                  ApplicationException.class,
                  () -> context.evidence.audioSource(foreign, receipt.traceId(), 1))
              .code());
    }
  }

  @Test
  void sourceRevocationChecksTheUncitedSelectedDocumentToo() {
    try (var context = context()) {
      String uncited = context.publish("plan.txt", "这是文字资料。");
      var audio = AudioTestFixture.publish(context, TEXTS);
      var scope = scope(context, List.of(uncited, audio.documentId()));
      var material = context.evidence.hydrateAudio(scope, audio.physicalIds()).getLast();
      var receipt =
          context.evidence.finish(
              scope,
              trace(
                  material.physicalSegmentId(),
                  material.transcript().startCodePoint(),
                  material.transcript().endCodePoint()),
              () -> AnswerEligibility.ELIGIBLE);
      context.revoke(uncited);
      assertThrows(
          ApplicationException.class,
          () -> context.evidence.audioSource(context.owner, receipt.traceId(), 1));
      assertThrows(
          ApplicationException.class,
          () -> context.evidence.hydrateAudio(scope, audio.physicalIds()));
      assertThrows(ApplicationException.class, () -> context.evidence.audioPublications(scope));
    }
  }

  @Test
  void lastEligibilityFailureClearsAudioChildrenBeforeSealingRefusal() {
    try (var context = context()) {
      var audio = AudioTestFixture.publish(context, TEXTS);
      var scope = scope(context, List.of(audio.documentId()));
      var material = context.evidence.hydrateAudio(scope, audio.physicalIds()).getLast();
      var checks = new AtomicInteger();
      var receipt =
          context.evidence.finish(
              scope,
              trace(
                  material.physicalSegmentId(),
                  material.transcript().startCodePoint(),
                  material.transcript().endCodePoint()),
              () ->
                  checks.getAndIncrement() == 0
                      ? AnswerEligibility.ELIGIBLE
                      : AnswerEligibility.PROCESSING_TIMEOUT);
      assertEquals("abstained", receipt.outcome());
      assertEquals("processing_timeout", receipt.reasonCode());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_trace_evidence"));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @Test
  void wrongSpanCodePointRangeCannotCreateASealedAudioCitation() {
    try (var context = context()) {
      var audio = AudioTestFixture.publish(context, TEXTS);
      var scope = scope(context, List.of(audio.documentId()));
      assertThrows(
          ApplicationException.class,
          () ->
              context.evidence.finish(
                  scope,
                  trace(audio.physicalIds().getLast(), 0, 2),
                  () -> AnswerEligibility.ELIGIBLE));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_traces"));
      assertTrue(context.evidence.hydrateAudio(scope, List.of()).isEmpty());
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
  }

  private static EvidenceScope scope(AnswerTestContext context, List<String> documents) {
    return context.evidence.snapshot(
        context.owner, DocumentSelection.selected(documents), context.target);
  }

  private static TraceDraft trace(String physicalId, int start, int end) {
    return new TraceDraft(
        sha("合成问题"),
        sha(TEXTS.getLast()),
        "answered",
        null,
        "test-answer-model-v1",
        "test-audio-prompt-v1",
        "test-audio-policy-v1",
        List.of(),
        List.of(),
        List.of(new AudioTraceEvidence(1, physicalId, start, end, 1.0, 1.0, List.of(sha("预算")))));
  }

  private static String sha(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
