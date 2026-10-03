package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnchoredAnswerServiceTest {
  @TempDir Path directory;

  @Test
  void newAnswerTraceUsesActualGenerationRevisionWhileOldTracePublicationAndSourceStayUnchanged()
      throws Exception {
    try (var fixture = new TextRoleSwitchTestFixture(directory)) {
      var first =
          fixture.activate(1, TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v1"), null);
      String document = fixture.publish(first.target());
      var question =
          new AnswerCommand("星港项目的识别码是什么？", new DocumentSelection(false, List.of(document)));
      var old = first.answers().answer(TextRoleSwitchTestFixture.ADMIN, question);
      assertEquals("answered", old.status());
      assertEquals(first.modelsRevision(), traceRevision(fixture, old.answerId()));
      String oldTrace =
          fixture.scalar(
              "SELECT question_sha256 || ':' || answer_sha256 || ':' || model_revision FROM query_traces WHERE id=?",
              old.answerId());
      var switched =
          fixture.activate(
              2,
              TextRoleSwitchTestFixture.roles("rerank-v2", "generation-v2"),
              first.indexAnchor());
      var answer = switched.answers().answer(TextRoleSwitchTestFixture.ADMIN, question);
      assertEquals("answered", answer.status());
      assertEquals(switched.modelsRevision(), traceRevision(fixture, answer.answerId()));
      assertNotEquals(first.target().modelRevision(), traceRevision(fixture, answer.answerId()));
      assertEquals(
          first.target().modelRevision(),
          fixture.scalar("SELECT model_revision FROM index_publications", null));
      assertEquals(
          oldTrace,
          fixture.scalar(
              "SELECT question_sha256 || ':' || answer_sha256 || ':' || model_revision FROM query_traces WHERE id=?",
              old.answerId()));
      assertEquals(
          old.citations().getFirst(),
          switched
              .answers()
              .source(TextRoleSwitchTestFixture.ADMIN, old.answerId(), 1, switched.target())
              .citation());
      assertEquals(
          answer.citations().getFirst(),
          switched
              .answers()
              .source(TextRoleSwitchTestFixture.ADMIN, answer.answerId(), 1, switched.target())
              .citation());
    }
  }

  @Test
  void emptyScopeAbstentionAlsoRecordsActualCurrentRevisionWithoutCallingProviders()
      throws Exception {
    try (var fixture = new TextRoleSwitchTestFixture(directory)) {
      var first =
          fixture.activate(1, TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v1"), null);
      var switched =
          fixture.activate(
              2,
              TextRoleSwitchTestFixture.roles("rerank-v2", "generation-v2"),
              first.indexAnchor());
      var result =
          switched
              .answers()
              .answer(
                  TextRoleSwitchTestFixture.ADMIN,
                  new AnswerCommand("星港项目的识别码是什么？", new DocumentSelection(false, List.of())));
      assertEquals("abstained", result.status());
      assertEquals("empty_scope", result.reason());
      assertEquals(switched.modelsRevision(), traceRevision(fixture, result.answerId()));
      assertNotEquals(first.target().modelRevision(), traceRevision(fixture, result.answerId()));
      assertTrue(fixture.projection.calls.isEmpty());
    }
  }

  @Test
  void oldStrictAnswerConstructorDoesNotSilentlyAcceptANewFullProfileWithAnOldTarget() {
    try (var fixture = new TextRoleSwitchTestFixture(directory)) {
      var first =
          fixture.activate(1, TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v1"), null);
      var switched =
          fixture.activate(
              2,
              TextRoleSwitchTestFixture.roles("rerank-v2", "generation-v2"),
              first.indexAnchor());
      assertThrows(
          ApplicationException.class,
          () ->
              new AnswerService(
                  fixture.evidence,
                  switched.models(),
                  fixture.projection,
                  first.target(),
                  Duration.ofSeconds(3),
                  2));
      assertTrue(fixture.authority.store().operationGate().isIdle());
    }
  }

  private static String traceRevision(TextRoleSwitchTestFixture fixture, String id)
      throws Exception {
    return fixture.scalar("SELECT model_revision FROM query_traces WHERE id=?", id);
  }
}
