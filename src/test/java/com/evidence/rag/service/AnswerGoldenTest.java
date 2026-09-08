package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Frozen PDF end-to-end acceptance with explicit deterministic remote stand-ins, not quality CI.
 */
class AnswerGoldenTest {
  @TempDir Path directory;

  static Stream<Arguments> cases() throws Exception {
    JsonNode cases =
        JsonMapper.builder()
            .build()
            .readTree(Files.readAllBytes(Path.of("docs/evals/golden.json")));
    assertEquals(6, cases.size());
    return StreamSupport.stream(cases.spliterator(), false)
        .map(value -> Arguments.of(value.path("id").stringValue(), value));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void answersFrozenPdfCases(String caseId, JsonNode definition) throws Exception {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(10), 2)) {
      Path corpus = Path.of("src/test/resources/corpus");
      for (String name : List.of("星河制造差旅政策.pdf", "Atlas路由器运维手册.pdf", "不可信指令样例.pdf")) {
        fixture.publish(name, "application/pdf", Files.readAllBytes(corpus.resolve(name)));
      }
      String foreignDocument =
          fixture.publish(
              new Actor("org-other", "owner"),
              "另一组织薪酬资料.pdf",
              "application/pdf",
              Files.readAllBytes(corpus.resolve("另一组织薪酬资料.pdf")));
      assertEquals(4, fixture.scalar("SELECT COUNT(*) FROM index_publications"));
      var result =
          fixture.answers.answer(
              new Actor(definition.path("workspace").stringValue(), "owner"),
              new AnswerCommand(
                  definition.path("question").stringValue(), DocumentSelection.allDocuments()));
      assertEquals(definition.path("expected_status").stringValue(), result.status(), caseId);
      assertFalse(fixture.projection.lastScope.documentRevisions().containsKey(foreignDocument));
      assertEquals(3, fixture.projection.lastScope.documentRevisions().size());
      assertFalse(
          fixture.models.lastEvidence.stream().anyMatch(e -> e.text().contains("980")), caseId);
      if (definition.has("expected_answer_contains")) {
        assertTrue(
            result.answer().contains(definition.path("expected_answer_contains").stringValue()),
            caseId);
      }
      if (definition.has("expected_evidence_contains")) {
        assertTrue(
            result.citations().stream()
                .anyMatch(
                    citation ->
                        citation
                            .quote()
                            .contains(definition.path("expected_evidence_contains").stringValue())),
            caseId);
      }
      if (definition.has("forbidden_answer_contains")) {
        assertFalse(
            result.answer().contains(definition.path("forbidden_answer_contains").stringValue()),
            caseId);
      }
      if ("answered".equals(result.status())) {
        assertFalse(result.citations().isEmpty(), caseId);
        for (var citation : result.citations()) {
          assertEquals(
              citation,
              fixture
                  .answers
                  .source(fixture.owner, result.answerId(), citation.number())
                  .citation());
        }
      } else {
        assertTrue(result.citations().isEmpty(), caseId);
      }
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_traces"));
      assertEquals(3, fixture.scalar("SELECT COUNT(*) FROM query_trace_documents"));
    }
  }
}
