package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.GeminiAudioEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.AudioVectorBuildClaim;
import com.evidence.rag.model.domain.AudioVectorReceipt;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedRevision;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioVectorProtocolBoundaryTest {
  @Test
  void requestAdmissionPinsEachEmbeddingProjectionModelDimensionAndDecoderIdentity() {
    var request = AudioVectorProtocolTest.request();
    var claim = request.claim();
    var target = claim.target();
    for (var wrong :
        List.of(
            new IndexTarget(
                "other-embedding", target.projectionIdentity(), target.modelRevision(), 2),
            new IndexTarget(target.embeddingIdentity(), "e".repeat(64), target.modelRevision(), 2),
            new IndexTarget(
                target.embeddingIdentity(), target.projectionIdentity(), "other-model", 2),
            new IndexTarget(
                target.embeddingIdentity(),
                target.projectionIdentity(),
                target.modelRevision(),
                3))) {
      assertThrows(
          ProcessAudioVectorIndexer.Failure.class,
          () ->
              AudioVectorProtocol.request(
                  request.models(), request.projection(), request.timeout(), claim(claim, wrong)));
    }
    var settings = request.projection();
    var dimensionMismatch =
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
        ProcessAudioVectorIndexer.Failure.class,
        () ->
            AudioVectorProtocol.request(
                request.models(), dimensionMismatch, request.timeout(), claim));
    var changedModel =
        new GeminiAudioEmbeddingModels.Configuration(
            request.models().endpoint(),
            "other-model-revision",
            2,
            claim.decoderRevision(),
            request.models().deadline(),
            request.models().maxResponseBytes(),
            true);
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.request(changedModel, settings, request.timeout(), claim));
    var changedDecoder =
        new GeminiAudioEmbeddingModels.Configuration(
            request.models().endpoint(),
            request.models().modelRevision(),
            2,
            "decoder-v2",
            request.models().deadline(),
            request.models().maxResponseBytes(),
            true);
    try (var models = new GeminiAudioEmbeddingModels(changedDecoder)) {
      var projection =
          new MilvusRestProjection.Settings(
              settings.endpoint(),
              settings.token(),
              settings.database(),
              settings.collection(),
              settings.workspaceId(),
              models.revision(),
              2,
              settings.timeout(),
              settings.maxResponseBytes(),
              true);
      var decoderTarget =
          new IndexTarget(models.revision(), projection.identity(), models.revision(), 2);
      assertThrows(
          ProcessAudioVectorIndexer.Failure.class,
          () ->
              AudioVectorProtocol.request(
                  changedDecoder, projection, request.timeout(), claim(claim, decoderTarget)));
    }
  }

  @Test
  void wrongTailPhysicalDimensionOrDigestAndPrefixReceiptCannotVerify() throws Exception {
    var request = AudioVectorProtocolTest.request();
    var good = AudioVectorProtocolTest.receipt(request);
    var tail = good.entries().getLast();
    for (var bad :
        List.of(
            new AudioVectorReceipt.Entry("wrong-physical", tail.vector(), tail.entrySha256()),
            new AudioVectorReceipt.Entry(
                tail.physicalSegmentId(), List.of(0.25, 0.75, 0.5), tail.entrySha256()),
            new AudioVectorReceipt.Entry(
                tail.physicalSegmentId(), tail.vector(), "e".repeat(64)))) {
      var entries = new ArrayList<>(good.entries());
      entries.set(1, bad);
      var damaged = new AudioVectorReceipt(entries, good.verified());
      assertThrows(
          ProcessAudioVectorIndexer.Failure.class,
          () -> AudioVectorProtocol.verify(request.claim(), damaged));
      assertThrows(
          ProcessAudioVectorIndexer.Failure.class,
          () -> AudioVectorProtocol.decode(AudioVectorProtocol.encode(damaged), request));
    }
    var prefix =
        new AudioVectorReceipt(
            List.of(good.entries().getFirst()),
            new VerifiedRevision(
                good.verified().projectionIdentity(), good.verified().manifestSha256(), 1));
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.verify(request.claim(), prefix));
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.decode(AudioVectorProtocol.encode(prefix), request));
  }

  @Test
  void binaryRequestRejectsUnknownVersionNonBooleanFlagAndImpossibleSpanCount() throws Exception {
    var request = AudioVectorProtocolTest.request();
    var bytes = new ByteArrayOutputStream();
    AudioVectorProtocol.writeRequest(bytes, request);
    byte[] valid = bytes.toByteArray();
    int modelFlag =
        offset(valid, request.models().modelRevision())
            + request.models().modelRevision().length()
            + 4
            + 4
            + request.models().decoderRevision().length()
            + 8
            + 4;
    var badFlag = valid.clone();
    badFlag[modelFlag] = 2;
    int count =
        offset(valid, request.claim().vectorGenerationId())
            + request.claim().vectorGenerationId().length()
            + 4
            + request.claim().decoderRevision().length();
    for (byte[] packet :
        List.of(
            integer(valid, 4, AudioVectorProtocol.VERSION + 1),
            badFlag,
            integer(valid, count, 601),
            Arrays.copyOf(valid, count + 6))) {
      assertThrows(
          Exception.class, () -> AudioVectorProtocol.readRequest(new ByteArrayInputStream(packet)));
    }
  }

  @Test
  void outputLengthFailureEnvelopeAndEntireVectorDimensionAreStrictlyBounded() throws Exception {
    var request = AudioVectorProtocolTest.request();
    byte[] good = AudioVectorProtocol.encode(AudioVectorProtocolTest.receipt(request));
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.decode(new byte[AudioVectorProtocol.MAX_OUTPUT + 1], request));
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () ->
            AudioVectorProtocol.decode(Arrays.copyOf(AudioVectorProtocol.failure(1), 13), request));
    assertEquals(
        "audio_vector_unavailable",
        assertThrows(
                ProcessAudioVectorIndexer.Failure.class,
                () -> AudioVectorProtocol.decode(AudioVectorProtocol.failure(1), request))
            .code());
    int firstDimension =
        16
            + 4
            + AudioVectorProtocolTest.receipt(request)
                .entries()
                .getFirst()
                .physicalSegmentId()
                .length();
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.decode(integer(good, firstDimension, 3073), request));
    var allZero = integer(integer(good, firstDimension + 4, 0), firstDimension + 8, 0);
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.decode(allZero, request));
    assertThrows(
        Exception.class,
        () -> AudioVectorProtocol.decode(Arrays.copyOf(good, firstDimension + 6), request));
  }

  private static AudioVectorBuildClaim claim(AudioVectorBuildClaim base, IndexTarget target) {
    return new AudioVectorBuildClaim(
        base.actor(),
        base.basePublication(),
        target,
        base.vectorGenerationId(),
        base.decoderRevision(),
        base.spans());
  }

  private static byte[] integer(byte[] bytes, int offset, int value) {
    byte[] changed = bytes.clone();
    ByteBuffer.wrap(changed).putInt(offset, value);
    return changed;
  }

  private static int offset(byte[] packet, String marker) {
    byte[] value = marker.getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i <= packet.length - value.length; i++) {
      if (Arrays.equals(packet, i, i + value.length, value, 0, value.length)) {
        return i;
      }
    }
    throw new AssertionError("Fixture marker missing");
  }
}
