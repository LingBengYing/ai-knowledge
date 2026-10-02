package com.evidence.rag.service;

import static com.evidence.rag.service.VideoIngestionServiceTest.OWNER;
import static com.evidence.rag.service.VideoIngestionServiceTest.envelope;
import static com.evidence.rag.service.VideoIngestionServiceTest.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoIngestionTaskProcessorTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void frozenVideoContractUsesCompleteVideoCompilerRatherThanSameNameAudio(boolean audioTrack)
      throws Exception {
    var models = new Models();
    var decoder = new Decoder(audioTrack);
    var compiler = compiler(decoder, models);
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, compiler.revision());
      var task = service.uploadDocument(OWNER, "film.mp4", "video/mp4", envelope("film.mp4"));
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
      var processor = processor(service, compiler, models);
      processor.process(claim);
      assertEquals("parsed", service.ingestionStatus(OWNER, task.taskId()).state());
      assertEquals(1, decoder.calls);
      assertEquals(0, models.independentAudioCalls);
      assertEquals(audioTrack ? 3 : 0, models.asrCalls);
      assertEquals(2, models.captionCalls);
      var stored =
          store.transaction(
              () ->
                  new IngestionRepository(store)
                      .findVideoCompilation(claim.revisionId())
                      .orElseThrow());
      assertEquals(compiler.revision(), stored.compilerRevision());
      assertEquals(2, stored.frames().size());
      assertEquals(2_000_000, stored.timelineOriginUs());
      if (audioTrack) {
        assertEquals("", stored.audio().spans().getFirst().text());
        assertEquals(3000, stored.audio().spans().getLast().endMs());
      } else {
        assertNull(stored.audio());
      }
    }
  }

  @Test
  void wrongActualStreamTypeFailsWithoutModelsOrFallbackToAudio() throws Exception {
    var models = new Models();
    var decoder = new Decoder(true);
    decoder.rejectStream = true;
    var compiler = compiler(decoder, models);
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, compiler.revision());
      var task =
          service.uploadDocument(OWNER, "audio-only.mp4", "video/mp4", envelope("audio-only.mp4"));
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
      processor(service, compiler, models).process(claim);
      var failed = service.ingestionStatus(OWNER, task.taskId());
      assertEquals("failed", failed.state());
      assertEquals("unsupported_document", failed.errorCode());
      assertEquals(1, decoder.calls);
      assertEquals(0, models.asrCalls + models.captionCalls + models.independentAudioCalls);
      assertTrue(
          store.transaction(
              () ->
                  new IngestionRepository(store)
                      .findVideoCompilation(claim.revisionId())
                      .isEmpty()));
    }
  }

  @Test
  void restartedQueuedVideoDoesNotBecomeAudioWhenVideoFeatureIsDisabled() throws Exception {
    var models = new Models();
    var compiler = compiler(new Decoder(true), models);
    String job;
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, compiler.revision());
      job = service.uploadDocument(OWNER, "film.mp4", "video/mp4", envelope("film.mp4")).taskId();
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      var service = service(reopened, null);
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
      assertEquals("video/mp4", claim.mimeType());
      processor(service, null, models).process(claim);
      assertEquals("failed", service.ingestionStatus(OWNER, job).state());
      assertEquals("parser_output_invalid", service.ingestionStatus(OWNER, job).errorCode());
      assertEquals(0, models.independentAudioCalls + models.asrCalls + models.captionCalls);
    }
  }

  @Test
  void cancellationDuringCaptionDoesNotPublishPartialVideo() throws Exception {
    var models = new Models();
    var compiler = compiler(new Decoder(false), models);
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, compiler.revision());
      var task = service.uploadDocument(OWNER, "film.mp4", "video/mp4", envelope("film.mp4"));
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
      models.afterCaption = () -> service.cancelIngestion(OWNER, task.taskId());
      processor(service, compiler, models).process(claim);
      assertEquals("cancelled", service.ingestionStatus(OWNER, task.taskId()).state());
      assertEquals(1, models.captionCalls);
      assertTrue(
          store.transaction(
              () ->
                  new IngestionRepository(store)
                      .findVideoCompilation(claim.revisionId())
                      .isEmpty()));
    }
  }

  private static VideoCompilationService compiler(Decoder decoder, Models models) {
    return new VideoCompilationService(
        decoder,
        new AudioTranscriptionService(models, 1, Duration.ofSeconds(5)),
        models,
        Duration.ofSeconds(5));
  }

  private static IngestionTaskProcessor processor(
      IngestionService service, VideoCompilationService video, Models models) {
    var independentAudio =
        new AudioCompilationService(
            new AudioDecoder() {
              public String revision() {
                return "independent-audio-decoder";
              }

              public DecodedAudio decode(String filename, String mime, byte[] content) {
                models.independentAudioCalls++;
                return new DecodedAudio(ModelValues.sha256(content), revision(), new byte[32000]);
              }

              public void close() {}
            },
            models,
            1,
            Duration.ofSeconds(5));
    return new IngestionTaskProcessor(
        service, OWNER.workspaceId(), Duration.ofSeconds(5), null, null, independentAudio, video);
  }

  private static final class Decoder implements VideoDecoder {
    private final boolean audioTrack;
    private final VisualImage image;
    private boolean rejectStream;
    private int calls;

    Decoder(boolean audioTrack) throws Exception {
      this.audioTrack = audioTrack;
      image = VisualSyntheticFixture.image("png");
    }

    public String revision() {
      return "video-decoder-v1";
    }

    public DecodedVideo decode(String filename, String mime, byte[] content) {
      calls++;
      assertEquals("video/mp4", mime);
      if (rejectStream) {
        throw new TextParser.Failure("unsupported_document");
      }
      String sha = ModelValues.sha256(content);
      return new DecodedVideo(
          sha,
          revision(),
          2_000_000,
          3_000_000,
          List.of(
              new VideoFrame(0, 0, 1_000_000, image, 640, 320),
              new VideoFrame(1, 1_500_000, 500_000, image, 640, 320)),
          audioTrack ? new DecodedAudio(sha, revision(), new byte[96000]) : null);
    }

    public void close() {}
  }

  private static final class Models implements AudioModels, VisionModels {
    int asrCalls;
    int captionCalls;
    int independentAudioCalls;
    Runnable afterCaption = () -> {};

    public String revision() {
      return "synthetic-model-v1";
    }

    public Transcript transcribe(byte[] wav) {
      return new Transcript(List.of("", "合成声轨", "保留完整尾音").get(asrCalls++));
    }

    public Description describe(VisualImage image) {
      captionCalls++;
      afterCaption.run();
      return new Description("合成画面" + captionCalls);
    }

    public Draft draft(String question, VisualImage image) {
      throw new AssertionError("No video answers in ingestion");
    }

    public Verification verify(String question, VisualImage image, List<String> claims) {
      throw new AssertionError("No video answers in ingestion");
    }

    public void close() {}
  }
}
