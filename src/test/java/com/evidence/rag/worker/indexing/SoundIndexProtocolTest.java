package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.GeminiSoundEmbeddingModels;
import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundBuildClaim;
import com.evidence.rag.model.domain.SoundInputSpan;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.model.domain.SoundReceipt;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.tool.parser.AudioPcm;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SoundIndexProtocolTest {
  static final Duration BUDGET = Duration.ofSeconds(5);

  static SoundIndexProtocol.Request request() {
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1:1"), "sound", "fixture-key");
    var sounds = new GeminiSoundModels.Configuration(endpoint, "sound-v1", BUDGET, 65536, true);
    var models =
        new GeminiSoundEmbeddingModels.Configuration(
            endpoint, "embedding-v1", 2, "decoder-v1", BUDGET, 65536, true);
    var projection =
        new MilvusRestProjection.Settings(
            URI.create("http://127.0.0.1:1"),
            "",
            "default",
            "java_sound_fixture",
            "org",
            models.revision(),
            2,
            BUDGET,
            65536,
            true);
    var target = new IndexTarget(models.revision(), projection.identity(), models.revision(), 2);
    byte[] raw = AudioPcm.wav(new byte[64006], 0, 64006);
    var original =
        new DocumentOriginal(
            "doc",
            "rev",
            "tone.wav",
            "audio",
            "audio/wav",
            ModelValues.sha256(raw),
            raw.length,
            raw);
    var spans = new ArrayList<SoundInputSpan>();
    for (int ordinal = 0; ordinal < 3; ordinal++) {
      long start = ordinal * 16000L;
      long end = ordinal == 2 ? start + 3 : start + 16000;
      byte[] pcm = new byte[(int) (end - start) * 2];
      if (ordinal != 1) {
        pcm[0] = (byte) (ordinal + 1);
      }
      spans.add(
          new SoundInputSpan(
              SoundProfile.spanId("rev", ordinal),
              ordinal,
              new AudioWaveform(original.sourceSha256(), "decoder-v1", start, end, pcm)));
    }
    return SoundIndexProtocol.request(
        sounds,
        models,
        projection,
        BUDGET,
        new SoundBuildClaim(
            new Actor("org", "owner"),
            original,
            target,
            UUID.randomUUID().toString(),
            sounds.revision(),
            "decoder-v1",
            1,
            spans,
            SoundProfile.fingerprint(target, sounds.revision(), "decoder-v1", 1)));
  }

  static SoundReceipt receipt(SoundIndexProtocol.Request request) {
    var claim = request.claim();
    var entries = new ArrayList<SoundReceipt.Entry>();
    var digests = new TreeMap<String, String>();
    for (var span : claim.spans()) {
      String id = RetrievalProjection.physicalSegmentId(claim.generationId(), span.id());
      var entry =
          new RetrievalProjection.Entry(
              id,
              claim.actor().workspaceId(),
              claim.original().documentId(),
              claim.generationId(),
              span.waveform().pcmSha256(),
              List.of(0.1, 0.9));
      String digest = RetrievalProjection.entryDigest(entry);
      digests.put(id, digest);
      entries.add(
          new SoundReceipt.Entry(
              span.id(), id, span.ordinal() == 1 ? "" : "tone", entry.vector(), digest));
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.actor().workspaceId(),
            claim.original().documentId(),
            claim.generationId(),
            digests);
    return new SoundReceipt(
        entries,
        new VerifiedRevision(
            claim.target().projectionIdentity(), manifest.sha256(), entries.size()));
  }

  @Test
  void rawSourceAllWindowsSilenceAndActualTailRoundTrip() throws Exception {
    var request = request();
    var bytes = new ByteArrayOutputStream();
    SoundIndexProtocol.writeRequest(bytes, request);
    var decoded = SoundIndexProtocol.readRequest(new ByteArrayInputStream(bytes.toByteArray()));
    assertArrayEquals(request.claim().original().content(), decoded.claim().original().content());
    assertEquals(request.claim().profileFingerprint(), decoded.claim().profileFingerprint());
    assertEquals(request.parent(), decoded.parent());
    assertEquals(
        List.of(0, 1, 2), decoded.claim().spans().stream().map(SoundInputSpan::ordinal).toList());
    for (int i = 0; i < 3; i++) {
      assertArrayEquals(
          request.claim().spans().get(i).waveform().pcm(),
          decoded.claim().spans().get(i).waveform().pcm());
    }
    assertEquals(32003, decoded.claim().spans().getLast().waveform().endSample());
  }

  @Test
  void float32ReceiptEmptyRecallAndCompleteManifestRoundTrip() throws Exception {
    var request = request();
    var receipt = receipt(request);
    assertEquals(receipt, SoundIndexProtocol.decode(SoundIndexProtocol.encode(receipt), request));
    assertEquals("", receipt.entries().get(1).recallText());
    assertEquals((double) (float) 0.1, receipt.entries().getFirst().vector().getFirst());
  }

  @Test
  void generationReplayReorderingAndWrongSpanIdentityRejectEntireReceipt() throws Exception {
    var request = request();
    var receipt = receipt(request);
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () -> SoundIndexProtocol.decode(SoundIndexProtocol.encode(receipt), request()));
    var reversed = new ArrayList<>(receipt.entries());
    Collections.reverse(reversed);
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.verify(
                request.claim(), new SoundReceipt(reversed, receipt.verified())));
    var changed = new ArrayList<>(receipt.entries());
    var first = changed.getFirst();
    changed.set(
        0,
        new SoundReceipt.Entry(
            "wrong-span",
            first.physicalSegmentId(),
            first.recallText(),
            first.vector(),
            first.entrySha256()));
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.verify(
                request.claim(), new SoundReceipt(changed, receipt.verified())));
  }

  @Test
  void tailVectorEntryDigestCountAndProjectionIdentityAreIndependentChecks() {
    var request = request();
    var good = receipt(request);
    var changed = new ArrayList<>(good.entries());
    var tail = changed.getLast();
    changed.set(
        2,
        new SoundReceipt.Entry(
            tail.spanId(),
            tail.physicalSegmentId(),
            tail.recallText(),
            List.of(0.5, 0.5),
            tail.entrySha256()));
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.verify(request.claim(), new SoundReceipt(changed, good.verified())));
    var shortEntries = good.entries().subList(0, 2);
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.verify(
                request.claim(),
                new SoundReceipt(
                    shortEntries,
                    new VerifiedRevision(
                        request.claim().target().projectionIdentity(),
                        good.verified().manifestSha256(),
                        2))));
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.verify(
                request.claim(),
                new SoundReceipt(
                    good.entries(),
                    new VerifiedRevision("b".repeat(64), good.verified().manifestSha256(), 3))));
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.verify(
                request.claim(),
                new SoundReceipt(
                    good.entries(),
                    new VerifiedRevision(
                        request.claim().target().projectionIdentity(), "c".repeat(64), 3))));
  }

  @Test
  void truncatedMalformedHeaderTrailingBytesAndOversizePacketsAreRejected() throws Exception {
    var request = request();
    var bytes = new ByteArrayOutputStream();
    SoundIndexProtocol.writeRequest(bytes, request);
    byte[] packet = bytes.toByteArray();
    assertThrows(
        Exception.class,
        () ->
            SoundIndexProtocol.readRequest(
                new ByteArrayInputStream(Arrays.copyOf(packet, packet.length - 1))));
    assertThrows(
        Exception.class,
        () ->
            SoundIndexProtocol.readRequest(
                new ByteArrayInputStream(Arrays.copyOf(packet, packet.length + 1))));
    packet[0] = 0;
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () -> SoundIndexProtocol.readRequest(new ByteArrayInputStream(packet)));
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.readRequest(
                new ByteArrayInputStream(new byte[SoundIndexProtocol.MAX_REQUEST + 1])));
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () -> SoundIndexProtocol.decode(new byte[SoundIndexProtocol.MAX_OUTPUT + 1], request));
  }

  @Test
  void unsafeLengthAndInvalidUtf8AreRejectedBeforeAnyProvider() throws Exception {
    var request = request();
    var bytes = new ByteArrayOutputStream();
    SoundIndexProtocol.writeRequest(bytes, request);
    byte[] negative = bytes.toByteArray();
    ByteBuffer.wrap(negative).putInt(16, -1);
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () -> SoundIndexProtocol.readRequest(new ByteArrayInputStream(negative)));
    byte[] utf8 = bytes.toByteArray();
    utf8[20] = (byte) 0xc0;
    assertThrows(
        Exception.class, () -> SoundIndexProtocol.readRequest(new ByteArrayInputStream(utf8)));
  }

  @Test
  void responseStatusIsExactAndMalformedFailureNeverBecomesSuccess() throws Exception {
    var request = request();
    assertEquals(
        "sound_index_timeout",
        assertThrows(
                ProcessSoundIndexer.Failure.class,
                () -> SoundIndexProtocol.decode(SoundIndexProtocol.failure(2), request))
            .code());
    assertEquals(
        "sound_index_unavailable",
        assertThrows(
                ProcessSoundIndexer.Failure.class,
                () -> SoundIndexProtocol.decode(SoundIndexProtocol.failure(1), request))
            .code());
    assertEquals(
        "sound_index_output_invalid",
        assertThrows(
                ProcessSoundIndexer.Failure.class,
                () -> SoundIndexProtocol.decode(SoundIndexProtocol.failure(3), request))
            .code());
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () -> SoundIndexProtocol.decode(Arrays.copyOf(SoundIndexProtocol.failure(1), 13), request));
  }

  @Test
  void mismatchedProjectionModelDecoderAndTimeoutAreRejectedOffline() {
    var request = request();
    var wrongProjection =
        new MilvusRestProjection.Settings(
            URI.create("http://127.0.0.1:1"),
            "",
            "default",
            "java_sound_other",
            "org",
            request.models().revision(),
            2,
            BUDGET,
            65536,
            true);
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.request(
                request.sounds(), request.models(), wrongProjection, BUDGET, request.claim()));
    var wrongDecoder =
        new GeminiSoundEmbeddingModels.Configuration(
            request.models().endpoint(), "embedding-v1", 2, "decoder-v2", BUDGET, 65536, true);
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.request(
                request.sounds(), wrongDecoder, request.projection(), BUDGET, request.claim()));
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.validateSettings(
                request.sounds(), request.models(), request.projection(), Duration.ofMillis(9)));
    assertThrows(
        ProcessSoundIndexer.Failure.class,
        () ->
            SoundIndexProtocol.validateSettings(
                request.sounds(),
                request.models(),
                request.projection(),
                Duration.ofMillis(120001)));
  }
}
