package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ImageVectorBuildClaim;
import com.evidence.rag.model.domain.ImageVectorReceipt;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImageVectorProtocolTest {
  static final Duration BUDGET = Duration.ofSeconds(5);
  static final SiliconFlowImageEmbeddingModels.Configuration MODEL =
      new SiliconFlowImageEmbeddingModels.Configuration(
          new OpenAiCompatibleModels.Endpoint(
              URI.create("http://127.0.0.1:1/v1"), "image", "fixture-key"),
          "image-model-v1",
          2,
          BUDGET,
          65536,
          true);

  static ImageVectorProtocol.Request request() {
    try (var models = new SiliconFlowImageEmbeddingModels(MODEL)) {
      var projection =
          new MilvusRestProjection.Settings(
              URI.create("http://127.0.0.1:1"),
              "",
              "default",
              "java_image_fixture",
              "org",
              models.revision(),
              2,
              BUDGET,
              65536,
              true);
      var target = new IndexTarget(models.revision(), projection.identity(), models.revision(), 2);
      var image = new VisualImage("image/png", new byte[] {1, 2, 3});
      var base =
          new PublicationVersion(
              "doc", "pub", "rev", "base", image.sha256(), "parser-v1", target, "a".repeat(64), 1);
      var claim =
          new ImageVectorBuildClaim(
              new Actor("org", "owner"),
              base,
              "image",
              RetrievalProjection.physicalSegmentId("base", "image"),
              target,
              UUID.randomUUID().toString(),
              image);
      return ImageVectorProtocol.request(MODEL, projection, BUDGET, claim);
    }
  }

  static ImageVectorReceipt receipt(ImageVectorProtocol.Request request) {
    var claim = request.claim();
    String id =
        RetrievalProjection.physicalSegmentId(claim.vectorGenerationId(), claim.imageEvidenceId());
    var entry =
        new RetrievalProjection.Entry(
            id,
            claim.actor().workspaceId(),
            claim.basePublication().documentId(),
            claim.vectorGenerationId(),
            claim.original().sha256(),
            List.of(0.25, 0.75));
    String digest = RetrievalProjection.entryDigest(entry);
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.actor().workspaceId(),
            claim.basePublication().documentId(),
            claim.vectorGenerationId(),
            Map.of(id, digest));
    return new ImageVectorReceipt(
        id,
        entry.vector(),
        digest,
        new VerifiedRevision(claim.target().projectionIdentity(), manifest.sha256(), 1));
  }

  @Test
  void originalBytesAndEveryFrozenIdentityRoundTrip() throws Exception {
    var request = request();
    var bytes = new ByteArrayOutputStream();
    ImageVectorProtocol.writeRequest(bytes, request);
    var decoded = ImageVectorProtocol.readRequest(new ByteArrayInputStream(bytes.toByteArray()));
    assertArrayEquals(request.claim().original().content(), decoded.claim().original().content());
    assertEquals(request.claim().basePublication(), decoded.claim().basePublication());
    assertEquals(request.claim().vectorGenerationId(), decoded.claim().vectorGenerationId());
    assertEquals(request.parent(), decoded.parent());
  }

  @Test
  void receiptIsRecomputedAndCannotBeReplayedForAnotherGeneration() throws Exception {
    var request = request();
    byte[] encoded = ImageVectorProtocol.encode(receipt(request));
    assertEquals(receipt(request), ImageVectorProtocol.decode(encoded, request));
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () -> ImageVectorProtocol.decode(encoded, request()));
  }

  @Test
  void truncatedAndTrailingRequestsAreRejected() throws Exception {
    var bytes = new ByteArrayOutputStream();
    ImageVectorProtocol.writeRequest(bytes, request());
    var original = bytes.toByteArray();
    assertThrows(
        Exception.class,
        () ->
            ImageVectorProtocol.readRequest(
                new ByteArrayInputStream(Arrays.copyOf(original, original.length - 1))));
    assertThrows(
        Exception.class,
        () ->
            ImageVectorProtocol.readRequest(
                new ByteArrayInputStream(Arrays.copyOf(original, original.length + 1))));
  }

  @Test
  void unsafeOutputLengthAndTrailingReceiptAreRejected() throws Exception {
    var request = request();
    byte[] valid = ImageVectorProtocol.encode(receipt(request));
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () -> ImageVectorProtocol.decode(Arrays.copyOf(valid, valid.length + 1), request));
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () -> ImageVectorProtocol.decode(new byte[ImageVectorProtocol.MAX_OUTPUT + 1], request));
  }

  @Test
  void admissionRejectsWrongWorkspaceBaseMappingAndEachTargetBinding() {
    var request = request();
    var claim = request.claim();
    var target = claim.target();
    var wrongClaims = new ArrayList<ImageVectorBuildClaim>();
    wrongClaims.add(
        claim(claim, new Actor("other", "owner"), claim.basePhysicalSegmentId(), target));
    wrongClaims.add(claim(claim, claim.actor(), "wrong-base-physical", target));
    for (var wrongTarget :
        List.of(
            new IndexTarget(
                "other-profile", target.projectionIdentity(), target.modelRevision(), 2),
            new IndexTarget(target.embeddingIdentity(), "d".repeat(64), target.modelRevision(), 2),
            new IndexTarget(
                target.embeddingIdentity(), target.projectionIdentity(), "other-model", 2),
            new IndexTarget(
                target.embeddingIdentity(),
                target.projectionIdentity(),
                target.modelRevision(),
                3))) {
      wrongClaims.add(claim(claim, claim.actor(), claim.basePhysicalSegmentId(), wrongTarget));
    }
    for (var wrong : wrongClaims) {
      assertEquals(
          "image_vector_output_invalid",
          assertThrows(
                  ProcessImageVectorIndexer.Failure.class,
                  () ->
                      ImageVectorProtocol.request(
                          request.models(), request.projection(), BUDGET, wrong))
              .code());
    }
    var changedModel =
        new SiliconFlowImageEmbeddingModels.Configuration(
            MODEL.endpoint(), "image-model-v2", 2, BUDGET, 65536, true);
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () -> ImageVectorProtocol.request(changedModel, request.projection(), BUDGET, claim));
  }

  @Test
  void admissionRejectsAnIncompatibleProjectionSpaceAndOutOfRangeTotalBudgets() {
    var request = request();
    var settings = request.projection();
    var otherDimension =
        new MilvusRestProjection.Settings(
            settings.endpoint(),
            settings.token(),
            settings.database(),
            settings.collection(),
            settings.workspaceId(),
            settings.embeddingIdentity(),
            3,
            settings.timeout(),
            settings.maxResponseBytes(),
            true);
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () -> ImageVectorProtocol.request(MODEL, otherDimension, BUDGET, request.claim()));
    for (var budget : List.of(Duration.ofMillis(9), Duration.ofMillis(120001))) {
      assertThrows(
          ProcessImageVectorIndexer.Failure.class,
          () -> ImageVectorProtocol.request(MODEL, settings, budget, request.claim()));
    }
  }

  @Test
  void wrongPhysicalVectorDigestProjectionManifestAndCardinalityCannotSealAReceipt()
      throws Exception {
    var request = request();
    var good = receipt(request);
    var verified = good.verified();
    var wrongReceipts =
        List.of(
            new ImageVectorReceipt("wrong-physical", good.vector(), good.entrySha256(), verified),
            new ImageVectorReceipt(
                good.physicalSegmentId(), List.of(0.75, 0.25), good.entrySha256(), verified),
            new ImageVectorReceipt(
                good.physicalSegmentId(), good.vector(), "d".repeat(64), verified),
            new ImageVectorReceipt(
                good.physicalSegmentId(),
                good.vector(),
                good.entrySha256(),
                new VerifiedRevision("d".repeat(64), verified.manifestSha256(), 1)),
            new ImageVectorReceipt(
                good.physicalSegmentId(),
                good.vector(),
                good.entrySha256(),
                new VerifiedRevision(verified.projectionIdentity(), "d".repeat(64), 1)),
            new ImageVectorReceipt(
                good.physicalSegmentId(), List.of(0.25, 0.75, 0.5), good.entrySha256(), verified));
    for (var wrong : wrongReceipts) {
      assertThrows(
          ProcessImageVectorIndexer.Failure.class,
          () -> ImageVectorProtocol.verify(request.claim(), wrong));
      assertEquals(
          "image_vector_output_invalid",
          assertThrows(
                  ProcessImageVectorIndexer.Failure.class,
                  () -> ImageVectorProtocol.decode(ImageVectorProtocol.encode(wrong), request))
              .code());
    }
    var countTwo =
        integerAt(ImageVectorProtocol.encode(good), ImageVectorProtocol.encode(good).length - 4, 2);
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () -> ImageVectorProtocol.decode(countTwo, request));
  }

  @Test
  void receiptCannotBeReplayedUnderAnotherDocumentWorkspaceOrSourceSha() throws Exception {
    var request = request();
    var claim = request.claim();
    var base = claim.basePublication();
    byte[] encoded = ImageVectorProtocol.encode(receipt(request));
    var changedDocument =
        new PublicationVersion(
            "other-doc",
            base.publicationId(),
            base.sourceRevisionId(),
            base.projectionGenerationId(),
            base.sourceSha256(),
            base.parserRevision(),
            base.target(),
            base.manifestSha256(),
            1);
    var otherOriginal = new VisualImage("image/png", new byte[] {3, 2, 1});
    var changedSource =
        new PublicationVersion(
            base.documentId(),
            base.publicationId(),
            "other-rev",
            base.projectionGenerationId(),
            otherOriginal.sha256(),
            base.parserRevision(),
            base.target(),
            base.manifestSha256(),
            1);
    var changedClaims =
        List.of(
            claim(
                claim, new Actor("other", "owner"), claim.basePhysicalSegmentId(), claim.target()),
            new ImageVectorBuildClaim(
                claim.actor(),
                changedDocument,
                claim.imageEvidenceId(),
                claim.basePhysicalSegmentId(),
                claim.target(),
                claim.vectorGenerationId(),
                claim.original()),
            new ImageVectorBuildClaim(
                claim.actor(),
                changedSource,
                claim.imageEvidenceId(),
                claim.basePhysicalSegmentId(),
                claim.target(),
                claim.vectorGenerationId(),
                otherOriginal));
    for (var changed : changedClaims) {
      var changedRequest =
          new ImageVectorProtocol.Request(
              request.models(), request.projection(), BUDGET, changed, request.parent());
      assertThrows(
          ProcessImageVectorIndexer.Failure.class,
          () -> ImageVectorProtocol.decode(encoded, changedRequest));
    }
  }

  @Test
  void malformedPacketLengthsEncodingAndOriginalSizeAreRejectedBeforeAllocation() throws Exception {
    var bytes = new ByteArrayOutputStream();
    ImageVectorProtocol.writeRequest(bytes, request());
    byte[] valid = bytes.toByteArray();
    int originalSizeOffset = offsetOf(valid, "image/png") + "image/png".length();
    int loopbackFlagOffset =
        offsetOf(valid, MODEL.modelRevision()) + MODEL.modelRevision().length() + 4 + 8 + 4;
    var badUtf8 = valid.clone();
    badUtf8[20] = (byte) 0xff;
    var invalidFlag = valid.clone();
    invalidFlag[loopbackFlagOffset] = 2;
    var disallowedLoopback = valid.clone();
    disallowedLoopback[loopbackFlagOffset] = 0;
    var packets =
        List.of(
            integerAt(valid, 0, ImageVectorProtocol.MAGIC + 1),
            integerAt(valid, 4, ImageVectorProtocol.VERSION + 1),
            integerAt(valid, 16, -1),
            integerAt(valid, 16, 4097),
            badUtf8,
            invalidFlag,
            disallowedLoopback,
            Arrays.copyOf(valid, 22),
            integerAt(valid, originalSizeOffset, 0),
            integerAt(valid, originalSizeOffset, 10 * 1024 * 1024 + 1),
            integerAt(valid, originalSizeOffset, 10000));
    for (var packet : packets) {
      assertThrows(
          Exception.class, () -> ImageVectorProtocol.readRequest(new ByteArrayInputStream(packet)));
    }
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () ->
            ImageVectorProtocol.readRequest(
                new ByteArrayInputStream(new byte[ImageVectorProtocol.MAX_REQUEST + 1])));
  }

  @Test
  void damagedFailureEnvelopeAndFloat32CoordinatesAreRejectedAsWholeOutputs() throws Exception {
    var request = request();
    var good = receipt(request);
    byte[] valid = ImageVectorProtocol.encode(good);
    int coordinates = 12 + 4 + good.physicalSegmentId().getBytes(StandardCharsets.UTF_8).length + 4;
    for (int value :
        new int[] {
          Float.floatToIntBits(Float.NaN), Float.floatToIntBits(Float.POSITIVE_INFINITY)
        }) {
      assertThrows(
          ProcessImageVectorIndexer.Failure.class,
          () -> ImageVectorProtocol.decode(integerAt(valid, coordinates, value), request));
    }
    var allZero = integerAt(integerAt(valid, coordinates, 0), coordinates + 4, 0);
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () -> ImageVectorProtocol.decode(allZero, request));
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () -> ImageVectorProtocol.decode(integerAt(valid, coordinates - 4, 8193), request));
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () -> ImageVectorProtocol.decode(ImageVectorProtocol.failure(3), request));
    assertThrows(
        ProcessImageVectorIndexer.Failure.class,
        () ->
            ImageVectorProtocol.decode(Arrays.copyOf(ImageVectorProtocol.failure(1), 13), request));
    assertEquals(
        "image_vector_timeout",
        assertThrows(
                ProcessImageVectorIndexer.Failure.class,
                () -> ImageVectorProtocol.decode(ImageVectorProtocol.failure(2), request))
            .code());
  }

  private static ImageVectorBuildClaim claim(
      ImageVectorBuildClaim original, Actor actor, String baseId, IndexTarget target) {
    return new ImageVectorBuildClaim(
        actor,
        original.basePublication(),
        original.imageEvidenceId(),
        baseId,
        target,
        original.vectorGenerationId(),
        original.original());
  }

  private static byte[] integerAt(byte[] packet, int offset, int value) {
    byte[] changed = packet.clone();
    ByteBuffer.wrap(changed).putInt(offset, value);
    return changed;
  }

  private static int offsetOf(byte[] packet, String marker) {
    byte[] value = marker.getBytes(StandardCharsets.UTF_8);
    for (int offset = 0; offset <= packet.length - value.length; offset++) {
      if (Arrays.equals(packet, offset, offset + value.length, value, 0, value.length)) {
        return offset;
      }
    }
    throw new AssertionError("Fixture marker absent");
  }
}
