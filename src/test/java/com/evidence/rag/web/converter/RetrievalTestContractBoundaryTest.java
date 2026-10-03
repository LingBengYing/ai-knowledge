package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.RetrievalTestCommand;
import com.evidence.rag.model.dto.RetrievalTestMatch;
import com.evidence.rag.model.dto.RetrievalTestResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class RetrievalTestContractBoundaryTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TEST_ID = "8c51ec7c-9bb7-4a90-8a11-009bc0219210";
  private static final String SOURCE_SHA = "a".repeat(64);

  @Test
  void defaultsPreserveTheFullQuestionAndExplicitEmptySelectionNeverBecomesAll() {
    String question = "  合成预算😀？\n请保留全部范围。  ";
    var all = request(JSON.writeValueAsString(Map.of("question", question)));
    assertEquals(question, all.answer().question());
    assertTrue(all.answer().selection().all());
    assertEquals(5, all.topK());
    assertTrue(all.rerank());
    var empty =
        request(
            JSON.writeValueAsString(
                Map.of("question", question, "document_ids", List.of(), "rerank", false)));
    assertFalse(empty.answer().selection().all());
    assertTrue(empty.answer().selection().documentIds().isEmpty());
    assertFalse(empty.rerank());
    assertFalse(empty.toString().contains("合成预算"));
  }

  @ParameterizedTest(name = "rejects ambiguous retrieval JSON: {0}")
  @MethodSource("ambiguousJson")
  void rejectsAmbiguousJsonWithoutExposingSubmittedAuthorityOrProviderFields(
      String scenario, String json) {
    var failure = assertInvalid(() -> request(json));
    assertFalse(failure.getMessage().contains("synthetic-private-marker"), scenario);
  }

  private static Stream<Arguments> ambiguousJson() {
    return Stream.of(
        Arguments.of("duplicate question", "{\"question\":\"first\",\"question\":\"second\"}"),
        Arguments.of("duplicate option", "{\"question\":\"q\",\"top_k\":1,\"top_k\":20}"),
        Arguments.of("trailing request", "{\"question\":\"q\"}{\"question\":\"other\"}"),
        Arguments.of("malformed JSON", "{\"question\":\"unfinished"),
        Arguments.of("array root", "[{\"question\":\"q\"}]"),
        Arguments.of("missing full question", "{\"top_k\":5}"),
        Arguments.of("question coercion", "{\"question\":123}"),
        Arguments.of(
            "forged authority",
            "{\"question\":\"q\",\"workspace_id\":\"synthetic-private-marker\"}"),
        Arguments.of(
            "provider redirect",
            "{\"question\":\"q\",\"base_url\":\"https://synthetic-private-marker.invalid\"}"),
        Arguments.of(
            "forged source link",
            "{\"question\":\"q\",\"source_url\":\"synthetic-private-marker\"}"));
  }

  @Test
  void requestOptionsUseBoundedExactIntegersAndRealBooleansRatherThanCoercion() {
    assertEquals(1, request("{\"question\":\"q\",\"top_k\":1}").topK());
    assertEquals(20, request("{\"question\":\"q\",\"top_k\":20}").topK());
    for (String topK : List.of("0", "21", "5.0", "1e0", "\"5\"", "null", "2147483648")) {
      assertInvalid(() -> request("{\"question\":\"q\",\"top_k\":" + topK + "}"));
    }
    for (String rerank : List.of("\"false\"", "0", "null")) {
      assertInvalid(() -> request("{\"question\":\"q\",\"rerank\":" + rerank + "}"));
    }
  }

  @Test
  void rawUtf8AndTransportLimitsFailBeforeCreatingACommand() {
    byte[] invalidUtf8 = "{\"question\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    invalidUtf8[13] = (byte) 0xC3;
    assertInvalid(() -> RetrievalTestRequestMapper.command(invalidUtf8));
    assertInvalid(() -> RetrievalTestRequestMapper.command(null));
    assertInvalid(() -> RetrievalTestRequestMapper.command(new byte[0]));
    byte[] bounded = new byte[RetrievalTestRequestMapper.MAX_BYTES];
    Arrays.fill(bounded, (byte) ' ');
    byte[] valid = "{\"question\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8);
    System.arraycopy(valid, 0, bounded, 0, valid.length);
    assertEquals("synthetic", RetrievalTestRequestMapper.command(bounded).answer().question());
    assertInvalid(
        () -> RetrievalTestRequestMapper.command(Arrays.copyOf(bounded, bounded.length + 1)));
  }

  @Test
  void theWholeSelectedSetIsPreservedAndAnInvalidTailCannotBeDropped() {
    var ids = new ArrayList<String>();
    for (int i = 0; i < 128; i++) {
      ids.add("synthetic-doc-" + i);
    }
    var command = request(JSON.writeValueAsString(Map.of("question", "q", "document_ids", ids)));
    assertFalse(command.answer().selection().all());
    assertEquals(ids, command.answer().selection().documentIds());
    assertThrows(
        UnsupportedOperationException.class,
        () -> command.answer().selection().documentIds().clear());
    ids.add("synthetic-doc-128");
    assertInvalid(
        () -> request(JSON.writeValueAsString(Map.of("question", "q", "document_ids", ids))));
    assertEquals(128, command.answer().selection().documentIds().size());
    for (String selected :
        List.of(
            "null",
            "\"doc-one\"",
            "[\"doc-one\",null]",
            "[\"doc-one\",\"doc-one\"]",
            "[\"doc-one\",\"../tail\"]")) {
      assertInvalid(() -> request("{\"question\":\"q\",\"document_ids\":" + selected + "}"));
    }
  }

  @Test
  void questionBudgetUsesCompleteUtf8AndRejectsUnpairedUnicodeInsteadOfReplacingIt() {
    String complete = "😀".repeat(1024);
    assertEquals(
        complete,
        request(JSON.writeValueAsString(Map.of("question", complete))).answer().question());
    assertInvalid(() -> request(JSON.writeValueAsString(Map.of("question", complete + "a"))));
    assertInvalid(() -> request("{\"question\":\"\\uD800\"}"));
    assertInvalid(() -> request("{\"question\":\"\\u0000\"}"));
  }

  @Test
  void sourceTextKeepsWhitespaceUnicodeAndServerLocatorWithScoresThatAreNotProbabilities() {
    String text = " 原文😀\n";
    var match = match(1, 500, 37, 42, text, SOURCE_SHA, sha(text), 2.5, -10.0);
    var result = result("completed", null, 128, List.of(match));
    var wire = JSON.readTree(JSON.writeValueAsString(result));
    assertEquals(
        Set.of(
            "test_id",
            "configuration_version",
            "status",
            "reason",
            "scope_count",
            "score_kind",
            "matches"),
        new HashSet<>(wire.propertyNames()));
    var hit = wire.path("matches").get(0);
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
    assertEquals(text, hit.path("text").asText());
    assertEquals(500, hit.path("page").asInt());
    assertEquals(37, hit.path("start").asInt());
    assertEquals(42, hit.path("end").asInt());
    assertEquals(2.5, hit.path("retrieval_score").doubleValue());
    assertEquals(-10.0, hit.path("rerank_score").doubleValue());
    assertEquals("rrf", wire.path("score_kind").asText());
    assertEquals("RetrievalTestResult[redacted]", result.toString());
    assertEquals("RetrievalTestMatch[redacted]", match.toString());
    assertNull(match(2, 1, 0, 2, "原文", SOURCE_SHA, sha("原文"), 0, null).rerankScore());
  }

  @ParameterizedTest(name = "rejects unusable authority match: {0}")
  @MethodSource("unusableMatches")
  void aMalformedLocatorOrHashCannotBecomeAVisibleAuthorityMatch(
      String scenario, Executable construct) {
    assertInvalid(construct);
  }

  private static Stream<Arguments> unusableMatches() {
    return Stream.of(
        badMatch("rank outside bounded display", 21, 1, 0, 2, "原文", SOURCE_SHA, sha("原文")),
        badMatch("rank cannot be zero", 0, 1, 0, 2, "原文", SOURCE_SHA, sha("原文")),
        badMatch("page zero has no source", 1, 0, 0, 2, "原文", SOURCE_SHA, sha("原文")),
        badMatch("page beyond parser bound", 1, 501, 0, 2, "原文", SOURCE_SHA, sha("原文")),
        badMatch("negative source offset", 1, 1, -1, 1, "原文", SOURCE_SHA, sha("原文")),
        badMatch("empty locator", 1, 1, 2, 2, "", SOURCE_SHA, sha("")),
        badMatch(
            "oversized authority chunk",
            1,
            1,
            0,
            1201,
            "x".repeat(1201),
            SOURCE_SHA,
            sha("x".repeat(1201))),
        badMatch("UTF16 length is not CP length", 1, 1, 0, 4, "原文😀", SOURCE_SHA, sha("原文😀")),
        badMatch("NUL in visible source", 1, 1, 0, 2, "x\u0000", SOURCE_SHA, sha("x\u0000")),
        badMatch("unpaired surrogate source", 1, 1, 0, 2, "x\uD800", SOURCE_SHA, sha("x\uD800")),
        badMatch("missing original hash", 1, 1, 0, 2, "原文", null, sha("原文")),
        badMatch("noncanonical original hash", 1, 1, 0, 2, "原文", "A".repeat(64), sha("原文")),
        badMatch("text hash belongs to another chunk", 1, 1, 0, 2, "原文", SOURCE_SHA, sha("另文")));
  }

  @Test
  void nonFiniteOrNegativeRetrievalScoresCannotBePublishedAsValidRanking() {
    for (double score : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -0.01}) {
      assertInvalid(() -> match(1, 1, 0, 2, "原文", SOURCE_SHA, sha("原文"), score, null));
    }
    assertInvalid(
        () -> match(1, 1, 0, 2, "原文", SOURCE_SHA, sha("原文"), 0.01, Double.NEGATIVE_INFINITY));
  }

  @Test
  void emptyResultsDistinguishNoSelectedDocumentsFromNoHitsWithoutFabricatingSuccess() {
    assertEquals("empty_scope", result("empty", "empty_scope", 0, List.of()).reason());
    assertEquals("no_matches", result("empty", "no_matches", 128, List.of()).reason());
    assertInvalid(() -> result("empty", "empty_scope", 1, List.of()));
    assertInvalid(() -> result("empty", "no_matches", 0, List.of()));
    assertInvalid(() -> result("empty", null, 1, List.of()));
    assertInvalid(() -> result("empty", "provider failed with private details", 1, List.of()));
    assertInvalid(() -> result("completed", "no_matches", 1, List.of(validMatch(1, 1))));
    assertInvalid(() -> result("completed", null, 0, List.of(validMatch(1, 1))));
    assertInvalid(() -> result("completed", null, 1, List.of()));
    assertInvalid(() -> result("answered", null, 1, List.of(validMatch(1, 1))));
  }

  @Test
  void testIdentityVersionAndScopeBoundsCannotBeReplacedWithAnAnswerOrUnknownConfiguration() {
    for (String id : Arrays.asList(null, "1-1-1-1-1", TEST_ID.toUpperCase())) {
      assertInvalid(
          () -> new RetrievalTestResult(id, 1, "empty", "empty_scope", 0, "rrf", List.of()));
    }
    for (long version : new long[] {0, 9_007_199_254_740_992L}) {
      assertInvalid(
          () ->
              new RetrievalTestResult(
                  TEST_ID, version, "empty", "empty_scope", 0, "rrf", List.of()));
    }
    for (int scopeCount : new int[] {-1, 129}) {
      assertInvalid(() -> result("empty", "no_matches", scopeCount, List.of()));
    }
    assertInvalid(
        () ->
            new RetrievalTestResult(
                TEST_ID, 1, "empty", "empty_scope", 0, "probability", List.of()));
    assertInvalid(() -> result("empty", "empty_scope", 0, null));
    assertInvalid(() -> result("completed", null, 1, Arrays.asList(validMatch(1, 1), null)));
  }

  @Test
  void theWholeOrderedMatchListRejectsDuplicateLocatorsAndOverfullResults() {
    var matches = new ArrayList<RetrievalTestMatch>();
    for (int rank = 1; rank <= 20; rank++) {
      matches.add(validMatch(rank, rank));
    }
    var maximum = result("completed", null, 1, matches);
    assertEquals(20, maximum.matches().size());
    matches.add(validMatch(20, 21));
    assertEquals(20, maximum.matches().size());
    assertThrows(UnsupportedOperationException.class, () -> maximum.matches().clear());
    assertInvalid(() -> result("completed", null, 1, matches));
    assertInvalid(() -> result("completed", null, 1, List.of(validMatch(1, 1), validMatch(2, 1))));
    assertInvalid(() -> result("completed", null, 1, List.of(validMatch(1, 1), validMatch(3, 2))));
  }

  private static Arguments badMatch(
      String scenario,
      int rank,
      int page,
      int start,
      int end,
      String text,
      String sourceSha,
      String textSha) {
    return Arguments.of(
        scenario,
        (Executable) () -> match(rank, page, start, end, text, sourceSha, textSha, 0.01, null));
  }

  private static RetrievalTestMatch validMatch(int rank, int page) {
    return match(rank, page, 0, 2, "原文", SOURCE_SHA, sha("原文"), 0.01, null);
  }

  private static RetrievalTestMatch match(
      int rank,
      int page,
      int start,
      int end,
      String text,
      String sourceSha,
      String textSha,
      double retrievalScore,
      Double rerankScore) {
    return new RetrievalTestMatch(
        rank,
        "synthetic-document",
        "synthetic-revision",
        "synthetic.txt",
        sourceSha,
        "java-text-parser-v2-monotonic-codepoints",
        page,
        start,
        end,
        text,
        textSha,
        retrievalScore,
        rerankScore);
  }

  private static RetrievalTestResult result(
      String status, String reason, int scopeCount, List<RetrievalTestMatch> matches) {
    return new RetrievalTestResult(TEST_ID, 1, status, reason, scopeCount, "rrf", matches);
  }

  private static RetrievalTestCommand request(String json) {
    return RetrievalTestRequestMapper.command(json.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private static ApplicationException assertInvalid(Executable executable) {
    var failure = assertThrows(ApplicationException.class, executable);
    assertEquals(FailureKind.INVALID_INPUT, failure.kind());
    assertEquals("invalid_request", failure.code());
    return failure;
  }
}
