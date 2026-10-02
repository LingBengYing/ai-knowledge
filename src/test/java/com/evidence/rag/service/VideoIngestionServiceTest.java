package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoFrameRecall;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VideoIngestionServiceTest {
  static final Actor OWNER = new Actor("org", "owner");
  static final String AUDIO_PROFILE = "java-audio-compiler-v1:" + "a".repeat(64);
  static final String VIDEO_PROFILE = "java-video-compiler-v1:" + "b".repeat(64);
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({
    "film.mp4,video/mp4",
    "film.webm,video/webm",
    "film.mov,video/quicktime",
    "film.mkv,video/x-matroska"
  })
  void explicitVideoContractQueuesAndPreservesCompleteVideoEvidenceAcrossRestart(
      String filename, String mime) throws Exception {
    byte[] original = envelope(filename);
    var expected = compilation(VIDEO_PROFILE, ModelValues.sha256(original));
    String revision;
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, VIDEO_PROFILE);
      assertEquals(mime, service.prepareUpload(filename, mime));
      var queued = service.uploadDocument(OWNER, filename, mime, original);
      assertEquals("queued", queued.state());
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
      revision = claim.revisionId();
      assertEquals(queued.taskId(), claim.jobId());
      assertEquals(mime, claim.mimeType());
      assertEquals(VIDEO_PROFILE, claim.parserRevision());
      assertArrayEquals(original, claim.content());
      var document =
          store.transaction(
              () ->
                  new ManagementRepository(store)
                      .findAuthorizedDocument(OWNER, queued.documentId(), false)
                      .orElseThrow());
      assertEquals("video", document.documentType());
      assertEquals(filename, document.filename());
      assertTrue(service.completeVideoIngestion(claim, expected));
      assertEquals("parsed", service.ingestionStatus(OWNER, queued.taskId()).state());
      assertTrue(service.parsedEvidence(OWNER, queued.documentId()).pages().isEmpty());
      assertTrue(service.parsedEvidence(OWNER, queued.documentId()).segments().isEmpty());
      assertFalse(service.completeVideoIngestion(claim, expected));
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      var restored =
          reopened.transaction(
              () -> new IngestionRepository(reopened).findVideoCompilation(revision).orElseThrow());
      assertEquals(expected.sourceSha256(), restored.sourceSha256());
      assertEquals(expected.decoderRevision(), restored.decoderRevision());
      assertEquals(expected.compilerRevision(), restored.compilerRevision());
      assertEquals(expected.timelineOriginUs(), restored.timelineOriginUs());
      assertEquals(expected.durationUs(), restored.durationUs());
      assertEquals(expected.audio(), restored.audio());
      for (int index = 0; index < expected.frames().size(); index++) {
        var expectedFrame = expected.frames().get(index);
        var restoredFrame = restored.frames().get(index);
        assertEquals(expectedFrame.recall(), restoredFrame.recall());
        assertEquals(expectedFrame.frame().ordinal(), restoredFrame.frame().ordinal());
        assertEquals(
            expectedFrame.frame().presentationUs(), restoredFrame.frame().presentationUs());
        assertEquals(expectedFrame.frame().durationUs(), restoredFrame.frame().durationUs());
        assertEquals(expectedFrame.frame().width(), restoredFrame.frame().width());
        assertEquals(expectedFrame.frame().height(), restoredFrame.frame().height());
        assertEquals(
            expectedFrame.frame().image().mediaType(), restoredFrame.frame().image().mediaType());
        assertArrayEquals(
            expectedFrame.frame().image().content(), restoredFrame.frame().image().content());
      }
      assertEquals("", restored.audio().spans().getFirst().text());
      assertEquals(3000, restored.audio().spans().getLast().endMs());
      assertEquals(2, restored.frames().size());
    }
  }

  @Test
  void oldOctetStreamAudioContainersKeepTheirOriginalFrozenAudioIdentity() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, VIDEO_PROFILE);
      for (String name : List.of("voice.mp4", "voice.webm")) {
        String mime = name.endsWith(".mp4") ? "audio/mp4" : "audio/webm";
        assertEquals(mime, service.prepareUpload(name));
        assertEquals(mime, service.prepareUpload(name, "application/octet-stream"));
        var queued =
            service.uploadDocument(OWNER, name, "application/octet-stream", envelope(name));
        var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
        assertEquals(queued.taskId(), claim.jobId());
        assertEquals(mime, claim.mimeType());
        assertEquals(AUDIO_PROFILE, claim.parserRevision());
      }
    }
  }

  @Test
  void wrongCompilerSourceOrFrameMetadataCannotSealTheCurrentVideoClaim() throws Exception {
    byte[] original = envelope("film.mp4");
    String sha = ModelValues.sha256(original);
    var valid = compilation(VIDEO_PROFILE, sha);
    var first = valid.frames().getFirst();
    var sourceFrame = first.frame();
    var wrongSize =
        new VideoFrameRecall(
            new VideoFrame(
                0,
                sourceFrame.presentationUs(),
                sourceFrame.durationUs(),
                sourceFrame.image(),
                639,
                320),
            first.recall());
    var wrongMime =
        new VideoFrameRecall(
            new VideoFrame(
                0,
                sourceFrame.presentationUs(),
                sourceFrame.durationUs(),
                new VisualImage("image/jpeg", sourceFrame.image().content()),
                640,
                320),
            first.recall());
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, VIDEO_PROFILE);
      var queued = service.uploadDocument(OWNER, "film.mp4", "video/mp4", original);
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
      for (var invalid :
          List.of(
              compilation("java-video-compiler-v1:" + "c".repeat(64), sha),
              compilation(VIDEO_PROFILE, "d".repeat(64)),
              replacingFirstFrame(valid, wrongSize),
              replacingFirstFrame(valid, wrongMime))) {
        assertEquals(
            "parser_output_invalid",
            assertThrows(
                    ApplicationException.class,
                    () -> service.completeVideoIngestion(claim, invalid))
                .code());
        assertEquals("processing", service.ingestionStatus(OWNER, queued.taskId()).state());
        assertTrue(
            store.transaction(
                () ->
                    new IngestionRepository(store)
                        .findVideoCompilation(claim.revisionId())
                        .isEmpty()));
      }
      assertEquals(
          "parser_output_invalid",
          assertThrows(
                  ApplicationException.class, () -> service.completeVideoIngestion(claim, null))
              .code());
      assertTrue(service.completeVideoIngestion(claim, valid));
    }
  }

  @Test
  void cancelledClaimCannotWriteVideoEvidenceAndMalformedVideoConfigurationIsRejected()
      throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      assertEquals(
          "invalid_request",
          assertThrows(
                  ApplicationException.class,
                  () -> service(store, "java-audio-compiler-v1:" + "b".repeat(64)))
              .code());
      var service = service(store, VIDEO_PROFILE);
      var queued = service.uploadDocument(OWNER, "film.mp4", "video/mp4", envelope("film.mp4"));
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
      service.cancelIngestion(OWNER, queued.taskId());
      assertFalse(
          service.completeVideoIngestion(
              claim, compilation(VIDEO_PROFILE, ModelValues.sha256(claim.content()))));
      assertTrue(
          store.transaction(
              () ->
                  new IngestionRepository(store)
                      .findVideoCompilation(claim.revisionId())
                      .isEmpty()));
    }
  }

  private static VideoCompilation replacingFirstFrame(
      VideoCompilation original, VideoFrameRecall first) {
    return new VideoCompilation(
        original.sourceSha256(),
        original.decoderRevision(),
        original.compilerRevision(),
        original.timelineOriginUs(),
        original.durationUs(),
        List.of(first, original.frames().get(1)),
        original.audio());
  }

  static IngestionService service(SqliteAuthorityStore store, String videoProfile) {
    return new IngestionService(
        store,
        new IngestionRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        null,
        null,
        AUDIO_PROFILE,
        videoProfile);
  }

  static byte[] envelope(String filename) {
    return (filename.endsWith(".webm") || filename.endsWith(".mkv")
            ? "\u001aE\u00df\u00a3\u0000\u0000\u0000\u0000"
            : "\u0000\u0000\u0000\u0010ftypisom\u0000\u0000\u0000\u0000")
        .getBytes(StandardCharsets.ISO_8859_1);
  }

  static VideoCompilation compilation(String profile, String sha) throws Exception {
    var image = VisualSyntheticFixture.image("png");
    return new VideoCompilation(
        sha,
        "video-decoder-v1",
        profile,
        2_000_000,
        3_000_000,
        List.of(
            new VideoFrameRecall(
                new VideoFrame(0, 0, 1_000_000, image, 640, 320),
                new ImageRecall("合成蓝圆与红方块", "vision-v1")),
            new VideoFrameRecall(
                new VideoFrame(1, 1_500_000, 500_000, image, 640, 320),
                new ImageRecall("合成第二原帧", "vision-v1"))),
        new AudioTranscription(
            sha,
            "video-decoder-v1",
            "asr-v1",
            "transcription-v1",
            48000,
            List.of(
                new AudioTranscriptSpan(0, 0, 1000, ""),
                new AudioTranscriptSpan(1, 1000, 2000, "合成声轨"),
                new AudioTranscriptSpan(2, 2000, 3000, "保留完整尾音"))));
  }
}
