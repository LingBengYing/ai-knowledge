package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AudioCitationResult;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real audio authority and shared text proof; synthetic ASR does not claim recognition quality. */
class AudioAnswerServiceTest {
  private static final String CODE = "星港项目的识别码为A-42。";
  private static final String BUDGET = "星港项目的预算为47万元。";
  private static final String QUESTION = "星港项目的识别码是什么？星港项目的预算是多少？";

  @TempDir Path directory;

  @ParameterizedTest(name = "allDocuments={0}")
  @ValueSource(booleans = {true, false})
  void answersAudioFactsWithRealSpanCitationsAndSameVersionSource(boolean allDocuments) {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String textDocument = context.publish("other.txt", "旁支项目的识别码为B-7。");
      var audio = AudioTestFixture.publish(context, List.of(CODE, "\n", BUDGET));
      var selection =
          allDocuments
              ? DocumentSelection.allDocuments()
              : DocumentSelection.selected(List.of(textDocument, audio.documentId()));
      assertEquals(
          2,
          context
              .evidence
              .snapshot(context.owner, selection, context.target)
              .publications()
              .size());

      var answer =
          context.answers.answerAudio(context.owner, new AnswerCommand(QUESTION, selection));

      assertEquals("answered", answer.status(), answer.reason());
      assertTrue(answer.answer().contains("A-42"));
      assertTrue(answer.answer().contains("47万元"));
      assertFalse(answer.answer().contains("B-7"));
      assertEquals(
          Set.of(audio.documentId()), context.projection.lastScope.documentRevisions().keySet());
      assertEquals(
          Set.of(CODE, BUDGET),
          Set.copyOf(context.models.lastEvidence.stream().map(value -> value.text()).toList()));
      assertEquals(List.of("embed", "rerank", "extract"), context.models.calls);
      assertEquals(2, answer.citations().size());
      var byTime =
          answer.citations().stream()
              .sorted(Comparator.comparingLong(AudioCitationResult::startMs))
              .toList();
      assertEquals(List.of(0L, 2000L), byTime.stream().map(AudioCitationResult::startMs).toList());
      assertEquals(List.of(1000L, 3000L), byTime.stream().map(AudioCitationResult::endMs).toList());
      for (var citation : answer.citations()) {
        assertEquals("audio_span", citation.kind());
        assertEquals(audio.documentId(), citation.documentId());
        assertEquals(audio.revisionId(), citation.revisionId());
        assertEquals(ModelValues.sha256(audio.original()), citation.sourceSha256());
        assertEquals(AudioTestFixture.COMPILER, citation.parserRevision());
        assertEquals("meeting.wav", citation.filename());
        assertEquals("audio/wav", citation.mediaType());
        assertEquals("machine_asr", citation.textOrigin());
        assertEquals("server_chunk", citation.timePrecision());
        assertEquals(
            ModelValues.sha256(citation.quote().getBytes(StandardCharsets.UTF_8)),
            citation.quoteSha256());
        assertEquals(
            "/v1/audio-sources/" + answer.answerId() + "/" + citation.number(),
            citation.sourceUrl());
        assertEquals(citation.sourceUrl() + "/content", citation.contentUrl());
        assertEquals(
            citation,
            context
                .answers
                .audioSource(context.owner, answer.answerId(), citation.number())
                .citation());
        var content =
            context.answers.audioContent(context.owner, answer.answerId(), citation.number());
        assertEquals("audio/wav", content.mimeType());
        assertArrayEquals(audio.original(), content.content());
      }
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
    }
  }

  @Test
  void nonCandidateTranscriptTailContradictionPreventsAudioAnswer() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      var audio = AudioTestFixture.publish(context, List.of("项目预算为470万元。", "", "项目预算为480万元。"));
      context.projection.results =
          candidates ->
              candidates.stream()
                  .filter(candidate -> candidate.segmentId().equals(audio.physicalIds().getFirst()))
                  .toList();

      var answer =
          context.answers.answerAudio(
              context.owner, new AnswerCommand("项目预算是多少？", DocumentSelection.allDocuments()));

      assertEquals("abstained", answer.status());
      assertEquals("conflicting_evidence", answer.reason());
      assertTrue(answer.citations().isEmpty());
      assertEquals(
          List.of("项目预算为470万元。"),
          context.models.lastEvidence.stream().map(value -> value.text()).toList());
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
    }
  }

  @Test
  void nonCandidateTextRevocationDuringAudioExtractionPreservesCompleteScopeTrace() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String textDocument = context.publish("other.txt", "旁支项目的识别码为B-7。");
      var audio = AudioTestFixture.publish(context, List.of(CODE, "", BUDGET));
      context.models.onExtract = () -> context.revoke(textDocument);

      var answer =
          context.answers.answerAudio(
              context.owner,
              new AnswerCommand(
                  QUESTION, DocumentSelection.selected(List.of(textDocument, audio.documentId()))));

      assertEquals("abstained", answer.status());
      assertEquals("scope_changed", answer.reason());
      assertFalse(answer.answer().contains("A-42"));
      assertTrue(answer.citations().isEmpty());
      assertEquals(
          Set.of(audio.documentId()), context.projection.lastScope.documentRevisions().keySet());
      assertEquals(List.of("embed", "rerank", "extract"), context.models.calls);
      assertEquals(
          1, context.scalar("SELECT COUNT(*) FROM query_traces WHERE outcome='abstained'"));
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
    }
  }
}
