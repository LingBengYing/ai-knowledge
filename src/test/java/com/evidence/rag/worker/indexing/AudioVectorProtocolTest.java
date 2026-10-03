package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.GeminiAudioEmbeddingModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioVectorBuildClaim;
import com.evidence.rag.model.domain.AudioVectorReceipt;
import com.evidence.rag.model.domain.AudioVectorSpan;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VerifiedRevision;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AudioVectorProtocolTest {
  static final Duration BUDGET = Duration.ofSeconds(5);
  static final GeminiAudioEmbeddingModels.Configuration MODEL =
      new GeminiAudioEmbeddingModels.Configuration(
          new OpenAiCompatibleModels.Endpoint(
              URI.create("http://127.0.0.1:1"), "audio", "fixture-key"),
          "audio-model-v1",
          2,
          "decoder-v1",
          BUDGET,
          65536,
          true);

  static AudioVectorProtocol.Request request() {
    try (var models = new GeminiAudioEmbeddingModels(MODEL)) {
      var projection =
          new MilvusRestProjection.Settings(
              URI.create("http://127.0.0.1:1"),
              "",
              "default",
              "java_audio_fixture",
              "org",
              models.revision(),
              2,
              BUDGET,
              65536,
              true);
      var target = new IndexTarget(models.revision(), projection.identity(), models.revision(), 2);
      var base =
          new PublicationVersion(
              "doc", "pub", "rev", "base", "a".repeat(64), "parser-v1", target, "b".repeat(64), 2);
      var spans = new ArrayList<AudioVectorSpan>();
      for (int ordinal : new int[] {0, 2}) {
        String id =
            "audio-" + ModelValues.sha256(("rev\0" + ordinal).getBytes(StandardCharsets.UTF_8));
        long start = ordinal * 16000L, end = start + (ordinal == 2 ? 3 : 16000);
        spans.add(
            new AudioVectorSpan(
                id,
                RetrievalProjection.physicalSegmentId("base", id),
                ordinal,
                ordinal * 1000L,
                ordinal * 1000L + (ordinal == 2 ? 1 : 1000),
                new AudioWaveform(
                    base.sourceSha256(),
                    "decoder-v1",
                    start,
                    end,
                    new byte[(int) (end - start) * 2])));
      }
      return AudioVectorProtocol.request(
          MODEL,
          projection,
          BUDGET,
          new AudioVectorBuildClaim(
              new Actor("org", "owner"),
              base,
              target,
              UUID.randomUUID().toString(),
              "decoder-v1",
              spans));
    }
  }

  static AudioVectorReceipt receipt(AudioVectorProtocol.Request request) {
    var claim = request.claim();
    var entries = new ArrayList<AudioVectorReceipt.Entry>();
    var digests = new TreeMap<String, String>();
    for (var span : claim.spans()) {
      String id =
          RetrievalProjection.physicalSegmentId(claim.vectorGenerationId(), span.audioEvidenceId());
      var entry =
          new RetrievalProjection.Entry(
              id,
              claim.actor().workspaceId(),
              claim.basePublication().documentId(),
              claim.vectorGenerationId(),
              span.waveform().pcmSha256(),
              List.of(0.25, 0.75));
      String digest = RetrievalProjection.entryDigest(entry);
      digests.put(id, digest);
      entries.add(new AudioVectorReceipt.Entry(id, entry.vector(), digest));
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.actor().workspaceId(),
            claim.basePublication().documentId(),
            claim.vectorGenerationId(),
            digests);
    return new AudioVectorReceipt(
        entries,
        new VerifiedRevision(
            claim.target().projectionIdentity(), manifest.sha256(), entries.size()));
  }

  @Test
  void allPcmBytesSilentOrdinalGapAndExactLastSampleRoundTrip() throws Exception {
    var request = request();
    var bytes = new ByteArrayOutputStream();
    AudioVectorProtocol.writeRequest(bytes, request);
    var decoded = AudioVectorProtocol.readRequest(new ByteArrayInputStream(bytes.toByteArray()));
    assertEquals(request.claim().basePublication(), decoded.claim().basePublication());
    assertEquals(request.parent(), decoded.parent());
    assertEquals(
        List.of(0, 2), decoded.claim().spans().stream().map(AudioVectorSpan::ordinal).toList());
    for (int i = 0; i < 2; i++) {
      assertArrayEquals(
          request.claim().spans().get(i).waveform().pcm(),
          decoded.claim().spans().get(i).waveform().pcm());
    }
    assertEquals(32003, decoded.claim().spans().getLast().waveform().endSample());
  }

  @Test
  void completeReceiptRecomputedAndGenerationReplayRejected() throws Exception {
    var request = request();
    var good = receipt(request);
    assertEquals(good, AudioVectorProtocol.decode(AudioVectorProtocol.encode(good), request));
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.decode(AudioVectorProtocol.encode(good), request()));
    var reversed = new ArrayList<>(good.entries());
    Collections.reverse(reversed);
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () ->
            AudioVectorProtocol.verify(
                request.claim(), new AudioVectorReceipt(reversed, good.verified())));
  }

  @Test
  void changedTailPcmDigestCountAndManifestRejectEntireReceipt() throws Exception {
    var request = request();
    var good = receipt(request);
    var entries = new ArrayList<>(good.entries());
    var tail = entries.getLast();
    entries.set(
        1,
        new AudioVectorReceipt.Entry(
            tail.physicalSegmentId(), List.of(0.75, 0.25), tail.entrySha256()));
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () ->
            AudioVectorProtocol.verify(
                request.claim(), new AudioVectorReceipt(entries, good.verified())));
    for (var verified :
        List.of(
            new VerifiedRevision("d".repeat(64), good.verified().manifestSha256(), 2),
            new VerifiedRevision(good.verified().projectionIdentity(), "d".repeat(64), 2))) {
      assertThrows(
          ProcessAudioVectorIndexer.Failure.class,
          () ->
              AudioVectorProtocol.decode(
                  AudioVectorProtocol.encode(new AudioVectorReceipt(good.entries(), verified)),
                  request));
    }
    byte[] valid = AudioVectorProtocol.encode(good);
    ByteBuffer.wrap(valid).putInt(valid.length - 4, 1);
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class, () -> AudioVectorProtocol.decode(valid, request));
  }

  @Test
  void malformedHeaderLengthsTrailingBytesAndOversizedPacketFailClosed() throws Exception {
    var bytes = new ByteArrayOutputStream();
    AudioVectorProtocol.writeRequest(bytes, request());
    byte[] valid = bytes.toByteArray();
    var badHeader = valid.clone();
    ByteBuffer.wrap(badHeader).putInt(0, 0);
    var badLength = valid.clone();
    ByteBuffer.wrap(badLength).putInt(16, Integer.MAX_VALUE);
    var badUtf8 = valid.clone();
    badUtf8[20] = (byte) 0xff;
    for (byte[] packet :
        List.of(
            badHeader,
            badLength,
            badUtf8,
            Arrays.copyOf(valid, 22),
            Arrays.copyOf(valid, valid.length - 1),
            Arrays.copyOf(valid, valid.length + 1),
            new byte[AudioVectorProtocol.MAX_REQUEST + 1])) {
      assertThrows(
          Exception.class, () -> AudioVectorProtocol.readRequest(new ByteArrayInputStream(packet)));
    }
  }

  @Test
  void wrongBaseMappingDecoderWorkspaceAndBudgetRejectedBeforeChildLaunch() {
    var request = request();
    var claim = request.claim();
    var span = claim.spans().getFirst();
    var wrong =
        new AudioVectorSpan(
            span.audioEvidenceId(),
            "wrong-base",
            span.ordinal(),
            span.startMs(),
            span.endMs(),
            span.waveform());
    var spans = new ArrayList<>(claim.spans());
    spans.set(0, wrong);
    var wrongBase =
        new AudioVectorBuildClaim(
            claim.actor(),
            claim.basePublication(),
            claim.target(),
            claim.vectorGenerationId(),
            claim.decoderRevision(),
            spans);
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.request(MODEL, request.projection(), BUDGET, wrongBase));
    var changed =
        new AudioVectorBuildClaim(
            new Actor("other", "owner"),
            claim.basePublication(),
            claim.target(),
            claim.vectorGenerationId(),
            claim.decoderRevision(),
            claim.spans());
    var other = changed;
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.request(MODEL, request.projection(), BUDGET, other));
    for (var budget : List.of(Duration.ofMillis(9), Duration.ofMillis(120001))) {
      assertThrows(
          ProcessAudioVectorIndexer.Failure.class,
          () -> AudioVectorProtocol.request(MODEL, request.projection(), budget, claim));
    }
  }

  @Test
  void maximumSixHundredSpansUseTheIndependentLargeProtocolWithoutDroppingTail() throws Exception {
    var template = request();
    var old = template.claim();
    var base = old.basePublication();
    var completeBase =
        new PublicationVersion(
            base.documentId(),
            base.publicationId(),
            base.sourceRevisionId(),
            base.projectionGenerationId(),
            base.sourceSha256(),
            base.parserRevision(),
            base.target(),
            base.manifestSha256(),
            600);
    var spans = new ArrayList<AudioVectorSpan>();
    for (int ordinal = 0; ordinal < 600; ordinal++) {
      String id =
          "audio-" + ModelValues.sha256(("rev\0" + ordinal).getBytes(StandardCharsets.UTF_8));
      long start = ordinal * 16000L;
      spans.add(
          new AudioVectorSpan(
              id,
              RetrievalProjection.physicalSegmentId("base", id),
              ordinal,
              ordinal * 1000L,
              (ordinal + 1) * 1000L,
              new AudioWaveform(
                  base.sourceSha256(), "decoder-v1", start, start + 16000, new byte[32000])));
    }
    var claim =
        new AudioVectorBuildClaim(
            old.actor(),
            completeBase,
            old.target(),
            old.vectorGenerationId(),
            old.decoderRevision(),
            spans);
    var request =
        AudioVectorProtocol.request(template.models(), template.projection(), BUDGET, claim);
    var bytes = new ByteArrayOutputStream();
    AudioVectorProtocol.writeRequest(bytes, request);
    assertEquals(true, bytes.size() > 8 * 1024 * 1024);
    var decoded = AudioVectorProtocol.readRequest(new ByteArrayInputStream(bytes.toByteArray()));
    assertEquals(600, decoded.claim().spans().size());
    assertEquals(9600000, decoded.claim().spans().getLast().waveform().endSample());
    assertEquals(
        receipt(request),
        AudioVectorProtocol.decode(AudioVectorProtocol.encode(receipt(request)), request));
  }

  @Test
  void damagedFailureEnvelopeNonFiniteAndZeroVectorsRejected() throws Exception {
    var request = request();
    var good = receipt(request);
    byte[] bytes = AudioVectorProtocol.encode(good);
    int offset =
        16
            + 4
            + good.entries().getFirst().physicalSegmentId().getBytes(StandardCharsets.UTF_8).length
            + 4;
    for (int value :
        new int[] {
          Float.floatToIntBits(Float.NaN), Float.floatToIntBits(Float.POSITIVE_INFINITY)
        }) {
      var damaged = bytes.clone();
      ByteBuffer.wrap(damaged).putInt(offset, value);
      assertThrows(
          ProcessAudioVectorIndexer.Failure.class,
          () -> AudioVectorProtocol.decode(damaged, request));
    }
    assertEquals(
        "audio_vector_timeout",
        assertThrows(
                ProcessAudioVectorIndexer.Failure.class,
                () -> AudioVectorProtocol.decode(AudioVectorProtocol.failure(2), request))
            .code());
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.decode(AudioVectorProtocol.failure(3), request));
    assertThrows(
        ProcessAudioVectorIndexer.Failure.class,
        () -> AudioVectorProtocol.decode(Arrays.copyOf(bytes, bytes.length + 1), request));
  }
}
