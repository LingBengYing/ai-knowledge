package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AnswerResult;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Seam failures cannot bypass server proof, admission, or the final durable trace decision. */
class AnswerServiceBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"null", "empty", "extra", "null-vector", "dimensions", "nan", "zero"})
  void invalidEmbeddingDoesNotReachVectorSearch(String shape) {
    try (var fixture = fixture()) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      fixture.models.embedding =
          ignored ->
              switch (shape) {
                case "null" -> null;
                case "empty" -> List.of();
                case "extra" -> List.of(List.of(1.0, 0.0), List.of(1.0, 0.0));
                case "null-vector" -> Collections.singletonList(null);
                case "dimensions" -> List.of(List.of(1.0, 0.0, 0.0));
                case "nan" -> List.of(List.of(Double.NaN, 1.0));
                case "zero" -> List.of(List.of(0.0, 0.0));
                default -> throw new AssertionError(shape);
              };
      assertRefused(fixture, answer(fixture));
      assertEquals(List.of("embed"), fixture.models.calls);
      assertEquals(List.of("prepare"), fixture.projection.calls);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing", "extra", "duplicate", "negative-index", "outside", "nan", "infinity"})
  void rankingMustCoverAllCandidatesExactlyOnceWithFiniteScores(String shape) {
    try (var fixture = fixture()) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      fixture.publish("other.txt", "远洋项目的识别码为B-51。");
      fixture.models.ranking =
          ignored ->
              switch (shape) {
                case "missing" -> List.of(new TextModels.Ranked(0, 1));
                case "extra" ->
                    List.of(
                        new TextModels.Ranked(0, 1),
                        new TextModels.Ranked(1, 1),
                        new TextModels.Ranked(2, 1));
                case "duplicate" ->
                    List.of(new TextModels.Ranked(0, 1), new TextModels.Ranked(0, 1));
                case "negative-index" ->
                    List.of(new TextModels.Ranked(-1, 1), new TextModels.Ranked(1, 1));
                case "outside" -> List.of(new TextModels.Ranked(0, 1), new TextModels.Ranked(2, 1));
                case "nan" ->
                    List.of(new TextModels.Ranked(0, Double.NaN), new TextModels.Ranked(1, 1));
                case "infinity" ->
                    List.of(
                        new TextModels.Ranked(0, 1),
                        new TextModels.Ranked(1, Double.POSITIVE_INFINITY));
                default -> throw new AssertionError(shape);
              };
      assertRefused(fixture, answer(fixture));
      assertEquals(List.of("embed", "rerank"), fixture.models.calls);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"embed", "rerank", "extract"})
  void modelFailuresHaveNoAnswerTextAndAreStillAudited(String stage) {
    try (var fixture = fixture()) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      Runnable fail =
          () -> {
            throw new TextModels.Failure("synthetic-private-upstream-message");
          };
      switch (stage) {
        case "embed" -> fixture.models.onEmbed = fail;
        case "rerank" -> fixture.models.onRerank = fail;
        case "extract" -> fixture.models.onExtract = fail;
        default -> throw new AssertionError(stage);
      }
      var result = answer(fixture);
      assertRefused(fixture, result);
      assertFalse(result.toString().contains("synthetic-private"));
      assertFalse(result.answer().contains("synthetic-private"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"embed", "rerank", "extract"})
  void changedModelIdentityCannotReleaseAnAnswerFromTheOldConfiguration(String stage) {
    try (var fixture = fixture()) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      Runnable change = () -> fixture.models.modelRevision = "different-model-v2";
      switch (stage) {
        case "embed" -> fixture.models.onEmbed = change;
        case "rerank" -> fixture.models.onRerank = change;
        case "extract" -> fixture.models.onExtract = change;
        default -> throw new AssertionError(stage);
      }
      var result = answer(fixture);
      assertEquals("configuration_changed", result.reason());
      assertRefused(fixture, result);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "empty-answered", "refused-with-quote", "too-many", "forged"})
  void extractionShapesCannotCreateAnUnprovedCitation(String shape) {
    try (var fixture = fixture()) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      fixture.models.extraction =
          values -> {
            var quote = new TextModels.Quote(values.getFirst().id(), values.getFirst().text());
            return switch (shape) {
              case "null" -> null;
              case "empty-answered" -> new TextModels.Extraction(List.of(), false);
              case "refused-with-quote" -> new TextModels.Extraction(List.of(quote), true);
              case "too-many" -> new TextModels.Extraction(Collections.nCopies(33, quote), false);
              case "forged" ->
                  new TextModels.Extraction(
                      List.of(new TextModels.Quote("unrequested-physical-id", quote.quote())),
                      false);
              default -> throw new AssertionError(shape);
            };
          };
      assertRefused(fixture, answer(fixture));
    }
  }

  @Test
  void arbitraryFiniteRerankScoreIsNotTreatedAsAProbabilityThreshold() {
    try (var fixture = fixture()) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      fixture.models.ranking = ignored -> List.of(new TextModels.Ranked(0, -100));
      assertEquals("answered", answer(fixture).status());
    }
  }

  @Test
  void finalTraceFailureDoesNotReturnSuccessOrLeavePartialChildren() throws Exception {
    try (var fixture = fixture()) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement = connection.createStatement()) {
        statement.execute(
            "CREATE TRIGGER test_trace_failure BEFORE INSERT ON query_traces "
                + "BEGIN SELECT RAISE(ABORT,'synthetic-trace-fault'); END");
      }
      var failure = assertThrows(ApplicationException.class, () -> answer(fixture));
      assertEquals(FailureKind.UNAVAILABLE, failure.kind());
      assertFalse(failure.getMessage().contains("synthetic-trace-fault"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_traces"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
    }
  }

  @Test
  void closedServiceRejectsNewQueriesAndSourceReadsWithoutTouchingAdapters() {
    try (var fixture = fixture()) {
      fixture.answers.close();
      assertEquals(
          FailureKind.UNAVAILABLE,
          assertThrows(ApplicationException.class, () -> answer(fixture)).kind());
      assertEquals(
          FailureKind.UNAVAILABLE,
          assertThrows(
                  ApplicationException.class,
                  () -> fixture.answers.source(fixture.owner, "not-a-trace", 1))
              .kind());
      assertTrue(fixture.models.calls.isEmpty());
      assertTrue(fixture.projection.calls.isEmpty());
    }
  }

  @Test
  void emptyAuthorizedLibraryCreatesARefusalWithoutExternalCalls() {
    try (var fixture = fixture()) {
      var result = answer(fixture);
      assertEquals("empty_scope", result.reason());
      assertRefused(fixture, result);
      assertTrue(fixture.models.calls.isEmpty());
      assertTrue(fixture.projection.calls.isEmpty());
    }
  }

  private AnswerTestContext fixture() {
    return new AnswerTestContext(directory, Duration.ofSeconds(5), 2);
  }

  private static AnswerResult answer(AnswerTestContext fixture) {
    return fixture.answers.answer(
        fixture.owner, new AnswerCommand("星港项目的识别码是什么？", DocumentSelection.allDocuments()));
  }

  private static void assertRefused(AnswerTestContext fixture, AnswerResult result) {
    assertEquals("abstained", result.status());
    assertTrue(result.citations().isEmpty());
    assertFalse(result.answer().contains("A-42"));
    assertEquals(
        1, fixture.scalar("SELECT COUNT(*) FROM query_traces WHERE answer_sha256 IS NULL"));
    assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
  }
}
