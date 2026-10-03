package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real queued claims and authority statuses; local model/decoder fixtures assert entry boundaries.
 */
@SuppressWarnings("try")
class ManagedMediaIngestionBoundaryTest {
  private static final Duration DEADLINE = Duration.ofSeconds(10);
  private static final String DESCRIPTION = "白色背景中左侧蓝圆、右侧红方块。";
  @TempDir Path directory;

  @Test
  void unconfiguredTextFailsQueuedVisualBeforeDescriptionAndPersistsSafeRetryableStatus()
      throws Exception {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var vision = new RecordingVision();
      var service = ingestion(fixture, null, vision, null, null);
      var queued = service.uploadDocument(fixture.context.owner, "shape.png", "image/png", image());
      assertEquals("queued", queued.state());
      var claim = service.claimIngestion(fixture.context.owner.workspaceId()).orElseThrow();
      assertEquals(queued.taskId(), claim.jobId());
      assertEquals(
          "processing", service.ingestionStatus(fixture.context.owner, queued.taskId()).state());

      process(fixture, processor(fixture, service, null, vision, null, null), claim);

      assertFailed(fixture, service, claim, "text_configuration_required");
      assertEquals(0, vision.descriptions.get());
      assertTrue(fixture.context.models.calls.isEmpty());
      assertTrue(fixture.context.projection.calls.isEmpty());
    }
  }

  @Test
  void visualQueuedUnderT1CannotDescribeAfterT2IsInstalledBeforeClaimExecution() throws Exception {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.activate(1);
      var vision = new RecordingVision();
      var service = ingestion(fixture, null, vision, null, null);
      var queued = service.uploadDocument(fixture.context.owner, "shape.png", "image/png", image());
      assertEquals("queued", queued.state());
      var target = activateDifferentTarget(fixture);
      assertFalse(target.equals(fixture.context.target));
      assertEquals(target, fixture.runtime.currentTarget());
      var claim = service.claimIngestion(fixture.context.owner.workspaceId()).orElseThrow();

      process(fixture, processor(fixture, service, null, vision, null, null), claim);

      assertFailed(fixture, service, claim, "media_text_configuration_mismatch");
      assertEquals(0, vision.descriptions.get());
      assertTrue(fixture.context.models.calls.isEmpty());
      assertTrue(fixture.context.projection.calls.isEmpty());
    }
  }

  @Test
  void matchingTargetProcessesTheActualImageAndSealsTheCompleteDescription() throws Exception {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.activate(1);
      var vision = new RecordingVision();
      var service = ingestion(fixture, null, vision, null, null);
      byte[] original = image();
      var queued =
          service.uploadDocument(fixture.context.owner, "shape.png", "image/png", original);
      var claim = service.claimIngestion(fixture.context.owner.workspaceId()).orElseThrow();

      process(fixture, processor(fixture, service, null, vision, null, null), claim);

      assertParsedVisual(fixture, service, claim);
      assertEquals(1, vision.descriptions.get());
      assertArrayEquals(original, vision.lastImage.content());
      assertEquals(ModelValues.sha256(original), ModelValues.sha256(claim.content()));
      assertEquals(queued.revisionId(), claim.revisionId());
      assertTrue(fixture.context.models.calls.isEmpty());
      assertTrue(fixture.context.projection.calls.isEmpty());
    }
  }

  @Test
  void originalNullGuardConstructorKeepsLegacyVisualProcessingWithoutManagedActivation()
      throws Exception {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var vision = new RecordingVision();
      var service = ingestion(fixture, null, vision, null, null);
      service.uploadDocument(fixture.context.owner, "shape.png", "image/png", image());
      var claim = service.claimIngestion(fixture.context.owner.workspaceId()).orElseThrow();
      var legacy =
          new IngestionTaskProcessor(
              service, fixture.context.owner.workspaceId(), DEADLINE, null, vision);

      process(fixture, legacy, claim);

      assertNull(fixture.runtime.currentTarget());
      assertParsedVisual(fixture, service, claim);
      assertEquals(1, vision.descriptions.get());
    }
  }

  @Test
  void unconfiguredTextDoesNotBlockOrdinaryTextParsingThroughTheRealChild() throws Exception {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var vision = new RecordingVision();
      var service = ingestion(fixture, null, vision, null, null);
      String text = "合成文字：上海住宿上限为650元。\n完整尾段。";
      var queued =
          service.uploadDocument(
              fixture.context.owner,
              "policy.txt",
              "text/plain",
              text.getBytes(StandardCharsets.UTF_8));
      var claim = service.claimIngestion(fixture.context.owner.workspaceId()).orElseThrow();

      process(fixture, processor(fixture, service, null, vision, null, null), claim);

      var status = service.ingestionStatus(fixture.context.owner, queued.taskId());
      assertEquals("parsed", status.state());
      assertNull(status.errorCode());
      assertEquals(
          text,
          service
              .parsedEvidence(fixture.context.owner, queued.documentId())
              .pages()
              .getFirst()
              .text());
      assertEquals(0, vision.descriptions.get());
      assertNull(fixture.runtime.currentTarget());
    }
  }

  @Test
  void unconfiguredTextDoesNotBlockOcrOnlyQueuedImageOrReplaceItsParserProfile() throws Exception {
    Path script = directory.resolve("synthetic-ocr");
    Path marker = directory.resolve("ocr-input-drained");
    Files.writeString(
        script,
        "#!/bin/sh\n/bin/cat >/dev/null\nprintf 'complete\\n' > "
            + shellQuote(marker.toString())
            + "\n/bin/cat <<'TSV'\n"
            + "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
            + "1\t1\t0\t0\t0\t0\t0\t0\t640\t320\t-1\t\n"
            + "5\t1\t1\t1\t1\t1\t10\t10\t100\t20\t99\tBudget42\nTSV\n");
    Files.setPosixFilePermissions(
        script,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
    var options = new ImageOcrOptions(script.toAbsolutePath().normalize(), "eng", "fixture-ocr-v1");
    try (var fixture = new ManagedTextTestFixture(directory.resolve("library"))) {
      var vision = new RecordingVision();
      var service = ingestion(fixture, options, null, null, null);
      var queued = service.uploadDocument(fixture.context.owner, "shape.png", "image/png", image());
      var claim = service.claimIngestion(fixture.context.owner.workspaceId()).orElseThrow();
      assertEquals(options.parserRevision(), claim.parserRevision());

      process(fixture, processor(fixture, service, options, vision, null, null), claim);

      var status = service.ingestionStatus(fixture.context.owner, queued.taskId());
      assertEquals("parsed", status.state());
      assertNull(status.errorCode());
      assertEquals("complete\n", Files.readString(marker));
      assertEquals(
          "Budget42\n",
          service
              .parsedEvidence(fixture.context.owner, queued.documentId())
              .pages()
              .getFirst()
              .text());
      assertEquals(0, vision.descriptions.get());
      assertNull(fixture.runtime.currentTarget());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void queuedVideoChecksManagedTargetBeforeAnyDecoderOrModel(boolean changedTarget)
      throws Exception {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      if (changedTarget) {
        fixture.activate(1);
      }
      var vision = new RecordingVision();
      var audio = new RecordingAudio();
      var decoder = new ForbiddenVideoDecoder();
      var video =
          new VideoCompilationService(
              decoder, new AudioTranscriptionService(audio, 1, DEADLINE), vision, DEADLINE);
      var service = ingestion(fixture, null, vision, null, video.revision());
      // Only envelope admission is exercised: the managed guard must reject before decoding it.
      var queued =
          service.uploadDocument(
              fixture.context.owner,
              "queued.mp4",
              "video/mp4",
              VideoIngestionServiceTest.envelope("queued.mp4"));
      assertEquals("queued", queued.state());
      if (changedTarget) {
        activateDifferentTarget(fixture);
      }
      var claim = service.claimIngestion(fixture.context.owner.workspaceId()).orElseThrow();

      process(fixture, processor(fixture, service, null, vision, null, video), claim);

      assertFailed(
          fixture,
          service,
          claim,
          changedTarget ? "media_text_configuration_mismatch" : "text_configuration_required");
      assertEquals(0, decoder.calls.get());
      assertEquals(0, vision.descriptions.get());
      assertEquals(0, audio.transcriptions.get());
      assertTrue(
          fixture
              .context
              .authority
              .store()
              .transaction(
                  () ->
                      new IngestionRepository(fixture.context.authority.store())
                          .findVideoCompilation(claim.revisionId())
                          .isEmpty()));
    }
  }

  @Test
  void independentAudioCompilationKeepsItsExistingProfileWithoutManagedTextActivation()
      throws Exception {
    byte[] pcm = new byte[64_000];
    Arrays.fill(pcm, (byte) 1);
    var models = new RecordingAudio();
    var decoderCalls = new AtomicInteger();
    var decoder =
        new AudioDecoder() {
          @Override
          public String revision() {
            return "synthetic-independent-audio-v1";
          }

          @Override
          public DecodedAudio decode(String filename, String mime, byte[] content) {
            decoderCalls.incrementAndGet();
            assertEquals("audio/wav", mime);
            return new DecodedAudio(ModelValues.sha256(content), revision(), pcm);
          }

          @Override
          public void close() {}
        };
    var audio = new AudioCompilationService(decoder, models, 1, DEADLINE);
    byte[] original = new AudioWaveform("a".repeat(64), decoder.revision(), 0, 32_000, pcm).wav();
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var vision = new RecordingVision();
      var service = ingestion(fixture, null, null, audio.revision(), null);
      var queued =
          service.uploadDocument(fixture.context.owner, "independent.wav", "audio/wav", original);
      var claim = service.claimIngestion(fixture.context.owner.workspaceId()).orElseThrow();

      process(fixture, processor(fixture, service, null, vision, audio, null), claim);

      var status = service.ingestionStatus(fixture.context.owner, queued.taskId());
      assertEquals("parsed", status.state());
      assertNull(status.errorCode());
      assertEquals(1, decoderCalls.get());
      assertEquals(2, models.transcriptions.get());
      assertEquals(0, vision.descriptions.get());
      assertNull(fixture.runtime.currentTarget());
      assertTrue(fixture.context.models.calls.isEmpty());
      assertTrue(fixture.context.projection.calls.isEmpty());
      var stored =
          fixture
              .context
              .authority
              .store()
              .transaction(
                  () ->
                      new IngestionRepository(fixture.context.authority.store())
                          .findAudioCompilation(claim.revisionId())
                          .orElseThrow());
      assertEquals(audio.revision(), stored.compilerRevision());
      assertEquals(ModelValues.sha256(original), stored.sourceSha256());
      assertEquals(2_000, stored.durationMs());
      assertEquals(
          List.of("独立合成声轨1", "独立合成声轨2"), stored.spans().stream().map(span -> span.text()).toList());
    }
  }

  private static IngestionService ingestion(
      ManagedTextTestFixture fixture,
      ImageOcrOptions images,
      RecordingVision vision,
      String audioRevision,
      String videoRevision) {
    var store = fixture.context.authority.store();
    return new IngestionService(
        store,
        new IngestionRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        images,
        vision == null ? null : new VisualIngestionOptions(vision.revision()),
        audioRevision,
        videoRevision);
  }

  private static IngestionTaskProcessor processor(
      ManagedTextTestFixture fixture,
      IngestionService service,
      ImageOcrOptions images,
      RecordingVision vision,
      AudioCompilationService audio,
      VideoCompilationService video) {
    return new IngestionTaskProcessor(
        service,
        fixture.context.owner.workspaceId(),
        DEADLINE,
        images,
        vision,
        audio,
        video,
        null,
        new LegacyTextProfileGuard(fixture.runtime, fixture.context.target));
  }

  private static void process(
      ManagedTextTestFixture fixture, IngestionTaskProcessor processor, IngestionClaim claim) {
    try (var operation = fixture.context.authority.store().operationGate().enter()) {
      processor.process(claim);
    }
  }

  private static void assertFailed(
      ManagedTextTestFixture fixture, IngestionService service, IngestionClaim claim, String code) {
    var status = service.ingestionStatus(fixture.context.owner, claim.jobId());
    assertEquals("failed", status.state());
    assertEquals(code, status.errorCode());
    assertTrue(status.canRetry());
    assertFalse(status.canCancel());
    assertTrue(
        fixture
            .context
            .authority
            .store()
            .transaction(
                () ->
                    new IngestionRepository(fixture.context.authority.store())
                        .findImageEvidence(claim.revisionId())
                        .isEmpty()));
  }

  private static void assertParsedVisual(
      ManagedTextTestFixture fixture, IngestionService service, IngestionClaim claim) {
    var status = service.ingestionStatus(fixture.context.owner, claim.jobId());
    assertEquals("parsed", status.state());
    assertNull(status.errorCode());
    var image =
        fixture
            .context
            .authority
            .store()
            .transaction(
                () ->
                    new IngestionRepository(fixture.context.authority.store())
                        .findImageEvidence(claim.revisionId())
                        .orElseThrow());
    assertEquals(DESCRIPTION, image.recallText());
    assertEquals("synthetic-managed-vision-v1", image.descriptionRevision());
    assertEquals(640, image.width());
    assertEquals(320, image.height());
    assertTrue(service.parsedEvidence(fixture.context.owner, claim.documentId()).pages().isEmpty());
  }

  private static IndexTarget activateDifferentTarget(ManagedTextTestFixture fixture) {
    var models = new AnswerTestContext.RecordingModels();
    models.modelRevision = "test-answer-model-v2";
    var target =
        new IndexTarget(
            "embedding-v2", fixture.context.projection.identity(), models.revision(), 2);
    var answers =
        new AnswerService(
            fixture.context.evidence, models, fixture.context.projection, target, DEADLINE, 2);
    var indexing =
        new IndexingTaskProcessor(
            fixture.context.authority.indexing(),
            fixture.context.owner.workspaceId(),
            target,
            DEADLINE,
            ignored -> {
              throw new AssertionError("Ingestion must not invoke an indexing worker");
            });
    var snapshot =
        new TextRuntimeSnapshot(
            2, models, fixture.context.projection, target, answers, indexing, () -> {});
    try (var lease =
        fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
      fixture.runtime.install(snapshot, lease, () -> {});
    }
    assertTrue(models.calls.isEmpty());
    return target;
  }

  private static byte[] image() throws Exception {
    return VisualSyntheticFixture.image("png").content();
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private static final class RecordingVision implements VisionModels {
    private final AtomicInteger descriptions = new AtomicInteger();
    private VisualImage lastImage;

    @Override
    public Description describe(VisualImage image) {
      descriptions.incrementAndGet();
      lastImage = image;
      return new Description(DESCRIPTION);
    }

    @Override
    public Draft draft(String question, VisualImage image) {
      throw new AssertionError("Ingestion must not draft an answer");
    }

    @Override
    public Verification verify(String question, VisualImage image, List<String> claims) {
      throw new AssertionError("Ingestion must not verify an answer");
    }

    @Override
    public String revision() {
      return "synthetic-managed-vision-v1";
    }
  }

  private static final class RecordingAudio implements AudioModels {
    private final AtomicInteger transcriptions = new AtomicInteger();

    @Override
    public String revision() {
      return "synthetic-managed-audio-v1";
    }

    @Override
    public Transcript transcribe(byte[] wav) {
      return new Transcript("独立合成声轨" + transcriptions.incrementAndGet());
    }

    @Override
    public void close() {}
  }

  private static final class ForbiddenVideoDecoder implements VideoDecoder {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public String revision() {
      return "synthetic-managed-video-v1";
    }

    @Override
    public DecodedVideo decode(String filename, String mime, byte[] source) {
      calls.incrementAndGet();
      throw new AssertionError("An incompatible queued claim must fail before video decoding");
    }

    @Override
    public void close() {}
  }
}
