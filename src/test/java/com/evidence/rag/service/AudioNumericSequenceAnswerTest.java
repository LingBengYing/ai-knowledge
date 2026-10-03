package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production authority and answer/source flow over synthetic ASR, with no provider calls. */
class AudioNumericSequenceAnswerTest {
  private static final String FACT = "Project Car launch code is 7,3,9,21";
  private static final String QUESTION = "What is Project Car launch code?";
  @TempDir Path directory;

  @Test
  void answerAndSavedSourcePreserveTheCompleteOriginalSequence() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      var audio = AudioTestFixture.publish(context, List.of(FACT + "."));
      var result =
          context.answers.answerAudio(
              context.owner,
              new AnswerCommand(QUESTION, DocumentSelection.selected(List.of(audio.documentId()))));
      assertEquals("answered", result.status(), result.reason());
      assertEquals(FACT, result.answer());
      assertEquals(1, result.citations().size());
      var citation = result.citations().getFirst();
      assertEquals(FACT, citation.quote());
      assertEquals(audio.documentId(), citation.documentId());
      assertEquals(audio.revisionId(), citation.revisionId());
      assertEquals(0, citation.startMs());
      assertEquals(1000, citation.endMs());
      assertEquals(
          citation,
          context
              .answers
              .audioSource(context.owner, result.answerId(), citation.number())
              .citation());
    }
  }

  @Test
  void quotedFirstDigitIsRejectedAndCannotSilentlyRecoverTheTail() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      var audio = AudioTestFixture.publish(context, List.of(FACT + "."));
      context.models.extraction =
          values ->
              new TextModels.Extraction(
                  List.of(
                      new TextModels.Quote(values.getFirst().id(), "Project Car launch code is 7")),
                  false);
      var result =
          context.answers.answerAudio(
              context.owner,
              new AnswerCommand(QUESTION, DocumentSelection.selected(List.of(audio.documentId()))));
      assertEquals("abstained", result.status());
      assertEquals("incomplete_evidence", result.reason());
      assertTrue(result.citations().isEmpty());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_trace_evidence"));
    }
  }

  @Test
  void theWrongRecognizedNameRemainsUnproved() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      var audio = AudioTestFixture.publish(context, List.of(FACT + "."));
      var result =
          context.answers.answerAudio(
              context.owner,
              new AnswerCommand(
                  "What is Project Cedar launch code?",
                  DocumentSelection.selected(List.of(audio.documentId()))));
      assertEquals("abstained", result.status());
      assertEquals("incomplete_evidence", result.reason());
      assertTrue(result.citations().isEmpty());
    }
  }
}
