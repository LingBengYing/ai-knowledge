package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisualAnswerServiceTest {
  @TempDir Path directory;

  @Test
  void explicitEmptySelectionRefusesWithDurableTraceAndNoExternalRequests() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      var vision = new RecordingVision();
      try (var answers =
          new VisualAnswerService(
              context.evidence,
              context.models,
              vision,
              context.projection,
              context.target,
              Duration.ofSeconds(10),
              1)) {
        var result =
            answers.answer(
                context.owner,
                new AnswerCommand(
                    "What shapes are in the image?", DocumentSelection.selected(List.of())));
        assertEquals("abstained", result.status());
        assertEquals("empty_scope", result.reason());
        assertTrue(result.citations().isEmpty());
        assertTrue(context.models.calls.isEmpty());
        assertTrue(context.projection.calls.isEmpty());
        assertTrue(vision.calls.isEmpty());
        assertEquals(
            1, context.scalar("SELECT COUNT(*) FROM query_traces WHERE outcome='abstained'"));
      }
    }
  }

  @Test
  void answersFromOriginalAndNeverUsesRecallCaptionAsAQuote() throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      var vision = new RecordingVision();
      String doc = publish(context, vision);
      try (var answers = service(context, vision)) {
        var result =
            answers.answer(
                context.owner,
                new AnswerCommand(
                    "Name both shapes and colors.", DocumentSelection.selected(List.of(doc))));
        assertEquals("answered", result.status(), result.reason());
        assertEquals(List.of("draft", "verify"), vision.calls);
        assertEquals(List.of("embed", "rerank"), context.models.calls);
        assertFalse(result.answer().contains("green triangle"));
        var citation = result.citations().getFirst();
        assertEquals("image_region", citation.kind());
        assertEquals(List.of(0.0, 0.0, 1.0, 1.0), citation.bbox());
        assertEquals(citation, answers.source(context.owner, result.answerId(), 1).citation());
        assertArrayEquals(
            VisualSyntheticFixture.image("png").content(),
            answers.content(context.owner, result.answerId(), 1).content());
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM corpus_pages"));
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM corpus_segments"));
        assertEquals(1, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
      }
    }
  }

  @Test
  void oneUnsupportedFactRefusesTheWholeImageAnswer() throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      var vision = new RecordingVision();
      vision.supported = List.of(true, false);
      String doc = publish(context, vision);
      try (var answers = service(context, vision)) {
        var result =
            answers.answer(
                context.owner,
                new AnswerCommand("Name both shapes.", DocumentSelection.selected(List.of(doc))));
        assertEquals("abstained", result.status());
        assertEquals("unsupported_claims", result.reason());
        assertTrue(result.citations().isEmpty());
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
      }
    }
  }

  @Test
  void uncitedSelectedTextRevocationStopsSecondImageCallAndFinalAnswer() throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      var vision = new RecordingVision();
      String doc = publish(context, vision);
      String uncited = context.publish("selected.txt", "Unrelated selected document.");
      vision.onDraft = () -> context.revoke(uncited);
      try (var answers = service(context, vision)) {
        var result =
            answers.answer(
                context.owner,
                new AnswerCommand(
                    "Name both shapes.", DocumentSelection.selected(List.of(doc, uncited))));
        assertEquals("abstained", result.status());
        assertEquals("scope_changed", result.reason());
        assertEquals(List.of("draft"), vision.calls);
        assertTrue(result.citations().isEmpty());
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
        assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      }
    }
  }

  private static VisualAnswerService service(AnswerTestContext context, RecordingVision vision) {
    return new VisualAnswerService(
        context.evidence,
        context.models,
        vision,
        context.projection,
        context.target,
        Duration.ofSeconds(10),
        1);
  }

  private static String publish(AnswerTestContext context, RecordingVision vision)
      throws Exception {
    var store = context.authority.store();
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            new VisualIngestionOptions(vision.revision()));
    byte[] original = VisualSyntheticFixture.image("png").content();
    ingestion.uploadDocument(context.owner, "shapes.png", "image/png", original);
    var claim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
    assertTrue(
        ingestion.completeVisualIngestion(
            claim, new ImageRecall("Recall marker: a green triangle.", vision.revision())));
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

  static final class RecordingVision implements VisionModels {
    final List<String> calls = new ArrayList<>();
    Runnable onDraft = () -> {};
    boolean complete = true;
    List<Boolean> supported = List.of(true, true);

    @Override
    public Description describe(VisualImage image) {
      calls.add("describe");
      return new Description("Recall marker: a green triangle.");
    }

    @Override
    public Draft draft(String question, VisualImage image) {
      calls.add("draft");
      onDraft.run();
      return new Draft(false, List.of("There is a blue circle.", "There is a red square."));
    }

    @Override
    public Verification verify(String question, VisualImage image, List<String> claims) {
      calls.add("verify");
      return new Verification(complete, supported);
    }

    @Override
    public String revision() {
      return "test-visual-library-v1";
    }
  }
}
