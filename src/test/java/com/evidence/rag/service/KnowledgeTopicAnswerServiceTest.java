package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.KnowledgeAnswerResult;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real authority, retrieval, trace and source lifecycle; only external Adapters are substitutes.
 */
class KnowledgeTopicAnswerServiceTest {
  private static final String PAGE =
      "SYNTHETIC ACCEPTANCE MATERIAL\nProject Cedar Beacon\n"
          + "LAUNCH DATE\nNovember 18, 2026\nBUDGET\nCNY 48600";
  private static final String TOPIC_PAGE = "青榆灯塔项目\n预算：CNY 48600";
  private static final String OTHER = "An unrelated maintenance record.";
  private static final Duration DEADLINE = Duration.ofSeconds(10);
  @TempDir Path directory;

  @Test
  void anotherLoggedInMemberCanAnswerAndReopenSourcesButAnotherWorkspaceCannot() {
    try (var fixture = new Fixture(directory)) {
      String document = fixture.context.publish("sample.txt", PAGE);
      var member = new com.evidence.rag.model.domain.Actor("org-main", "member-two");
      var otherWorkspace = new com.evidence.rag.model.domain.Actor("org-other", "member-two");
      var result =
          fixture.answers.answer(
              member, new AnswerCommand("介绍项目。".repeat(5000), DocumentSelection.allDocuments()));
      assertEquals("answered", result.status(), result.reason());
      assertEquals(document, result.citations().getFirst().documentId());
      assertEquals(
          result.citations().getFirst(),
          fixture.answers.source(fixture.context.owner, result.answerId(), 1).citation());
      org.junit.jupiter.api.Assertions.assertThrows(
          com.evidence.rag.exception.ApplicationException.class,
          () -> fixture.answers.source(otherWorkspace, result.answerId(), 1));
      assertEquals(1, fixture.models.syntheses);
      assertEquals(0, fixture.models.verifications);
    }
  }

  @Test
  void ordinaryQuestionUsesFullWorkspaceAndOnlyOneSynthesis() {
    try (var fixture = new Fixture(directory)) {
      String document = fixture.context.publish("sample.txt", PAGE);
      fixture.context.publish("maintenance.txt", OTHER);
      var result =
          fixture.answers.answer(
              fixture.context.owner,
              new AnswerCommand("介绍一下这个项目，资料未说明的部分请指出。", DocumentSelection.selected(List.of())));
      assertEquals("answered", result.status(), result.reason());
      assertEquals(1, fixture.models.syntheses);
      assertEquals(0, fixture.models.generalExtractions);
      assertEquals(0, fixture.models.topicExtractions);
      assertEquals(0, fixture.models.verifications);
      assertEquals(document, result.citations().getFirst().documentId());
      assertEquals(PAGE, result.citations().getFirst().quote());
      assertEquals(
          result.citations().getFirst(),
          fixture.answers.source(fixture.context.owner, result.answerId(), 1).citation());
    }
  }

  @Test
  void projectHeadingAnswersAndReopensTheSameOriginalSourceAfterRestart() {
    KnowledgeAnswerResult answer;
    try (var fixture = new Fixture(directory)) {
      String document = fixture.context.publish("sample.txt", PAGE);
      String other = fixture.context.publish("maintenance.txt", OTHER);
      answer =
          fixture.answers.answer(
              fixture.context.owner,
              new AnswerCommand("Project", DocumentSelection.selected(List.of(document, other))));
      assertEquals("answered", answer.status(), answer.reason());
      assertNull(answer.reason());
      assertEquals("The project is Cedar Beacon. [1]", answer.answer());
      assertEquals(1, fixture.models.syntheses);
      assertEquals(0, fixture.models.verifications);
      assertEquals(
          java.util.Set.of(PAGE, OTHER),
          fixture.models.contextOnly.stream()
              .map(TextModels.SynthesisContext::context)
              .collect(java.util.stream.Collectors.toSet()));
      assertEquals(1, answer.citations().size());
      var citation = answer.citations().getFirst();
      assertEquals(document, citation.documentId());
      assertEquals(1, citation.page());
      assertTrue(citation.quote().contains("Project Cedar Beacon"));
      assertTrue(PAGE.contains(citation.quote()));
      assertEquals(
          citation, fixture.answers.source(fixture.context.owner, answer.answerId(), 1).citation());
    }
    try (var reopened = new Fixture(directory)) {
      assertEquals(
          answer.citations().getFirst(),
          reopened.answers.source(reopened.context.owner, answer.answerId(), 1).citation());
      assertEquals(0, reopened.models.requests);
    }
  }

  @Test
  void keywordAnswerDoesNotInvokeAnIndependentRefusalGate() {
    try (var fixture = new Fixture(directory)) {
      fixture.models.approve = false;
      String document = fixture.context.publish("sample.txt", PAGE);
      var result =
          fixture.answers.answer(
              fixture.context.owner,
              new AnswerCommand("Project", DocumentSelection.selected(List.of(document))));
      assertEquals("answered", result.status());
      assertNull(result.reason());
      assertEquals(0, fixture.models.verifications);
      assertEquals(1, result.citations().size());
      assertTrue(result.answer().contains("Cedar Beacon"));
    }
  }

  @Test
  void keywordQuestionUsesOriginalCandidatesWithoutMandatoryExtraction() {
    try (var fixture = new Fixture(directory)) {
      fixture.models.fragmentTopicExtraction = true;
      String document = fixture.context.publish("lighthouse.txt", TOPIC_PAGE);
      String other = fixture.context.publish("maintenance.txt", OTHER);
      var result =
          fixture.answers.answer(
              fixture.context.owner,
              new AnswerCommand("灯塔", DocumentSelection.selected(List.of(document, other))));
      assertEquals("answered", result.status(), result.reason());
      assertNull(result.reason());
      assertEquals("青榆灯塔项目的预算为CNY 48600。 [1]", result.answer());
      assertEquals(0, fixture.models.topicExtractions);
      assertEquals(0, fixture.models.generalExtractions);
      assertEquals(1, fixture.models.syntheses);
      assertEquals(0, fixture.models.verifications);
      assertEquals(
          java.util.Set.of(TOPIC_PAGE, OTHER),
          fixture.models.contextOnly.stream()
              .map(TextModels.SynthesisContext::context)
              .collect(java.util.stream.Collectors.toSet()));
      assertEquals(1, result.citations().size());
      assertEquals(document, result.citations().getFirst().documentId());
      assertEquals(TOPIC_PAGE, result.citations().getFirst().quote());
      assertEquals(
          result.citations().getFirst(),
          fixture.answers.source(fixture.context.owner, result.answerId(), 1).citation());
    }
  }

  @Test
  void budgetUsesOriginalSnippetWithoutHandWrittenFieldProof() {
    try (var fixture = new Fixture(directory)) {
      String document = fixture.context.publish("sample.txt", PAGE);
      var result =
          fixture.answers.answer(
              fixture.context.owner,
              new AnswerCommand("BUDGET", DocumentSelection.selected(List.of(document))));
      assertEquals("answered", result.status(), result.reason());
      assertEquals("CNY 48600 [1]", result.answer());
      assertEquals(PAGE, result.citations().getFirst().quote());
      assertEquals(0, fixture.models.generalExtractions);
      assertEquals(0, fixture.models.topicExtractions);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AnswerTestContext context;
    final Models models = new Models();
    final ManagedTextRuntime runtime;
    final ProductHelpService retrieval;
    final KnowledgeAnswerService answers;

    Fixture(Path path) {
      context = new AnswerTestContext(path, DEADLINE, 1);
      runtime =
          new ManagedTextRuntime(
              context.authority.store(),
              (version, configuration) -> {
                var textAnswers =
                    new AnswerService(
                        context.evidence, models, context.projection, context.target, DEADLINE, 1);
                var indexing =
                    new IndexingTaskProcessor(
                        context.authority.indexing(),
                        context.owner.workspaceId(),
                        context.target,
                        DEADLINE,
                        ignored -> {
                          throw new AssertionError("Answering must not index");
                        });
                return new TextRuntimeSnapshot(
                    version,
                    models,
                    context.projection,
                    context.target,
                    textAnswers,
                    indexing,
                    () -> {});
              });
      var snapshot = runtime.prepare(1, ManagedTextTestFixture.configuration());
      try (var lease = context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        runtime.install(snapshot, lease, () -> {});
      }
      retrieval = new ProductHelpService(context.evidence, runtime, DEADLINE, 1);
      answers =
          new KnowledgeAnswerService(
              context.evidence,
              retrieval,
              runtime,
              new KnowledgeTraceService(context.authority.store(), context.evidence),
              DEADLINE,
              1);
    }

    @Override
    public void close() {
      answers.close();
      retrieval.close();
      runtime.close();
      context.close();
    }
  }

  private static final class Models implements TextModels {
    int requests;
    int generalExtractions;
    int topicExtractions;
    int syntheses;
    int verifications;
    boolean approve = true;
    boolean fragmentTopicExtraction;
    List<SynthesisContext> contextOnly = List.of();

    @Override
    public Synthesis answerKnowledge(String question, List<SynthesisEvidence> evidence) {
      requests++;
      syntheses++;
      contextOnly =
          evidence.stream().map(item -> new SynthesisContext(item.id(), item.context())).toList();
      var source =
          evidence.stream()
              .filter(item -> item.quote().equals(PAGE) || item.quote().equals(TOPIC_PAGE))
              .findFirst()
              .orElseThrow();
      return new Synthesis(
          false,
          List.of(
              new Statement(
                  question.equals("BUDGET")
                      ? "CNY 48600"
                      : question.equals("灯塔")
                          ? "青榆灯塔项目的预算为CNY 48600。"
                          : "The project is Cedar Beacon.",
                  List.of(source.id()))));
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
      requests++;
      return texts.stream().map(ignored -> List.of(1.0, 0.0)).toList();
    }

    @Override
    public List<Ranked> rerank(String query, List<String> texts) {
      requests++;
      return IntStream.range(0, texts.size()).mapToObj(i -> new Ranked(i, 1.0)).toList();
    }

    @Override
    public Extraction extract(String query, List<Evidence> evidence) {
      requests++;
      generalExtractions++;
      if (fragmentTopicExtraction) {
        var source = evidence.stream().filter(item -> item.text().equals(TOPIC_PAGE)).findFirst();
        return source
            .map(
                item ->
                    new Extraction(
                        List.of(
                            new Quote(item.id(), "青榆灯塔项目"), new Quote(item.id(), "预算：CNY 48600")),
                        false))
            .orElseGet(() -> new Extraction(List.of(), true));
      }
      var quotes =
          evidence.stream()
              .filter(item -> item.text().equals(PAGE))
              .map(item -> new Quote(item.id(), item.text()))
              .toList();
      return new Extraction(quotes, quotes.isEmpty());
    }

    @Override
    public Extraction extractTopic(String query, List<Evidence> evidence) {
      requests++;
      topicExtractions++;
      var quotes =
          evidence.stream()
              .filter(item -> item.text().equals(PAGE) || item.text().equals(TOPIC_PAGE))
              .map(item -> new Quote(item.id(), item.text()))
              .toList();
      return new Extraction(quotes, quotes.isEmpty());
    }

    @Override
    public Extraction extractKnowledge(String query, List<KnowledgeExtractionEvidence> evidence) {
      return extract(
          query, evidence.stream().map(item -> new Evidence(item.id(), item.text())).toList());
    }

    @Override
    public Synthesis synthesize(
        String question, List<SynthesisEvidence> evidence, List<SynthesisContext> contexts) {
      requests++;
      syntheses++;
      contextOnly = List.copyOf(contexts);
      return new Synthesis(
          false,
          List.of(
              new Statement(
                  question.equals("BUDGET")
                      ? "CNY 48600"
                      : question.equals("灯塔")
                          ? "青榆灯塔项目的预算为CNY 48600。"
                          : "The project is Cedar Beacon.",
                  List.of(evidence.getFirst().id()))));
    }

    @Override
    public boolean verifySynthesis(
        String question,
        Synthesis synthesis,
        List<SynthesisEvidence> evidence,
        List<SynthesisContext> contexts) {
      requests++;
      verifications++;
      assertEquals(contextOnly, contexts);
      return approve;
    }

    @Override
    public String revision() {
      return "test-answer-model-v1";
    }
  }
}
