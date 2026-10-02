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
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionItem;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoFrameRecall;
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
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real authority and full index protocol; synthetic compiler products do not prove media quality.
 */
class VideoIndexingServiceTest {
  private static final String VIDEO_COMPILER = "java-video-compiler-v1:" + "c".repeat(64);
  private static final String AUDIO_COMPILER = "java-audio-compiler-v1:" + "a".repeat(64);
  private static final String DECODER = "test-video-index-decoder-v1";
  private static final String VISION = "test-video-index-vision-v1";
  private static final String FIRST_CAPTION = "色".repeat(4095) + "😀";
  private static final String LAST_CAPTION = "末帧的召回描述。";
  private static final String FIRST_TRANSCRIPT = "视频音轨的首段转录。";
  private static final String LAST_TRANSCRIPT = "视频音轨的完整末段转录。";
  private static final String TEXT = "星港项目的识别码为A-42。";
  private static final String QUESTION = "星港项目的识别码是什么？";

  @TempDir Path directory;

  @ParameterizedTest(name = "audio={0}")
  @ValueSource(strings = {"speech", "silent", "absent"})
  void completeVideoPublicationUsesDistinctFrameAndTranscriptIdentitiesWithTheWholeManifest(
      String audioMode) throws Exception {
    var owner = new Actor("org-main", "owner");
    byte[] original = videoEnvelope();
    try (var store = new SqliteAuthorityStore(directory);
        var server = new IndexingTestServer();
        var worker =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(15))) {
      var management = new ManagementRepository(store);
      var permissions = new DocumentPermissionPolicy();
      var ingestion = ingestion(store, management, permissions);
      var indexing =
          new IndexingService(store, new IndexingRepository(store), management, permissions);
      var evidence =
          new EvidenceService(store, new EvidenceRepository(store), management, permissions);
      var upload = ingestion.uploadDocument(owner, "demo.mp4", "video/mp4", original);
      var source = ingestion.claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(ingestion.completeVideoIngestion(source, compilation(original, audioMode)));
      assertTrue(ingestion.parsedEvidence(owner, upload.documentId()).pages().isEmpty());
      assertTrue(ingestion.parsedEvidence(owner, upload.documentId()).segments().isEmpty());

      var queued = indexing.createIndexing(owner, upload.documentId(), server.target());
      var claim = indexing.claimIndexing(owner.workspaceId()).orElseThrow();
      var expectedIds =
          new ArrayList<>(
              List.of(
                  evidenceId("video-frame-", source.revisionId(), 0),
                  evidenceId("video-frame-", source.revisionId(), 1)));
      var expectedText = new ArrayList<>(List.of(FIRST_CAPTION, LAST_CAPTION));
      if ("speech".equals(audioMode)) {
        expectedIds.add(evidenceId("video-transcript-", source.revisionId(), 0));
        expectedIds.add(evidenceId("video-transcript-", source.revisionId(), 2));
        expectedText.addAll(List.of(FIRST_TRANSCRIPT, LAST_TRANSCRIPT));
      }
      assertEquals(queued.taskId(), claim.jobId());
      assertEquals(source.revisionId(), claim.revisionId());
      assertEquals(ModelValues.sha256(original), claim.sourceSha256());
      assertEquals(VIDEO_COMPILER, claim.parserRevision());
      assertEquals(expectedIds, claim.items().stream().map(ProjectionItem::evidenceId).toList());
      assertEquals(expectedText, claim.items().stream().map(ProjectionItem::recallText).toList());
      for (int index = 0; index < claim.items().size(); index++) {
        assertEquals(index, claim.items().get(index).ordinal());
        assertEquals(
            ModelValues.sha256(expectedText.get(index).getBytes(StandardCharsets.UTF_8)),
            claim.items().get(index).recallTextSha256());
      }
      assertTrue(indexing.isIndexingClaimCurrent(claim));
      var result = worker.index(claim);
      var missingTail = new TreeMap<>(result.entryDigests());
      missingTail.remove(
          RetrievalProjection.physicalSegmentId(
              claim.projectionGenerationId(), expectedIds.getLast()));
      var incomplete =
          assertThrows(
              ApplicationException.class,
              () -> indexing.completeIndexing(claim, missingTail, result.verified()));
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
      var publication = snapshot.publications().getFirst();
      assertEquals(indexed.indexPublicationId(), publication.publicationId());
      assertEquals(source.revisionId(), publication.sourceRevisionId());
      assertEquals(expectedIds.size(), publication.segmentCount());
      assertEquals(result.verified().manifestSha256(), publication.manifestSha256());
      assertEquals(2, scalar("SELECT COUNT(*) FROM video_frame_publication_entries"));
      assertEquals(
          "speech".equals(audioMode) ? 2 : 0,
          scalar("SELECT COUNT(*) FROM video_transcript_publication_entries"));
      assertEquals(
          "absent".equals(audioMode) ? 0 : 3,
          scalar("SELECT COUNT(*) FROM video_transcript_spans"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_pages"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_segments"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM image_evidence"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM audio_spans"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM index_publication_entries"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM image_publication_entries"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM audio_publication_entries"));
      assertTrue(evidence.textPublications(snapshot).isEmpty());
      assertTrue(evidence.imagePublications(snapshot).isEmpty());
      assertTrue(evidence.audioPublications(snapshot).isEmpty());
    }
  }

  @ParameterizedTest(name = "allDocuments={0}")
  @ValueSource(booleans = {true, false})
  void fourModalitiesKeepTheFullScopeWhenTextAnswersDoNotRetrieveVideo(boolean allDocuments)
      throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String textId = context.publish("plan.txt", TEXT);
      var store = context.authority.store();
      var ingestion =
          ingestion(store, new ManagementRepository(store), new DocumentPermissionPolicy());
      var image =
          ingestion.uploadDocument(
              context.owner,
              "shapes.png",
              "image/png",
              VisualSyntheticFixture.image("png").content());
      var imageClaim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
      assertTrue(ingestion.completeVisualIngestion(imageClaim, new ImageRecall("独立图片描述。", VISION)));
      publish(context, image.documentId());

      byte[] wav = AudioPcm.wav(new byte[32_000], 0, 32_000);
      var audio = ingestion.uploadDocument(context.owner, "meeting.wav", "audio/wav", wav);
      var audioClaim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
      assertTrue(
          ingestion.completeAudioIngestion(
              audioClaim,
              new AudioCompilation(
                  ModelValues.sha256(wav),
                  DECODER,
                  "test-asr-v1",
                  AUDIO_COMPILER,
                  1000,
                  List.of(new AudioTranscriptSpan(0, 0, 1000, "独立音频内容。")))));
      publish(context, audio.documentId());

      byte[] original = videoEnvelope();
      var video = ingestion.uploadDocument(context.owner, "demo.mp4", "video/mp4", original);
      var videoClaim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
      assertTrue(ingestion.completeVideoIngestion(videoClaim, compilation(original, "speech")));
      publish(context, video.documentId());
      var documentIds = List.of(textId, image.documentId(), audio.documentId(), video.documentId());
      var selection =
          allDocuments ? DocumentSelection.allDocuments() : DocumentSelection.selected(documentIds);
      var snapshot = context.evidence.snapshot(context.owner, selection, context.target);
      assertEquals(
          Set.copyOf(documentIds),
          Set.copyOf(
              snapshot.publications().stream().map(PublicationVersion::documentId).toList()));
      assertEquals(
          List.of(textId),
          context.evidence.textPublications(snapshot).stream()
              .map(PublicationVersion::documentId)
              .toList());
      assertEquals(
          List.of(image.documentId()),
          context.evidence.imagePublications(snapshot).stream()
              .map(PublicationVersion::documentId)
              .toList());
      assertEquals(
          List.of(audio.documentId()),
          context.evidence.audioPublications(snapshot).stream()
              .map(PublicationVersion::documentId)
              .toList());
      var answer = context.answers.answer(context.owner, new AnswerCommand(QUESTION, selection));
      assertEquals("answered", answer.status(), answer.reason());
      assertTrue(answer.answer().contains("A-42"));
      assertEquals(Set.of(textId), context.projection.lastScope.documentRevisions().keySet());
      assertEquals(List.of(TEXT), context.models.lastEvidence.stream().map(e -> e.text()).toList());
      assertEquals(1, answer.citations().size());
      assertEquals(textId, answer.citations().getFirst().documentId());
      assertEquals(4, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertFalse(answer.answer().contains("视频"));

      context.models.onExtract = () -> context.revoke(video.documentId());
      var revoked = context.answers.answer(context.owner, new AnswerCommand(QUESTION, selection));
      assertEquals("abstained", revoked.status());
      assertEquals("scope_changed", revoked.reason());
      assertTrue(revoked.citations().isEmpty());
      assertEquals(8, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
    }
  }

  private static IngestionService ingestion(
      SqliteAuthorityStore store,
      ManagementRepository management,
      DocumentPermissionPolicy permissions) {
    return new IngestionService(
        store,
        new IngestionRepository(store),
        management,
        permissions,
        null,
        new VisualIngestionOptions(VISION),
        AUDIO_COMPILER,
        VIDEO_COMPILER);
  }

  private static byte[] videoEnvelope() {
    return new byte[] {
      0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
      0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '2'
    };
  }

  private static VideoCompilation compilation(byte[] source, String audioMode) throws Exception {
    var image = VisualSyntheticFixture.image("png");
    var frames =
        List.of(
            new VideoFrameRecall(
                new VideoFrame(0, 0, 1_000_000, image, 640, 320),
                new ImageRecall(FIRST_CAPTION, VISION)),
            new VideoFrameRecall(
                new VideoFrame(1, 2_000_000, 1_000_000, image, 640, 320),
                new ImageRecall(LAST_CAPTION, VISION)));
    var audio =
        "absent".equals(audioMode)
            ? null
            : new AudioTranscription(
                ModelValues.sha256(source),
                DECODER,
                "test-asr-v1",
                "test-transcription-v1",
                48_000,
                List.of(
                    new AudioTranscriptSpan(
                        0, 0, 1000, "speech".equals(audioMode) ? FIRST_TRANSCRIPT : ""),
                    new AudioTranscriptSpan(1, 1000, 2000, "\n"),
                    new AudioTranscriptSpan(
                        2, 2000, 3000, "speech".equals(audioMode) ? LAST_TRANSCRIPT : "")));
    return new VideoCompilation(
        ModelValues.sha256(source), DECODER, VIDEO_COMPILER, 2_000_000, 3_000_000, frames, audio);
  }

  private static String evidenceId(String prefix, String revisionId, int ordinal) {
    return prefix
        + ModelValues.sha256((revisionId + "\0" + ordinal).getBytes(StandardCharsets.UTF_8));
  }

  private long scalar(String sql) throws Exception {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
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
