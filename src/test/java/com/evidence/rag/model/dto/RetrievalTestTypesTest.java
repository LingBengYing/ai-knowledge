package com.evidence.rag.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RetrievalTestTypesTest {
  @Test
  void wireHasSettingsSnapshotAndThirteenMatchFieldsWithNullableRerank() {
    var result =
        new RetrievalTestResult(
            UUID.randomUUID().toString(),
            1,
            "completed",
            null,
            1,
            "rrf",
            List.of(match(1, "预算😀650", null)));
    var mapper = JsonMapper.builder().build();
    var json = mapper.readTree(mapper.writeValueAsString(result));
    assertEquals(
        Set.of(
            "test_id",
            "configuration_version",
            "status",
            "reason",
            "scope_count",
            "score_kind",
            "effective_settings",
            "matches"),
        new HashSet<>(json.propertyNames()));
    var hit = json.get("matches").get(0);
    assertEquals(
        Set.of(
            "rank",
            "document_id",
            "revision_id",
            "filename",
            "source_sha256",
            "parser_revision",
            "page",
            "start",
            "end",
            "text",
            "text_sha256",
            "retrieval_score",
            "rerank_score"),
        new HashSet<>(hit.propertyNames()));
    assertTrue(hit.get("rerank_score").isNull());
    assertEquals("预算😀650", hit.get("text").asText());
    assertFalse(result.toString().contains("650"));
    assertFalse(result.matches().getFirst().toString().contains("650"));
  }

  @Test
  void commandKeepsRankingOptionsButAcceptsQuestionsBeyondOldByteLimit() {
    var answer = new AnswerCommand("预算？", DocumentSelection.selected(List.of()));
    assertTrue(
        new RetrievalTestCommand(answer, 1, false).answer().selection().documentIds().isEmpty());
    assertThrows(ApplicationException.class, () -> new RetrievalTestCommand(answer, 0, false));
    assertThrows(ApplicationException.class, () -> new RetrievalTestCommand(answer, 21, false));
    assertEquals(
        "x".repeat(4097),
        new AnswerCommand("x".repeat(4097), DocumentSelection.allDocuments()).question());
    assertFalse(new RetrievalTestCommand(answer, 20, true).toString().contains("预算"));
  }

  @Test
  void resultIsImmutableAndCannotAdvertisePartialEmptyOrNonContiguousRanks() {
    var matches = new ArrayList<>(List.of(match(1, "原文", 100.0)));
    var result =
        new RetrievalTestResult(
            UUID.randomUUID().toString(), 1, "completed", null, 1, "rrf", matches);
    matches.clear();
    assertEquals(1, result.matches().size());
    assertThrows(UnsupportedOperationException.class, () -> result.matches().clear());
    assertThrows(
        ApplicationException.class,
        () ->
            new RetrievalTestResult(
                UUID.randomUUID().toString(),
                1,
                "empty",
                "no_matches",
                1,
                "rrf",
                result.matches()));
    assertThrows(
        ApplicationException.class,
        () ->
            new RetrievalTestResult(
                UUID.randomUUID().toString(),
                1,
                "completed",
                null,
                1,
                "rrf",
                List.of(match(2, "原文", 1.0))));
  }

  @Test
  void locatorHashAndFloatBoundariesCannotBeFabricated() {
    var valid = match(1, "原文😀", -0.5);
    assertEquals(3, valid.end());
    assertThrows(
        ApplicationException.class,
        () ->
            new RetrievalTestMatch(
                1,
                valid.documentId(),
                valid.revisionId(),
                valid.filename(),
                valid.sourceSha256(),
                valid.parserRevision(),
                1,
                0,
                4,
                valid.text(),
                valid.textSha256(),
                0.1,
                null));
    assertThrows(
        ApplicationException.class,
        () ->
            new RetrievalTestMatch(
                1,
                valid.documentId(),
                valid.revisionId(),
                valid.filename(),
                valid.sourceSha256(),
                valid.parserRevision(),
                1,
                0,
                3,
                valid.text(),
                "0".repeat(64),
                0.1,
                null));
    assertThrows(ApplicationException.class, () -> match(1, "原文", Double.NaN));
  }

  private static RetrievalTestMatch match(int rank, String text, Double rerank) {
    return new RetrievalTestMatch(
        rank,
        "document-1",
        "revision-1",
        "synthetic.txt",
        "a".repeat(64),
        "java-text-parser-v2-monotonic-codepoints",
        1,
        0,
        text.codePointCount(0, text.length()),
        text,
        ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8)),
        0.02,
        rerank);
  }
}
