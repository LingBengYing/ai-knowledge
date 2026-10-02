package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionItem;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.evidence.rag.worker.indexing.ProcessTextIndexer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AudioIndexingServiceTest {
  private static final String COMPILER = "java-audio-compiler-v1:" + "a".repeat(64);
  private static final String VISUAL = "test-audio-indexing-visual-v1";
  private static final String QUESTION = "星港项目的识别码是什么？";
  private static final String TEXT = "星港项目的识别码为A-42。";

  @TempDir Path directory;

  @Test
  void completeAudioPublicationUsesOnlyNonblankSpansAndTheExistingFullManifestWorker()
      throws Exception {
    var owner = new Actor("org-main", "owner");
    byte[] original = syntheticWav();
    String first = "首".repeat(4095) + "😀";
    String last = "音频末段事实。";
    try (var store = new SqliteAuthorityStore(directory);
        var server = new IndexingTestServer();
        var worker =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(15))) {
      var management = new ManagementRepository(store);
      var permissions = new DocumentPermissionPolicy();
      var ingestion =
          new IngestionService(
              store, new IngestionRepository(store), management, permissions, null, null, COMPILER);
      var indexing =
          new IndexingService(store, new IndexingRepository(store), management, permissions);
      var evidence =
          new EvidenceService(store, new EvidenceRepository(store), management, permissions);
      var upload = ingestion.uploadDocument(owner, "meeting.wav", "audio/wav", original);
      var source = ingestion.claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(ingestion.completeAudioIngestion(source, compilation(original, first, last)));
      assertTrue(ingestion.parsedEvidence(owner, upload.documentId()).pages().isEmpty());
      assertTrue(ingestion.parsedEvidence(owner, upload.documentId()).segments().isEmpty());

      var queued = indexing.createIndexing(owner, upload.documentId(), server.target());
      var claim = indexing.claimIndexing(owner.workspaceId()).orElseThrow();
      assertEquals(queued.taskId(), claim.jobId());
      assertEquals(source.revisionId(), claim.revisionId());
      assertEquals(ModelValues.sha256(original), claim.sourceSha256());
      assertEquals(COMPILER, claim.parserRevision());
      assertEquals(List.of(0, 1), claim.items().stream().map(ProjectionItem::ordinal).toList());
      assertEquals(
          List.of(first, last), claim.items().stream().map(ProjectionItem::recallText).toList());
      assertEquals(
          List.of(
              "audio-"
                  + ModelValues.sha256(
                      (source.revisionId() + "\0" + 0).getBytes(StandardCharsets.UTF_8)),
              "audio-"
                  + ModelValues.sha256(
                      (source.revisionId() + "\0" + 2).getBytes(StandardCharsets.UTF_8))),
          claim.items().stream().map(ProjectionItem::evidenceId).toList());
      assertTrue(indexing.isIndexingClaimCurrent(claim));

      var result = worker.index(claim);
      var incomplete =
          assertThrows(
              ApplicationException.class,
              () -> indexing.completeIndexing(claim, Map.of(), result.verified()));
      assertEquals("indexing_output_invalid", incomplete.code());
      assertEquals("processing", indexing.indexingStatus(owner, queued.taskId()).state());
      assertTrue(
          evidence
              .snapshot(owner, DocumentSelection.allDocuments(), server.target())
              .publications()
              .isEmpty());
      assertTrue(indexing.completeIndexing(claim, result.entryDigests(), result.verified()));
      var indexed = indexing.indexingStatus(owner, queued.taskId());
      assertEquals("indexed", indexed.state());
      assertNotNull(indexed.indexPublicationId());

      var snapshot =
          evidence.snapshot(
              owner, DocumentSelection.selected(List.of(upload.documentId())), server.target());
      assertEquals(1, snapshot.publications().size());
      var publication = snapshot.publications().getFirst();
      assertEquals(indexed.indexPublicationId(), publication.publicationId());
      assertEquals(source.revisionId(), publication.sourceRevisionId());
      assertEquals(ModelValues.sha256(original), publication.sourceSha256());
      assertEquals(2, publication.segmentCount());
      assertEquals(result.verified().manifestSha256(), publication.manifestSha256());
      assertTrue(evidence.textPublications(snapshot).isEmpty());
      assertTrue(evidence.imagePublications(snapshot).isEmpty());
      assertTrue(ingestion.parsedEvidence(owner, upload.documentId()).pages().isEmpty());
      assertTrue(ingestion.parsedEvidence(owner, upload.documentId()).segments().isEmpty());
    }
  }

  @ParameterizedTest(name = "allDocuments={0}")
  @ValueSource(booleans = {true, false})
  void mixedTextImageAndAudioKeepCompleteScopeWhileTextAnswersUseOnlyText(boolean allDocuments)
      throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String textDocument = context.publish("plan.txt", TEXT);
      var store = context.authority.store();
      var ingestion =
          new IngestionService(
              store,
              new IngestionRepository(store),
              new ManagementRepository(store),
              new DocumentPermissionPolicy(),
              null,
              new VisualIngestionOptions(VISUAL),
              COMPILER);
      var image =
          ingestion.uploadDocument(
              context.owner,
              "shapes.png",
              "image/png",
              VisualSyntheticFixture.image("png").content());
      var imageClaim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
      assertTrue(
          ingestion.completeVisualIngestion(
              imageClaim, new ImageRecall("Recall marker: a green triangle.", VISUAL)));
      publish(context, image.documentId());

      byte[] original = syntheticWav();
      var audio = ingestion.uploadDocument(context.owner, "meeting.wav", "audio/wav", original);
      var audioClaim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
      assertTrue(
          ingestion.completeAudioIngestion(
              audioClaim, compilation(original, "音频中的首段合成转录。", "音频中的末段合成转录。")));
      publish(context, audio.documentId());
      var selection =
          allDocuments
              ? DocumentSelection.allDocuments()
              : DocumentSelection.selected(
                  List.of(textDocument, image.documentId(), audio.documentId()));
      var snapshot = context.evidence.snapshot(context.owner, selection, context.target);
      assertEquals(
          Set.of(textDocument, image.documentId(), audio.documentId()),
          Set.copyOf(
              snapshot.publications().stream().map(PublicationVersion::documentId).toList()));
      assertEquals(
          List.of(textDocument),
          context.evidence.textPublications(snapshot).stream()
              .map(PublicationVersion::documentId)
              .toList());
      assertEquals(
          List.of(image.documentId()),
          context.evidence.imagePublications(snapshot).stream()
              .map(PublicationVersion::documentId)
              .toList());

      var answer = context.answers.answer(context.owner, new AnswerCommand(QUESTION, selection));
      assertEquals("answered", answer.status(), answer.reason());
      assertTrue(answer.answer().contains("A-42"));
      assertEquals(Set.of(textDocument), context.projection.lastScope.documentRevisions().keySet());
      assertEquals(List.of(TEXT), context.models.lastEvidence.stream().map(e -> e.text()).toList());
      assertEquals(1, answer.citations().size());
      assertEquals(textDocument, answer.citations().getFirst().documentId());
      assertEquals(
          answer.citations().getFirst(),
          context.answers.source(context.owner, answer.answerId(), 1).citation());
      assertEquals(3, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
      assertFalse(answer.answer().contains("合成转录"));
    }
  }

  private static byte[] syntheticWav() {
    byte[] pcm = new byte[96_000];
    return AudioPcm.wav(pcm, 0, pcm.length);
  }

  private static AudioCompilation compilation(byte[] original, String first, String last) {
    return new AudioCompilation(
        ModelValues.sha256(original),
        "test-audio-decoder-v1",
        "test-audio-model-v1",
        COMPILER,
        3000,
        List.of(
            new AudioTranscriptSpan(0, 0, 1000, first),
            new AudioTranscriptSpan(1, 1000, 2000, "\n"),
            new AudioTranscriptSpan(2, 2000, 3000, last)));
  }

  private static void publish(AnswerTestContext context, String documentId) {
    context.authority.createIndexing(context.owner, documentId, context.target);
    var claim = context.authority.claimIndexing(context.owner.workspaceId()).orElseThrow();
    var entries =
        claim.items().stream()
            .map(
                item ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            claim.projectionGenerationId(), item.evidenceId()),
                        context.owner.workspaceId(),
                        claim.documentId(),
                        claim.projectionGenerationId(),
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
            context.owner.workspaceId(), documentId, claim.projectionGenerationId(), digests);
    assertTrue(
        context.authority.completeIndexing(
            claim, digests, context.projection.data.verify(manifest)));
  }
}
