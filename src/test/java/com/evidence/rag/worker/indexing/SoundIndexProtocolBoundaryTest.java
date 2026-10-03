package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.SoundBuildClaim;
import com.evidence.rag.model.domain.SoundInputSpan;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.model.domain.SoundReceipt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SoundIndexProtocolBoundaryTest {
  @Test
  void frozenRequestRejectsOtherWorkspaceEmbeddingModelAndDimensionProfiles() {
    var seed = SoundIndexProtocolTest.request();
    var settings = seed.projection();
    for (int variation = 0; variation < 4; variation++) {
      var projection =
          new MilvusRestProjection.Settings(
              settings.endpoint(),
              settings.token(),
              settings.database(),
              settings.collection(),
              variation == 0 ? "other-workspace" : settings.workspaceId(),
              variation == 1 ? "other-embedding" : settings.embeddingIdentity(),
              variation == 2 ? 3 : settings.dimension(),
              settings.timeout(),
              settings.maxResponseBytes(),
              settings.allowLoopbackHttp());
      var target =
          variation == 3
              ? new IndexTarget(
                  seed.claim().target().embeddingIdentity(),
                  seed.claim().target().projectionIdentity(),
                  "other-model",
                  2)
              : seed.claim().target();
      var claim = claim(seed, target);
      assertThrows(
          ProcessSoundIndexer.Failure.class,
          () ->
              SoundIndexProtocol.request(
                  seed.sounds(), seed.models(), projection, seed.timeout(), claim));
    }
    var changedSound =
        new GeminiSoundModels.Configuration(
            seed.sounds().endpoint(),
            "sound-v2",
            seed.sounds().deadline(),
            seed.sounds().maxResponseBytes(),
            true);
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.request(
                changedSound, seed.models(), seed.projection(), seed.timeout(), seed.claim()));
    var otherDecoderSpans =
        seed.claim().spans().stream()
            .map(
                span ->
                    new SoundInputSpan(
                        span.id(),
                        span.ordinal(),
                        new AudioWaveform(
                            span.waveform().sourceSha256(),
                            "decoder-v2",
                            span.waveform().startSample(),
                            span.waveform().endSample(),
                            span.waveform().pcm())))
            .toList();
    var otherDecoderClaim =
        new SoundBuildClaim(
            seed.claim().actor(),
            seed.claim().original(),
            seed.claim().target(),
            seed.claim().generationId(),
            seed.claim().soundModelRevision(),
            "decoder-v2",
            1,
            otherDecoderSpans,
            SoundProfile.fingerprint(
                seed.claim().target(), seed.claim().soundModelRevision(), "decoder-v2", 1));
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.request(
                seed.sounds(),
                seed.models(),
                seed.projection(),
                seed.timeout(),
                otherDecoderClaim));
  }

  @Test
  void requestRequiresClaimAndAuthenticatedParentBeforeWritingAnyPacket() {
    var seed = SoundIndexProtocolTest.request();
    for (var request :
        List.of(
            new SoundIndexProtocol.Request(
                seed.sounds(),
                seed.models(),
                seed.projection(),
                seed.timeout(),
                null,
                seed.parent()),
            new SoundIndexProtocol.Request(
                seed.sounds(),
                seed.models(),
                seed.projection(),
                seed.timeout(),
                seed.claim(),
                null))) {
      var output = new ByteArrayOutputStream();
      assertThrows(
          ProcessSoundIndexer.Failure.class,
          () -> SoundIndexProtocol.writeRequest(output, request));
      assertEquals(0, output.size());
    }
  }

  @Test
  void responseRejectsWrongVersionMissingWindowsInvalidDimensionsFloatAndTrailingData()
      throws Exception {
    var request = SoundIndexProtocolTest.request();
    byte[] good = SoundIndexProtocol.encode(SoundIndexProtocolTest.receipt(request));
    int dimensionOffset = firstDimensionOffset(good);
    for (int count : new int[] {0, 2, 601}) {
      byte[] changed = good.clone();
      ByteBuffer.wrap(changed).putInt(12, count);
      assertThrows(
          ProcessSoundIndexer.Failure.class, () -> SoundIndexProtocol.decode(changed, request));
    }
    for (int dimensions : new int[] {1, 3, 3073}) {
      byte[] changed = good.clone();
      ByteBuffer.wrap(changed).putInt(dimensionOffset, dimensions);
      assertThrows(
          ProcessSoundIndexer.Failure.class, () -> SoundIndexProtocol.decode(changed, request));
    }
    for (float scalar : new float[] {Float.NaN, Float.POSITIVE_INFINITY}) {
      byte[] changed = good.clone();
      ByteBuffer.wrap(changed).putFloat(dimensionOffset + 4, scalar);
      assertThrows(
          ProcessSoundIndexer.Failure.class, () -> SoundIndexProtocol.decode(changed, request));
    }
    byte[] changedVersion = good.clone();
    ByteBuffer.wrap(changedVersion).putInt(4, 2);
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () -> SoundIndexProtocol.decode(changedVersion, request));
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () -> SoundIndexProtocol.decode(Arrays.copyOf(good, good.length + 1), request));
  }

  @Test
  void packetReaderRejectsInvalidBooleanAndTruncatedSourceOrPcm() throws Exception {
    var request = SoundIndexProtocolTest.request();
    byte[] good = packet(request);
    var cursor = ByteBuffer.wrap(good);
    cursor.position(16);
    for (int i = 0; i < 4; i++) {
      skipString(cursor);
    }
    cursor.getLong();
    cursor.getInt();
    int flagOffset = cursor.position();
    byte[] badFlag = good.clone();
    badFlag[flagOffset] = 2;
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () -> SoundIndexProtocol.readRequest(new ByteArrayInputStream(badFlag)));
    cursor.get();
    for (int i = 0; i < 4; i++) {
      skipString(cursor);
    }
    cursor.getInt();
    skipString(cursor);
    cursor.getLong();
    cursor.getInt();
    cursor.get();
    for (int i = 0; i < 6; i++) {
      skipString(cursor);
    }
    cursor.getInt();
    cursor.getLong();
    cursor.getInt();
    cursor.get();
    for (int i = 0; i < 8; i++) {
      skipString(cursor);
    }
    int sourceBytes = cursor.getInt();
    int sourceStart = cursor.position();
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.readRequest(
                new ByteArrayInputStream(Arrays.copyOf(good, sourceStart + sourceBytes - 1))));
    cursor.position(sourceStart + sourceBytes);
    for (int i = 0; i < 3; i++) {
      skipString(cursor);
    }
    cursor.getInt();
    for (int i = 0; i < 3; i++) {
      skipString(cursor);
    }
    cursor.getInt();
    skipString(cursor);
    cursor.getInt();
    skipString(cursor);
    cursor.getInt();
    skipString(cursor);
    skipString(cursor);
    cursor.getLong();
    cursor.getLong();
    int pcmBytes = cursor.getInt();
    int pcmStart = cursor.position();
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.readRequest(
                new ByteArrayInputStream(Arrays.copyOf(good, pcmStart + pcmBytes - 1))));
  }

  @Test
  void stringLengthCannotClaimBeyondPacketOrAdmittedMetadataLimit() throws Exception {
    var request = SoundIndexProtocolTest.request();
    byte[] good = packet(request);
    byte[] oversized = good.clone();
    ByteBuffer.wrap(oversized).putInt(16, 4097);
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () -> SoundIndexProtocol.readRequest(new ByteArrayInputStream(oversized)));
    byte[] truncated = good.clone();
    ByteBuffer.wrap(truncated).putInt(16, 4096);
    assertThrows(
        Exception.class,
        () ->
            SoundIndexProtocol.readRequest(new ByteArrayInputStream(Arrays.copyOf(truncated, 25))));
  }

  @Test
  void independentReceiptCheckRejectsPhysicalAliasAndExtraVectorDimension() {
    var request = SoundIndexProtocolTest.request();
    var good = SoundIndexProtocolTest.receipt(request);
    var first = good.entries().getFirst();
    for (var changed :
        List.of(
            new SoundReceipt.Entry(
                first.spanId(),
                "wrong-physical",
                first.recallText(),
                first.vector(),
                first.entrySha256()),
            new SoundReceipt.Entry(
                first.spanId(),
                first.physicalSegmentId(),
                first.recallText(),
                List.of(0.1, 0.9, 0.5),
                first.entrySha256()))) {
      var entries = new ArrayList<>(good.entries());
      entries.set(0, changed);
      assertThrows(
          ProcessSoundIndexer.Failure.class,
          () ->
              SoundIndexProtocol.verify(
                  request.claim(), new SoundReceipt(entries, good.verified())));
    }
  }

  private static SoundBuildClaim claim(SoundIndexProtocol.Request request, IndexTarget target) {
    var seed = request.claim();
    return new SoundBuildClaim(
        seed.actor(),
        seed.original(),
        target,
        seed.generationId(),
        seed.soundModelRevision(),
        seed.decoderRevision(),
        seed.chunkSeconds(),
        seed.spans(),
        SoundProfile.fingerprint(
            target, seed.soundModelRevision(), seed.decoderRevision(), seed.chunkSeconds()));
  }

  private static byte[] packet(SoundIndexProtocol.Request request) throws Exception {
    var bytes = new ByteArrayOutputStream();
    SoundIndexProtocol.writeRequest(bytes, request);
    return bytes.toByteArray();
  }

  private static int firstDimensionOffset(byte[] packet) {
    var cursor = ByteBuffer.wrap(packet);
    cursor.position(16);
    for (int i = 0; i < 3; i++) {
      skipString(cursor);
    }
    return cursor.position();
  }

  private static void skipString(ByteBuffer cursor) {
    int bytes = cursor.getInt();
    cursor.position(cursor.position() + bytes);
  }
}
