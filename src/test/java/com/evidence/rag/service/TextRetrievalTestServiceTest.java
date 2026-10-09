package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.RetrievalTestCommand;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextRetrievalTestServiceTest {
  @TempDir Path directory;

  @Test
  void savedSettingsAndTemporaryOverridesFilterDeduplicateAndKeepRequestSnapshot() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.context.publish("relevant.txt", "相关灯塔材料");
      fixture.context.publish("duplicate.txt", "相关灯塔材料");
      fixture.context.publish("noise.txt", "无关噪声");
      fixture.activate(1);
      var original = new RetrievalSettings(7, "hybrid", "rerank", 0.5, 2, true, 0.5);
      var saved = new AtomicReference<>(original);
      fixture.context.models.ranking =
          texts ->
              IntStream.range(0, texts.size())
                  .mapToObj(
                      i -> new TextModels.Ranked(i, texts.get(i).equals("相关灯塔材料") ? 0.5 : 0.000001))
                  .toList();
      fixture.context.models.onRerank =
          () -> saved.set(new RetrievalSettings(8, "hybrid", "rerank", 0.5, 1, true, 1.0));
      try (var retrieval =
          new TextRetrievalTestService(
              fixture.context.evidence, fixture.runtime, Duration.ofSeconds(3), 1, saved::get)) {
        var inherited =
            new RetrievalTestCommand(
                new AnswerCommand("灯塔", DocumentSelection.allDocuments()), null, null);
        var first = retrieval.test(fixture.context.owner, inherited);
        assertEquals(7, first.effectiveSettings().version());
        assertEquals(2, first.effectiveSettings().topK());
        assertEquals(1, first.matches().size());
        assertEquals("相关灯塔材料", first.matches().getFirst().text());
        assertEquals(0.5, first.matches().getFirst().rerankScore());
        var next = retrieval.test(fixture.context.owner, inherited);
        assertEquals("no_matches", next.reason());
        assertEquals(8, next.effectiveSettings().version());
        var overridden =
            retrieval.test(
                fixture.context.owner,
                new RetrievalTestCommand(
                    inherited.answer(),
                    null,
                    null,
                    new RetrievalSettings(0, "hybrid", "rerank", 0.5, 3, false, 1.0)));
        assertEquals(8, overridden.effectiveSettings().version());
        assertEquals(3, overridden.effectiveSettings().topK());
        assertEquals(2, overridden.matches().size());
        assertTrue(saved.get().scoreThresholdEnabled());
      }
    }
  }

  @Test
  void fullTextWeightedUsesNoModelAndVectorModeReportsItsOwnScore() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.context.publish("lighthouse.txt", "synthetic lighthouse launch");
      fixture.activate(1);
      var saved =
          new AtomicReference<>(
              new RetrievalSettings(1, "full_text", "weighted", 0.5, 5, false, 0.5));
      try (var retrieval =
          new TextRetrievalTestService(
              fixture.context.evidence, fixture.runtime, Duration.ofSeconds(3), 1, saved::get)) {
        var command =
            new RetrievalTestCommand(
                new AnswerCommand("lighthouse", DocumentSelection.allDocuments()), null, null);
        var fullText = retrieval.test(fixture.context.owner, command);
        assertEquals("completed", fullText.status());
        assertEquals("bm25", fullText.scoreKind());
        assertEquals(1, fullText.matches().size());
        assertTrue(fixture.context.models.calls.isEmpty());
        assertNull(fullText.matches().getFirst().rerankScore());
        saved.set(new RetrievalSettings(2, "vector", "weighted", 0.5, 5, false, 0.5));
        var vector = retrieval.test(fixture.context.owner, command);
        assertEquals("vector_similarity", vector.scoreKind());
        assertEquals(List.of("embed"), fixture.context.models.calls);
      }
    }
  }

  @Test
  void returnsAuthorityUnicodeLocatorAndUtf8ShaWithoutGenerationOrTrace() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      String text = "上海住宿😀上限为650元。";
      String document = fixture.context.publish("policy.txt", text);
      fixture.activate(1);
      long traces = fixture.context.scalar("SELECT COUNT(*) FROM query_traces");
      try (var retrieval = service(fixture, Duration.ofSeconds(3))) {
        var result =
            retrieval.test(
                fixture.context.owner,
                command(DocumentSelection.selected(List.of(document)), 5, false));
        assertEquals(1, result.configurationVersion());
        assertEquals("completed", result.status());
        assertNull(result.reason());
        assertEquals("weighted_score", result.scoreKind());
        assertEquals(1, result.scopeCount());
        assertEquals(1, result.matches().size());
        var match = result.matches().getFirst();
        assertEquals(document, match.documentId());
        assertEquals("policy.txt", match.filename());
        assertEquals(text, match.text());
        assertEquals(0, match.start());
        assertEquals(text.codePointCount(0, text.length()), match.end());
        assertEquals(ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8)), match.textSha256());
        assertNull(match.rerankScore());
        assertEquals(List.of("embed"), fixture.context.models.calls);
        assertEquals(List.of("prepare", "search"), fixture.context.projection.calls);
      }
      assertEquals(traces, fixture.context.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @Test
  void deprecatedEmptySelectionSearchesSharedWorkspaceAndNoHitsNeverExtracts() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.context.publish("policy.txt", "上海住宿上限650元。");
      fixture.activate(1);
      try (var retrieval = service(fixture, Duration.ofSeconds(3))) {
        var empty =
            retrieval.test(
                fixture.context.owner, command(DocumentSelection.selected(List.of()), 5, true));
        assertEquals("completed", empty.status());
        assertEquals(1, empty.scopeCount());
        assertEquals(List.of("embed", "rerank"), fixture.context.models.calls);
        fixture.context.models.calls.clear();
        fixture.context.projection.results = ignored -> List.of();
        var noHits =
            retrieval.test(
                fixture.context.owner, command(DocumentSelection.allDocuments(), 5, true));
        assertEquals("no_matches", noHits.reason());
        assertEquals(1, noHits.scopeCount());
        assertEquals(List.of("embed"), fixture.context.models.calls);
      }
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @Test
  void deprecatedSelectionAndHistoricalAclCannotRestrictSharedRetrieval() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      String document = fixture.context.publish("policy.txt", "上海住宿上限650元。");
      fixture.activate(1);
      try (var retrieval = service(fixture, Duration.ofSeconds(3))) {
        assertEquals(
            1,
            retrieval
                .test(
                    fixture.context.owner,
                    command(
                        DocumentSelection.selected(List.of(document, "unknown-document")), 1, true))
                .scopeCount());
        fixture.context.revoke(document);
        assertEquals(
            1,
            retrieval
                .test(
                    fixture.context.owner,
                    command(DocumentSelection.selected(List.of(document)), 1, true))
                .scopeCount());
        assertEquals(List.of("embed", "rerank", "embed", "rerank"), fixture.context.models.calls);
        assertEquals(
            0,
            retrieval
                .test(
                    new Actor("other-workspace", "member"),
                    command(DocumentSelection.allDocuments(), 1, true))
                .scopeCount());
        assertEquals(List.of("embed", "rerank", "embed", "rerank"), fixture.context.models.calls);
      }
    }
  }

  @Test
  void allCandidatesAreHydratedAndRerankedBeforeTopK() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.context.publish("first.txt", "上海住宿上限650元。");
      fixture.context.publish("second.txt", "北京住宿上限450元。");
      fixture.activate(1);
      var seen = new AtomicInteger();
      fixture.context.models.ranking =
          texts -> {
            seen.set(texts.size());
            return List.of(new TextModels.Ranked(1, 0.9), new TextModels.Ranked(0, 0.1));
          };
      try (var retrieval = service(fixture, Duration.ofSeconds(3))) {
        var result =
            retrieval.test(
                fixture.context.owner, command(DocumentSelection.allDocuments(), 1, true));
        assertEquals(2, seen.get());
        assertEquals(1, result.matches().size());
        assertEquals(0.9, result.matches().getFirst().rerankScore());
        assertEquals(List.of("embed", "rerank"), fixture.context.models.calls);
        assertEquals(2, fixture.context.projection.lastScope.documentRevisions().size());
      }
    }
  }

  @Test
  void foreignTailCandidateCannotDisappearBehindTopK() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.context.publish("policy.txt", "上海住宿上限650元。");
      fixture.activate(1);
      fixture.context.projection.results =
          candidates -> {
            var all = new ArrayList<>(candidates);
            all.add(new RetrievalProjection.Candidate("foreign-physical-segment", 0.001));
            return all;
          };
      try (var retrieval = service(fixture, Duration.ofSeconds(3))) {
        assertThrows(
            ApplicationException.class,
            () ->
                retrieval.test(
                    fixture.context.owner, command(DocumentSelection.allDocuments(), 1, true)));
        assertEquals(List.of("embed"), fixture.context.models.calls);
      }
    }
  }

  @Test
  void uncitedScopeWithdrawalDuringRerankRejectsWholeResult() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      String first = fixture.context.publish("first.txt", "上海住宿上限650元。");
      String second = fixture.context.publish("second.txt", "北京住宿上限450元。");
      fixture.activate(1);
      fixture.context.projection.results = hits -> hits.subList(0, 1);
      fixture.context.models.onRerank =
          () -> {
            try (var connection =
                    DriverManager.getConnection(
                        "jdbc:sqlite:" + directory.resolve("java-library.db"));
                var statement =
                    connection.prepareStatement(
                        "INSERT INTO document_tombstones SELECT id,workspace_id,?,? FROM documents WHERE id=?")) {
              statement.setString(1, fixture.context.owner.principalId());
              statement.setString(2, "2026-10-08T00:00:00Z");
              statement.setString(3, second);
              statement.executeUpdate();
            } catch (Exception failure) {
              throw new AssertionError("Synthetic withdrawal fixture failed", failure);
            }
          };
      try (var retrieval = service(fixture, Duration.ofSeconds(3))) {
        assertEquals(
            "scope_changed",
            assertThrows(
                    ApplicationException.class,
                    () ->
                        retrieval.test(
                            fixture.context.owner,
                            command(DocumentSelection.selected(List.of(first, second)), 1, true)))
                .code());
      }
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @Test
  void allScopeNewPublicationDuringEmbeddingRejectsRatherThanReturnSubset() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.context.publish("first.txt", "上海住宿上限650元。");
      fixture.activate(1);
      fixture.context.models.onEmbed = () -> fixture.context.publish("new.txt", "北京住宿上限450元。");
      try (var retrieval = service(fixture, Duration.ofSeconds(3))) {
        assertEquals(
            "scope_changed",
            assertThrows(
                    ApplicationException.class,
                    () ->
                        retrieval.test(
                            fixture.context.owner,
                            command(DocumentSelection.allDocuments(), 1, false)))
                .code());
        assertFalse(fixture.context.models.calls.contains("extract"));
      }
    }
  }

  @Test
  void incompleteRankingAndDuplicatePhysicalHitsAreSafeStageFailures() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.context.publish("first.txt", "上海住宿上限650元。");
      fixture.context.publish("second.txt", "北京住宿上限450元。");
      fixture.activate(1);
      fixture.context.models.ranking = ignored -> List.of(new TextModels.Ranked(0, 0.5));
      try (var retrieval = service(fixture, Duration.ofSeconds(3))) {
        assertEquals(
            "retrieval_rerank_failed",
            assertThrows(
                    ApplicationException.class,
                    () ->
                        retrieval.test(
                            fixture.context.owner,
                            command(DocumentSelection.allDocuments(), 1, true)))
                .code());
        fixture.context.projection.results = hits -> List.of(hits.getFirst(), hits.getFirst());
        assertEquals(
            "retrieval_search_failed",
            assertThrows(
                    ApplicationException.class,
                    () ->
                        retrieval.test(
                            fixture.context.owner,
                            command(DocumentSelection.allDocuments(), 1, true)))
                .code());
      }
    }
  }

  @Test
  void profileDriftDuringEmbeddingStopsBeforeSearchAndRerank() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.context.publish("policy.txt", "上海住宿上限650元。");
      fixture.activate(1);
      fixture.context.models.onEmbed =
          () -> fixture.context.models.modelRevision = "changed-model-v2";
      try (var retrieval = service(fixture, Duration.ofSeconds(3))) {
        assertEquals(
            "configuration_changed",
            assertThrows(
                    ApplicationException.class,
                    () ->
                        retrieval.test(
                            fixture.context.owner,
                            command(DocumentSelection.allDocuments(), 5, true)))
                .code());
        assertFalse(fixture.context.projection.calls.contains("search"));
        assertEquals(List.of("embed"), fixture.context.models.calls);
      }
    }
  }

  @Test
  void callerTimeoutRetainsActualBodyReservationAndAdmissionUntilThreadExit() throws Exception {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.context.publish("policy.txt", "上海住宿上限650元。");
      fixture.activate(1);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      fixture.context.models.onEmbed =
          () -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
              try {
                done = release.await(20, TimeUnit.MILLISECONDS);
              } catch (InterruptedException ignored) {
                // Synthetic upstream deliberately retains its actual body after caller
                // cancellation.
              }
            }
          };
      try (var retrieval = service(fixture, Duration.ofMillis(150))) {
        try {
          assertEquals(
              "retrieval_timeout",
              assertThrows(
                      ApplicationException.class,
                      () ->
                          retrieval.test(
                              fixture.context.owner,
                              command(DocumentSelection.allDocuments(), 1, true)))
                  .code());
          assertTrue(entered.await(1, TimeUnit.SECONDS));
          assertTrue(fixture.context.authority.store().operationGate().tryMaintenance().isEmpty());
          assertEquals(
              "retrieval_capacity_exceeded",
              assertThrows(
                      ApplicationException.class,
                      () ->
                          retrieval.test(
                              fixture.context.owner,
                              command(DocumentSelection.allDocuments(), 1, true)))
                  .code());
        } finally {
          release.countDown();
        }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!fixture.context.authority.store().operationGate().isIdle()
            && System.nanoTime() < end) {
          Thread.sleep(5);
        }
        assertTrue(fixture.context.authority.store().operationGate().isIdle());
        assertFalse(fixture.context.projection.calls.contains("search"));
        assertFalse(fixture.context.models.calls.contains("rerank"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"embedding", "search", "rerank"})
  void providerFailuresIdentifyOnlyTheFailedStageAndNeverExtract(String stage) {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.context.publish("policy.txt", "上海住宿上限650元。");
      fixture.activate(1);
      Runnable rejected =
          () -> {
            throw new IllegalStateException("synthetic provider secret body");
          };
      switch (stage) {
        case "embedding" -> fixture.context.models.onEmbed = rejected;
        case "search" -> fixture.context.projection.onSearch = rejected;
        case "rerank" -> fixture.context.models.onRerank = rejected;
        default -> throw new AssertionError("Unknown synthetic stage");
      }
      try (var retrieval = service(fixture, Duration.ofSeconds(3))) {
        var error =
            assertThrows(
                ApplicationException.class,
                () ->
                    retrieval.test(
                        fixture.context.owner, command(DocumentSelection.allDocuments(), 5, true)));
        assertEquals("retrieval_" + stage + "_failed", error.code());
        assertFalse(error.getMessage().contains("secret"));
        assertNull(error.getCause());
        assertFalse(fixture.context.models.calls.contains("extract"));
        assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM query_traces"));
      }
    }
  }

  @Test
  void matchingCapturedTargetSourceReadRetainsLegacySourceAndRejectsOtherProfile() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      String document = fixture.context.publish("policy.txt", "星港项目的识别码为A-42。");
      var command =
          new AnswerCommand("星港项目的识别码是什么？", DocumentSelection.selected(List.of(document)));
      var answer = fixture.context.answers.answer(fixture.context.owner, command);
      assertEquals("answered", answer.status());
      var legacy = fixture.context.answers.source(fixture.context.owner, answer.answerId(), 1);
      var matching =
          fixture.context.answers.source(
              fixture.context.owner, answer.answerId(), 1, fixture.context.target);
      assertEquals(legacy, matching);
      var target = fixture.context.target;
      var other =
          new IndexTarget(
              "changed-embedding", target.projectionIdentity(), target.modelRevision(), 2);
      assertEquals(
          "not_found",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      fixture.context.answers.source(
                          fixture.context.owner, answer.answerId(), 1, other))
              .code());
      assertEquals(
          "not_found",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      fixture.context.evidence.source(
                          fixture.context.owner, answer.answerId(), 1, other))
              .code());
      assertEquals(
          legacy, fixture.context.answers.source(fixture.context.owner, answer.answerId(), 1));
    }
  }

  private static TextRetrievalTestService service(
      ManagedTextTestFixture fixture, Duration timeout) {
    return new TextRetrievalTestService(fixture.context.evidence, fixture.runtime, timeout, 1);
  }

  private static RetrievalTestCommand command(
      DocumentSelection selection, int topK, boolean rerank) {
    return new RetrievalTestCommand(new AnswerCommand("上海住宿上限是多少？", selection), topK, rerank);
  }
}
