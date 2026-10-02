package com.evidence.rag.service;

import static com.evidence.rag.service.VideoIngestionServiceTest.OWNER;
import static com.evidence.rag.service.VideoIngestionServiceTest.envelope;
import static com.evidence.rag.service.VideoIngestionServiceTest.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ImageOcr;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoOcrIngestionTaskProcessorTest {
  @TempDir Path directory;

  @Test
  void v2AdmissionFreezesVideoIdentityWithoutTakingOldOctetStreamAudioInputs() {
    String revision = "java-video-compiler-v2:" + "d".repeat(64);
    try (var store = new SqliteAuthorityStore(directory)) {
      var authority = service(store, revision);
      assertEquals("video/mp4", authority.prepareUpload("clip.mp4", "video/mp4"));
      assertEquals("audio/mp4", authority.prepareUpload("clip.mp4", "application/octet-stream"));
      var task = authority.uploadDocument(OWNER, "clip.mp4", "video/mp4", envelope("clip.mp4"));
      var claim = authority.claimIngestion(OWNER.workspaceId()).orElseThrow();
      assertEquals(task.taskId(), claim.jobId());
      assertEquals(revision, claim.parserRevision());
      assertEquals("video/mp4", claim.mimeType());
    }
  }

  @Test
  void persistentTaskCompilesAndStoresBothTextAndExplicitlyBlankVideoFrames() throws Exception {
    var fixture = new Fixture();
    var compiler = fixture.compiler(true);
    assertTrue(compiler.revision().startsWith("java-video-compiler-v2:"));
    try (var store = new SqliteAuthorityStore(directory)) {
      var authority = service(store, compiler.revision());
      var task = authority.uploadDocument(OWNER, "clip.mp4", "video/mp4", envelope("clip.mp4"));
      var claim = authority.claimIngestion(OWNER.workspaceId()).orElseThrow();
      processor(authority, compiler).process(claim);
      assertEquals("parsed", authority.ingestionStatus(OWNER, task.taskId()).state());
      assertEquals(1, fixture.decodeCalls);
      assertEquals(2, fixture.captionCalls);
      assertEquals(2, fixture.ocrCalls);
      var compilation =
          store.transaction(
              () ->
                  new IngestionRepository(store)
                      .findVideoCompilation(claim.revisionId())
                      .orElseThrow());
      assertNotNull(compilation.ocr());
      assertEquals(fixture.revision(), compilation.ocr().ocrRevision());
      assertEquals("Budget 42\n", compilation.ocr().frames().getFirst().text());
      assertEquals("", compilation.ocr().frames().getLast().text());
      assertTrue(compilation.ocr().frames().getLast().segments().isEmpty());
    }
  }

  @Test
  void finalFrameOcrFailureFailsTheWholeTaskWithoutPersistingPartialVideo() throws Exception {
    var fixture = new Fixture();
    fixture.failFinal = true;
    var compiler = fixture.compiler(true);
    assertTrue(compiler.revision().startsWith("java-video-compiler-v2:"));
    try (var store = new SqliteAuthorityStore(directory)) {
      var authority = service(store, compiler.revision());
      var task = authority.uploadDocument(OWNER, "clip.mp4", "video/mp4", envelope("clip.mp4"));
      var claim = authority.claimIngestion(OWNER.workspaceId()).orElseThrow();
      processor(authority, compiler).process(claim);
      assertEquals("failed", authority.ingestionStatus(OWNER, task.taskId()).state());
      assertEquals(
          "parser_output_invalid", authority.ingestionStatus(OWNER, task.taskId()).errorCode());
      assertEquals(2, fixture.ocrCalls);
      assertTrue(
          store.transaction(
              () ->
                  new IngestionRepository(store)
                      .findVideoCompilation(claim.revisionId())
                      .isEmpty()));
    }
  }

  @Test
  void resumedV2TaskCannotSilentlyRunThroughACompilerWithOcrNowDisabled() throws Exception {
    var fixture = new Fixture();
    String revision = "java-video-compiler-v2:" + "d".repeat(64);
    String task;
    try (var store = new SqliteAuthorityStore(directory)) {
      task =
          service(store, revision)
              .uploadDocument(OWNER, "clip.mp4", "video/mp4", envelope("clip.mp4"))
              .taskId();
    }
    var legacy = fixture.compiler(false);
    try (var store = new SqliteAuthorityStore(directory)) {
      var authority = service(store, legacy.revision());
      var claim = authority.claimIngestion(OWNER.workspaceId()).orElseThrow();
      processor(authority, legacy).process(claim);
      assertEquals("failed", authority.ingestionStatus(OWNER, task).state());
      assertEquals("parser_output_invalid", authority.ingestionStatus(OWNER, task).errorCode());
      assertEquals(0, fixture.decodeCalls + fixture.ocrCalls + fixture.captionCalls);
    }
  }

  private static IngestionTaskProcessor processor(
      IngestionService authority, VideoCompilationService compiler) {
    return new IngestionTaskProcessor(
        authority, OWNER.workspaceId(), Duration.ofSeconds(5), null, null, null, compiler);
  }

  private static final class Fixture implements VideoDecoder, AudioModels, VisionModels, ImageOcr {
    final VisualImage image = VisualSyntheticFixture.image("png");
    int decodeCalls;
    int captionCalls;
    int ocrCalls;
    boolean failFinal;

    Fixture() throws Exception {}

    VideoCompilationService compiler(boolean ocrEnabled) {
      var transcription = new AudioTranscriptionService(this, 1, Duration.ofSeconds(5));
      return ocrEnabled
          ? new VideoCompilationService(this, transcription, this, this, Duration.ofSeconds(5))
          : new VideoCompilationService(this, transcription, this, Duration.ofSeconds(5));
    }

    public String revision() {
      return "synthetic-ocr-video-v1";
    }

    public DecodedVideo decode(String filename, String mime, byte[] content) {
      decodeCalls++;
      return new DecodedVideo(
          ModelValues.sha256(content),
          revision(),
          0,
          2_000_000,
          List.of(
              new VideoFrame(0, 0, 100_000, image, 640, 320),
              new VideoFrame(1, 1_000_000, 100_000, image, 640, 320)),
          null);
    }

    public Description describe(VisualImage original) {
      captionCalls++;
      return new Description("A generic frame with no stated budget.");
    }

    public Optional<ParsedImage> read(VisualImage original) {
      ocrCalls++;
      if (ocrCalls == 2) {
        if (failFinal) {
          throw new TextParser.Failure("parser_invalid_output");
        }
        return Optional.empty();
      }
      return Optional.of(
          new ParsedImage(
              new ParsedText(
                  List.of(new TextPage(1, "Budget 42\n")),
                  List.of(new TextSegment(0, 1, 0, 9, "Budget 42"))),
              new ImageDimensions(640, 320),
              List.of(
                  new ImageTextRegion(0, 6, 0, 0, 40, 10),
                  new ImageTextRegion(7, 9, 50, 0, 70, 10))));
    }

    public Transcript transcribe(byte[] wav) {
      throw new AssertionError("No audio in silent fixture");
    }

    public Draft draft(String question, VisualImage image) {
      throw new AssertionError("No answers during ingestion");
    }

    public Verification verify(String question, VisualImage image, List<String> claims) {
      throw new AssertionError("No answers during ingestion");
    }

    public void close() {}
  }
}
