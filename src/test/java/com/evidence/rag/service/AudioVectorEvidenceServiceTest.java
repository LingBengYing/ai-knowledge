package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.AudioTraceEvidence;
import com.evidence.rag.model.domain.AudioVectorScope;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublishedAudioEvidence;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioVectorEvidenceServiceTest {
  @TempDir Path directory;

  @Test
  void allAudioReceiptsAreRequiredAndEverySpanMapsToOriginalAuthorityWithSilentOrdinalGaps() {
    try (var context = context()) {
      var first =
          AudioTestFixture.publish(context, List.of("会议开始。", "", AudioVectorQueryFixture.FACT));
      var second = AudioTestFixture.publish(context, List.of("其他说明。"));
      String text = context.publish("scope.txt", "未引用资料也在范围内。");
      var scope = scope(context, first.documentId(), second.documentId(), text);
      var a = AudioVectorQueryFixture.publishVectors(context, scope, first);
      assertEquals(
          "audio_vector_required",
          assertThrows(ApplicationException.class, () -> vectors(context, scope)).code());
      var b = AudioVectorQueryFixture.publishVectors(context, scope, second);
      var vectors = vectors(context, scope);
      assertEquals(3, vectors.base().publications().size());
      assertEquals(2, vectors.publications().size());
      assertEquals(List.of(0, 2), a.entries().stream().map(entry -> entry.ordinal()).toList());
      assertEquals(
          List.of(
              b.entries().getFirst().basePhysicalSegmentId(),
              a.entries().getLast().basePhysicalSegmentId()),
          context
              .evidence
              .hydrateAudioVectors(
                  vectors,
                  List.of(
                      b.entries().getFirst().vectorPhysicalSegmentId(),
                      a.entries().getLast().vectorPhysicalSegmentId()))
              .stream()
              .map(PublishedAudioEvidence::physicalSegmentId)
              .toList());
      assertThrows(
          ApplicationException.class,
          () -> context.evidence.hydrateAudioVectors(vectors, List.of("foreign-tail")));
      var incomplete =
          new AudioVectorScope(
              scope, AudioVectorQueryFixture.TARGET, AudioVectorQueryFixture.DECODER, List.of(a));
      assertEquals(
          "audio_vector_required",
          assertThrows(
                  ApplicationException.class,
                  () -> context.evidence.hydrateAudioVectors(incomplete, List.of()))
              .code());
      var denied =
          context.evidence.finish(
              scope,
              draft(context.evidence.hydrateAudio(scope, first.physicalIds()).getLast()),
              () -> AnswerEligibility.ELIGIBLE,
              null,
              incomplete);
      assertEquals("audio_vector_required", denied.reasonCode());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_trace_evidence"));
      assertEquals(3, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(
          2,
          context.evidence.hydrateAudio(scope, first.physicalIds()).size(),
          "Old text audio contract remains available");
    }
  }

  @Test
  void fullReceiptAndOriginalSourceSurviveRestartWithoutModelCalls() {
    AudioTestFixture.Published audio;
    String trace;
    String generation;
    try (var context = context()) {
      audio = AudioTestFixture.publish(context, List.of("", AudioVectorQueryFixture.FACT));
      var scope = scope(context, audio.documentId());
      var saved = AudioVectorQueryFixture.publishVectors(context, scope, audio);
      generation = saved.vectorGenerationId();
      var vectors = vectors(context, scope);
      var material =
          context
              .evidence
              .hydrateAudioVectors(
                  vectors, List.of(saved.entries().getFirst().vectorPhysicalSegmentId()))
              .getFirst();
      var result =
          context.evidence.finish(
              scope, draft(material), () -> AnswerEligibility.ELIGIBLE, null, vectors);
      assertEquals("answered", result.outcome());
      trace = result.traceId();
    }
    try (var context = context()) {
      var vectors = vectors(context, scope(context, audio.documentId()));
      assertEquals(generation, vectors.publications().getFirst().vectorGenerationId());
      var source = context.evidence.audioSource(context.owner, trace, 1);
      assertEquals(1000, source.startMs());
      assertEquals(2000, source.endMs());
      assertArrayEquals(audio.original(), source.audio().content());
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @Test
  void profileDecoderScopeMismatchAndUncitedRevocationCannotSealAudioEvidence() {
    try (var context = context()) {
      var audio = AudioTestFixture.publish(context, List.of(AudioVectorQueryFixture.FACT));
      String uncited = context.publish("uncited.txt", "完整范围。");
      var scope = scope(context, audio.documentId(), uncited);
      var saved = AudioVectorQueryFixture.publishVectors(context, scope, audio);
      var vectors = vectors(context, scope);
      var drifted = new IndexTarget("audio-profile-v2", "f".repeat(64), "audio-profile-v2", 2);
      assertEquals(
          "audio_vector_required",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      context.evidence.audioVectorScope(
                          scope, drifted, AudioVectorQueryFixture.DECODER))
              .code());
      assertEquals(
          "audio_vector_required",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      context.evidence.audioVectorScope(
                          scope, AudioVectorQueryFixture.TARGET, "decoder-v2"))
              .code());
      assertThrows(
          ApplicationException.class,
          () ->
              new AudioVectorScope(
                  scope, drifted, AudioVectorQueryFixture.DECODER, List.of(saved)));
      assertThrows(
          ApplicationException.class,
          () ->
              new AudioVectorScope(
                  scope, AudioVectorQueryFixture.TARGET, "decoder-v2", List.of(saved)));
      assertThrows(
          ApplicationException.class,
          () ->
              new AudioVectorScope(
                  scope,
                  AudioVectorQueryFixture.TARGET,
                  AudioVectorQueryFixture.DECODER,
                  List.of(saved, saved)));
      var single = scope(context, audio.documentId());
      var material = context.evidence.hydrateAudio(scope, audio.physicalIds()).getFirst();
      assertThrows(
          ApplicationException.class,
          () ->
              context.evidence.finish(
                  single, draft(material), () -> AnswerEligibility.ELIGIBLE, null, vectors));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_traces"));
      context.revoke(uncited);
      assertEquals(
          "scope_changed",
          assertThrows(
                  ApplicationException.class,
                  () -> context.evidence.hydrateAudioVectors(vectors, List.of()))
              .code());
      var trace =
          context.evidence.finish(
              scope, draft(material), () -> AnswerEligibility.ELIGIBLE, null, vectors);
      assertEquals("scope_changed", trace.reasonCode());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_trace_evidence"));
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, AudioVectorQueryFixture.BUDGET, 1);
  }

  private static EvidenceScope scope(AnswerTestContext context, String... ids) {
    return context.evidence.snapshot(
        context.owner, DocumentSelection.selected(List.of(ids)), context.target);
  }

  private static AudioVectorScope vectors(AnswerTestContext context, EvidenceScope scope) {
    return context.evidence.audioVectorScope(
        scope, AudioVectorQueryFixture.TARGET, AudioVectorQueryFixture.DECODER);
  }

  private static TraceDraft draft(PublishedAudioEvidence source) {
    return new TraceDraft(
        hash(AudioVectorQueryFixture.QUESTION),
        hash(AudioVectorQueryFixture.FACT),
        "answered",
        null,
        "test-answer-model-v1",
        "test-audio-prompt-v1",
        "test-audio-policy-v1",
        List.of(),
        List.of(),
        List.of(
            new AudioTraceEvidence(
                1,
                source.physicalSegmentId(),
                source.transcript().startCodePoint(),
                source.transcript().endCodePoint(),
                1.0,
                1.0,
                List.of(hash("预算")))));
  }

  private static String hash(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
