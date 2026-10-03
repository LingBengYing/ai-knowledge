package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AudioAnswerResult;
import com.evidence.rag.model.dto.QueryAnswerMode;
import com.evidence.rag.model.dto.QueryAttachmentCommand;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioVectorAnswerServiceTest {
  private static final String FACT_QUOTE = "星港项目的预算为47万元";

  @TempDir Path directory;

  @Test
  void allPcmRoutesRecallBoundLibraryTailThenProofAndRestartReadOriginalSource() {
    String answerId;
    byte[] original;
    try (var context = context()) {
      var audio =
          AudioTestFixture.publish(context, List.of("会议开始。", "", AudioVectorQueryFixture.FACT));
      original = audio.original();
      String text = context.publish("scope.txt", "未引用的完整范围。");
      var selection = DocumentSelection.selected(List.of(audio.documentId(), text));
      var scope = context.evidence.snapshot(context.owner, selection, context.target);
      var receipt = AudioVectorQueryFixture.publishVectors(context, scope, audio);
      var fixture = new AudioVectorQueryFixture();
      var models = new AudioVectorQueryFixture.Embeddings();
      var projection = new AudioVectorQueryFixture.Projection();
      projection.response =
          ignored ->
              List.of(
                  new RetrievalProjection.Candidate(
                      receipt.entries().getLast().vectorPhysicalSegmentId(), 0.8));
      try (var answers = answers(context, fixture.queries(context, models, projection))) {
        var result = attached(answers, context, fixture, selection);
        assertEquals("answered", result.status(), result.reason());
        answerId = result.answerId();
        assertEquals(3, models.received.size());
        assertEquals(1, fixture.decodes);
        assertEquals(3, fixture.asr.size());
        assertEquals(List.of("rerank", "extract"), context.models.calls);
        assertEquals(3, projection.queries.size());
        projection.queries.forEach(
            query -> {
              assertEquals(
                  Map.of(audio.documentId(), receipt.vectorGenerationId()),
                  query.scope().documentRevisions());
              assertEquals(RetrievalProjection.SearchMode.DENSE_ONLY, query.mode());
            });
        var citation = result.citations().getFirst();
        assertEquals(audio.documentId(), citation.documentId());
        assertEquals(2000, citation.startMs());
        assertEquals(3000, citation.endMs());
        assertEquals(FACT_QUOTE, citation.quote());
        assertArrayEquals(original, answers.audioContent(context.owner, answerId, 1).content());
        assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
        assertEquals(1, context.scalar("SELECT COUNT(*) FROM audio_trace_evidence"));
      }
    }
    try (var context = context()) {
      var fixture = new AudioVectorQueryFixture();
      var models = new AudioVectorQueryFixture.Embeddings();
      var projection = new AudioVectorQueryFixture.Projection();
      try (var answers = answers(context, fixture.queries(context, models, projection))) {
        assertArrayEquals(original, answers.audioContent(context.owner, answerId, 1).content());
        assertEquals(
            FACT_QUOTE, answers.audioSource(context.owner, answerId, 1).citation().quote());
        assertTrue(models.received.isEmpty());
        assertTrue(projection.queries.isEmpty());
        assertTrue(fixture.asr.isEmpty());
        assertTrue(context.models.calls.isEmpty());
      }
    }
  }

  @Test
  void missingUncitedAudioReceiptRefusesCompleteScopeBeforeOriginalSoundEmbedding() {
    try (var context = context()) {
      var first = AudioTestFixture.publish(context, List.of(AudioVectorQueryFixture.FACT));
      var second = AudioTestFixture.publish(context, List.of("另一份会议说明。"));
      var selection = DocumentSelection.selected(List.of(first.documentId(), second.documentId()));
      var scope = context.evidence.snapshot(context.owner, selection, context.target);
      AudioVectorQueryFixture.publishVectors(context, scope, first);
      var fixture = new AudioVectorQueryFixture();
      var models = new AudioVectorQueryFixture.Embeddings();
      var projection = new AudioVectorQueryFixture.Projection();
      try (var answers = answers(context, fixture.queries(context, models, projection))) {
        var result = attached(answers, context, fixture, selection);
        assertEquals("abstained", result.status());
        assertEquals("audio_vector_required", result.reason());
        assertEquals("请为当前范围的全部音频建立完整原声向量。", result.answer());
        assertTrue(result.citations().isEmpty());
        assertTrue(models.received.isEmpty());
        assertTrue(projection.queries.isEmpty());
        assertEquals(
            3, fixture.asr.size(), "Input preparation precedes the actual-waveform route decision");
        assertTrue(context.models.calls.isEmpty());
        assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_trace_evidence"));
      }
    }
  }

  @Test
  void audioReferenceInTextModeAndNoReferenceAudioKeepTheOriginalTextPath() {
    try (var context = context()) {
      String text = context.publish("budget.txt", AudioVectorQueryFixture.FACT);
      var audio = AudioTestFixture.publish(context, List.of(AudioVectorQueryFixture.FACT));
      var fixture = new AudioVectorQueryFixture();
      var models = new AudioVectorQueryFixture.Embeddings();
      var projection = new AudioVectorQueryFixture.Projection();
      try (var answers = answers(context, fixture.queries(context, models, projection))) {
        models.revision = "unused-drifted-audio-profile";
        var selectedText = DocumentSelection.selected(List.of(text));
        var textResult =
            answers.answerAttached(
                context.owner,
                new QueryAttachmentCommand(
                    new AnswerCommand(AudioVectorQueryFixture.QUESTION, selectedText),
                    QueryAnswerMode.TEXT,
                    List.of(fixture.attachment())));
        assertEquals("answered", textResult.result().status());
        var noReference =
            answers.answerAudio(
                context.owner,
                new AnswerCommand(
                    AudioVectorQueryFixture.QUESTION,
                    DocumentSelection.selected(List.of(audio.documentId()))));
        assertEquals("answered", noReference.status(), noReference.reason());
        assertTrue(models.received.isEmpty());
        assertTrue(projection.queries.isEmpty());
        assertEquals(
            List.of("embed", "rerank", "extract", "embed", "rerank", "extract"),
            context.models.calls);
      }
    }
  }

  @Test
  void uncitedAclOrModelProfileChangeAfterEmbeddingStopsRecallAndLeavesNoAudioCitation() {
    for (boolean revoke : List.of(true, false)) {
      try (var context =
          new AnswerTestContext(
              directory.resolve(Boolean.toString(revoke)), AudioVectorQueryFixture.BUDGET, 1)) {
        var audio = AudioTestFixture.publish(context, List.of(AudioVectorQueryFixture.FACT));
        String text = context.publish("uncited.txt", "完整范围。");
        var selected = DocumentSelection.selected(List.of(audio.documentId(), text));
        var scope = context.evidence.snapshot(context.owner, selected, context.target);
        AudioVectorQueryFixture.publishVectors(context, scope, audio);
        var fixture = new AudioVectorQueryFixture();
        var models = new AudioVectorQueryFixture.Embeddings();
        var projection = new AudioVectorQueryFixture.Projection();
        models.after =
            revoke ? () -> context.revoke(text) : () -> models.revision = "changed-audio-profile";
        try (var answers = answers(context, fixture.queries(context, models, projection))) {
          var result = attached(answers, context, fixture, selected);
          assertEquals("abstained", result.status());
          assertEquals(revoke ? "scope_changed" : "configuration_changed", result.reason());
          assertEquals(1, models.received.size());
          assertTrue(projection.queries.isEmpty());
          assertTrue(context.models.calls.isEmpty());
          assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_trace_evidence"));
        }
      }
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, AudioVectorQueryFixture.BUDGET, 1);
  }

  private static AnswerService answers(AnswerTestContext context, QueryAttachmentService queries) {
    return new AnswerService(
        context.evidence,
        context.models,
        context.projection,
        context.target,
        AudioVectorQueryFixture.BUDGET,
        1,
        null,
        queries);
  }

  private static AudioAnswerResult attached(
      AnswerService service,
      AnswerTestContext context,
      AudioVectorQueryFixture fixture,
      DocumentSelection selection) {
    return (AudioAnswerResult)
        service
            .answerAttached(
                context.owner,
                new QueryAttachmentCommand(
                    new AnswerCommand(AudioVectorQueryFixture.QUESTION, selection),
                    QueryAnswerMode.AUDIO,
                    List.of(fixture.attachment())))
            .result();
  }
}
