package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextVisualCoexistenceTest {
  private static final String QUESTION = "星港项目的识别码是什么？";
  private static final String TEXT = "星港项目的识别码为A-42。";
  private static final String CAPTION = "Recall marker: a green triangle.";
  private static final String VISUAL_REVISION = "test-text-visual-coexistence-v1";

  @TempDir Path directory;

  @ParameterizedTest(name = "allDocuments={0}")
  @ValueSource(booleans = {true, false})
  void answersTextFromMixedLibraryForAllAndSelectedScopes(boolean allDocuments) throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String textDocument = context.publish("plan.txt", TEXT);
      String imageDocument = publishImage(context);
      var selection =
          allDocuments
              ? DocumentSelection.allDocuments()
              : DocumentSelection.selected(List.of(textDocument, imageDocument));
      var snapshot = context.evidence.snapshot(context.owner, selection, context.target);
      assertEquals(2, snapshot.publications().size());
      var generations = new TreeMap<String, String>();
      snapshot
          .publications()
          .forEach(p -> generations.put(p.documentId(), p.projectionGenerationId()));
      assertEquals(
          2,
          context
              .projection
              .data
              .search(
                  new RetrievalProjection.Query(
                      QUESTION,
                      List.of(1.0, 0.0),
                      new RetrievalProjection.AuthorizedScope(
                          context.owner.workspaceId(), generations),
                      16))
              .size());

      var answer = context.answers.answer(context.owner, new AnswerCommand(QUESTION, selection));

      assertEquals("answered", answer.status(), answer.reason());
      assertTrue(answer.answer().contains("A-42"));
      assertFalse(answer.answer().contains(CAPTION));
      assertEquals(Set.of(textDocument), context.projection.lastScope.documentRevisions().keySet());
      assertEquals(List.of("embed", "rerank", "extract"), context.models.calls);
      assertEquals(List.of(TEXT), context.models.lastEvidence.stream().map(e -> e.text()).toList());
      assertEquals(1, answer.citations().size());
      var citation = answer.citations().getFirst();
      assertEquals(textDocument, citation.documentId());
      assertEquals("plan.txt", citation.filename());
      assertEquals(
          citation, context.answers.source(context.owner, answer.answerId(), 1).citation());
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
    }
  }

  @Test
  void nonCandidateImageRevocationDuringTextExtractionRefusesWithCompleteScopeTrace()
      throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String textDocument = context.publish("plan.txt", TEXT);
      String imageDocument = publishImage(context);
      context.models.onExtract = () -> context.revoke(imageDocument);

      var answer =
          context.answers.answer(
              context.owner,
              new AnswerCommand(
                  QUESTION, DocumentSelection.selected(List.of(textDocument, imageDocument))));

      assertEquals("abstained", answer.status());
      assertEquals("scope_changed", answer.reason());
      assertFalse(answer.answer().contains("A-42"));
      assertTrue(answer.citations().isEmpty());
      assertEquals(Set.of(textDocument), context.projection.lastScope.documentRevisions().keySet());
      assertEquals(List.of("embed", "rerank", "extract"), context.models.calls);
      assertEquals(List.of(TEXT), context.models.lastEvidence.stream().map(e -> e.text()).toList());
      assertEquals(
          1, context.scalar("SELECT COUNT(*) FROM query_traces WHERE outcome='abstained'"));
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
    }
  }

  private static String publishImage(AnswerTestContext context) throws Exception {
    var store = context.authority.store();
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            new VisualIngestionOptions(VISUAL_REVISION));
    ingestion.uploadDocument(
        context.owner, "shapes.png", "image/png", VisualSyntheticFixture.image("png").content());
    var claim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
    assertTrue(ingestion.completeVisualIngestion(claim, new ImageRecall(CAPTION, VISUAL_REVISION)));
    context.authority.createIndexing(context.owner, claim.documentId(), context.target);
    var indexing = context.authority.claimIndexing(context.owner.workspaceId()).orElseThrow();
    var entries =
        indexing.items().stream()
            .map(
                item ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            indexing.projectionGenerationId(), item.evidenceId()),
                        context.owner.workspaceId(),
                        indexing.documentId(),
                        indexing.projectionGenerationId(),
                        item.recallText(),
                        List.of(1.0, 0.0)))
            .toList();
    context.projection.data.initialize();
    context.projection.data.upsert(entries);
    var digests = new TreeMap<String, String>();
    entries.forEach(
        entry -> digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            context.owner.workspaceId(),
            indexing.documentId(),
            indexing.projectionGenerationId(),
            digests);
    assertTrue(
        context.authority.completeIndexing(
            indexing, digests, context.projection.data.verify(manifest)));
    return claim.documentId();
  }
}
